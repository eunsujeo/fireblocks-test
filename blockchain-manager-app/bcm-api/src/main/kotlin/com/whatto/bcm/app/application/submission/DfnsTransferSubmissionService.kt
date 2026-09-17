package com.whatto.bcm.app.application.submission

import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.asset.AssetDecimals
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.submission.NetworkTransferSubmissionAction
import com.whatto.bcm.domain.submission.NetworkTransferSubmissionPolicy
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.vendor.NetworkTransferPort
import com.whatto.bcm.domain.vendor.NetworkTransferRequest
import com.whatto.bcm.domain.vendor.NetworkTransferSubmission
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.wallet.NetworkWalletProvisioningRepository
import com.whatto.bcm.support.submission.SubmissionRequestFingerprint
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import com.whatto.bcm.support.time.CoreDateTimes
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Dfns 경로의 출금 제출 유스케이스(계약13 "출금 제출 계약"). 02의 선기록·소유권·벤더 호출은 트랜잭션 밖·`FAILED` 판정 기준을 그대로 쓰고
 * **회수 수단만 다르다** — 벤더에 `externalId` 조회가 없으므로 **같은 본문 재제출**이 회수다(공식 Idempotency 계약).
 *
 * 세 가지가 Fireblocks 경로와 다르다.
 * 1. 제출 키가 50자를 넘으면 **원장에 적기 전에** 거절한다 — 원장 폭(128)이 벤더 한계(50)보다 넓어 제출되지 못할 행을 만들 수 있다.
 * 2. 회수는 조회가 아니라 멱등 재제출이다. 본문은 저장된 canonical 값에서 결정적으로 다시 만들고, 재구성 전에 `req_hash`로 같은 요청임을 확인한다.
 * 3. `FAILED` 재시도를 열지 않는다 — 벤더 `Failed`가 체인 제출 여부를 확정하지 못해 새 키로 보내면 이중 지급이 될 수 있다([NetworkTransferSubmissionPolicy]).
 */
class DfnsTransferSubmissionService(
    private val submissions: SubmissionRecordRepository,
    private val wallets: NetworkWalletProvisioningRepository,
    private val mappings: VendorAssetMappingQueryService,
    private val vendor: NetworkTransferPort,
    private val transactionRunner: TransactionRunner,
    private val origin: ProviderOrigin,
    private val clock: Clock,
    private val properties: TransactionSubmissionProperties,
) : TransactionSubmissionWork {
    override fun submit(command: TransactionSubmissionCommand): TransactionSubmissionResult {
        // 원장보다 먼저 본다 — 제출될 수 없는 키로 REQUESTED 행을 만들지 않기 위해서다(계약13).
        NetworkTransferSubmissionPolicy.requireSubmittableKey(command.externalTransactionId)
        val prepared = prepare(command)
        val fingerprint = fingerprint(command, prepared)
        val claim = newClaim()

        val attempt = insertOrFind(requestedRecord(command, prepared, fingerprint, claim))
        ensureSameRequest(attempt.record, command, prepared, fingerprint)
        if (attempt.isNew) return submitToVendor(prepared, command.externalTransactionId, claim.id)

        return when (NetworkTransferSubmissionPolicy.decide(attempt.record)) {
            NetworkTransferSubmissionAction.AlreadySubmitted ->
                TransactionSubmissionResult(
                    checkNotNull(attempt.record.vendorTransactionId) { "SUBMITTED submission has no vendor transaction id" },
                )

            NetworkTransferSubmissionAction.Recover -> {
                val acquired = acquireClaim(command.externalTransactionId, claim)
                if (acquired.status == SubmissionStatus.SUBMITTED) {
                    TransactionSubmissionResult(
                        checkNotNull(acquired.vendorTransactionId) { "SUBMITTED submission has no vendor transaction id" },
                    )
                } else {
                    // 같은 본문 재제출이 곧 회수다 — 벤더가 처음 만든 엔티티를 200으로 돌려주므로 이중 전송이 아니다.
                    submitToVendor(prepared, command.externalTransactionId, claim.id)
                }
            }

            NetworkTransferSubmissionAction.RetryNotAllowed ->
                NetworkTransferSubmissionPolicy.rejectRetry(command.externalTransactionId)

            // 행이 있는데 Submit이 나올 수 없다 — decide는 null에서만 Submit을 낸다.
            NetworkTransferSubmissionAction.Submit -> error("existing submission decided as new submit")
        }
    }

    /**
     * 벤더 본문을 저장 가능한 canonical 값으로 만든다. 여기서 만든 값만으로 [NetworkTransferRequest]가 결정되므로
     * 회수 때도 같은 입력에서 같은 바이트가 나온다 — 원문 JSON을 보관해 재사용하지 않는다(계약13).
     */
    private fun prepare(command: TransactionSubmissionCommand): PreparedNetworkTransfer {
        val recipient = command.recipient
        // Dfns 경로의 목적지는 주소뿐이다 — 계정 간 내부이체·화이트리스트 지갑은 별도 계약 전이라 만들지 않는다.
        val destinationAddress =
            when (recipient) {
                is TransactionSubmissionRecipient.Address -> recipient.address
                else -> throw InvalidRequestException("recipient")
            }
        val mapping = mappings.requiredCurrentMapping(command.network, command.symbol)
        // 정밀도가 없으면 최소 단위를 만들 수 없다. 0이나 사람 단위를 그대로 보내지 않는다 — 단위가 뒤섞이면 조용한 금액 사고다.
        val decimals = mapping.decimals ?: throw InvalidRequestException("decimals")
        val scope = NetworkWalletScope(origin, command.senderAccountId, command.network)
        val wallet = wallets.findWallet(scope) ?: throw InvalidRequestException("senderAccountId")
        return PreparedNetworkTransfer(
            scope = scope,
            vendorWalletId = wallet.vendorWalletId,
            vendorAssetId = mapping.vendorAssetId,
            destinationAddress = destinationAddress,
            amountBaseUnits = AssetDecimals.baseUnitsOf(command.amount, decimals),
        )
    }

    private fun submitToVendor(
        prepared: PreparedNetworkTransfer,
        externalTransactionId: String,
        claimId: String,
    ): TransactionSubmissionResult {
        val request = prepared.request(externalTransactionId)
        return when (val result = vendor.submit(request)) {
            is NetworkTransferSubmission.Accepted -> {
                val observation = result.observation
                // 벤더의 본문 대조를 믿되 돌려받은 값으로 다시 본다 — 원장에 남길 값은 관찰에서 읽는다.
                if (!NetworkTransferSubmissionPolicy.matches(observation, request)) {
                    throw ConflictException("submission", externalTransactionId)
                }
                transactionRunner.run {
                    submissions.markSubmittedByClaim(
                        externalTransactionId,
                        claimId,
                        observation.transferId,
                        CoreDateTimes.now(clock),
                    )
                }
                TransactionSubmissionResult(observation.transferId)
            }

            is NetworkTransferSubmission.Conflict -> {
                // 표식 있는 409는 요청 자체가 거절된 것이 확실하다(02의 409·422 계열) — FAILED로 굳힌다.
                // 표식 없는 409는 어댑터가 일반 벤더 오류로 전파하므로 여기 오지 않고 REQUESTED가 유지된다.
                val rejected = RelayRejectedException("transfer request conflicts with the submission key")
                try {
                    transactionRunner.run {
                        submissions.markFailedByClaim(externalTransactionId, claimId, CoreDateTimes.now(clock))
                    }
                } catch (conflict: ConflictException) {
                    rejected.addSuppressed(conflict)
                }
                throw rejected
            }
        }
    }

    private fun acquireClaim(
        externalTransactionId: String,
        claim: SubmissionClaim,
    ): SubmissionRecord {
        val acquired =
            transactionRunner.run {
                submissions.tryClaim(externalTransactionId, claim.id, claim.expiresAt, CoreDateTimes.now(clock))
            }
        if (acquired != null) return acquired

        val current =
            submissions.findByExternalTransactionId(externalTransactionId)
                ?: throw ConflictException("submission", externalTransactionId)
        if (current.status == SubmissionStatus.SUBMITTED) return current
        // 소유권을 못 잡은 후발 요청은 기다리지 않는다 — 기다리면 벤더 지연이 API 전체를 막는다(02).
        throw SubmissionInProgressException(externalTransactionId, retryAfterSeconds(current))
    }

    private fun insertOrFind(requested: SubmissionRecord): SubmissionAttempt =
        try {
            transactionRunner.run { SubmissionAttempt(submissions.insert(requested), isNew = true) }
        } catch (conflict: ConflictException) {
            SubmissionAttempt(
                submissions.findByExternalTransactionId(requested.externalTransactionId) ?: throw conflict,
                isNew = false,
            )
        }

    private fun fingerprint(
        command: TransactionSubmissionCommand,
        prepared: PreparedNetworkTransfer,
    ): SubmissionRequestFingerprint =
        SubmissionRequestHashes.v1(
            senderType = "ACCOUNT",
            senderAccountId = command.senderAccountId,
            recipientType = SubmissionRecipientType.ADDRESS.name,
            recipientValue = prepared.destinationAddress,
            network = command.network,
            symbol = command.symbol,
            amount = command.amount,
        )

    private fun requestedRecord(
        command: TransactionSubmissionCommand,
        prepared: PreparedNetworkTransfer,
        fingerprint: SubmissionRequestFingerprint,
        claim: SubmissionClaim,
    ): SubmissionRecord =
        SubmissionRecord(
            externalTransactionId = command.externalTransactionId,
            requestHash = fingerprint.requestHash,
            hashVersion = fingerprint.hashVersion,
            status = SubmissionStatus.REQUESTED,
            claimId = claim.id,
            claimExpiresAt = claim.expiresAt,
            transactionType = SubmissionRecipientType.ADDRESS.transactionType(),
            vendorTransactionId = null,
            senderAccountId = command.senderAccountId,
            recipientType = SubmissionRecipientType.ADDRESS,
            recipientValue = prepared.destinationAddress,
            network = command.network,
            symbol = command.symbol,
            amount = fingerprint.normalizedAmount,
            requestedAt = CoreDateTimes.now(clock),
            respondedAt = null,
        )

    private fun ensureSameRequest(
        existing: SubmissionRecord,
        command: TransactionSubmissionCommand,
        prepared: PreparedNetworkTransfer,
        fingerprint: SubmissionRequestFingerprint,
    ) {
        val same =
            if (existing.hashVersion == fingerprint.hashVersion) {
                existing.requestHash == fingerprint.requestHash
            } else {
                existing.senderAccountId == command.senderAccountId &&
                    existing.recipientType == SubmissionRecipientType.ADDRESS &&
                    existing.recipientValue == prepared.destinationAddress &&
                    existing.network == command.network &&
                    existing.symbol == command.symbol &&
                    BigDecimal(existing.amount).compareTo(BigDecimal(command.amount)) == 0
            }
        if (!same) throw ConflictException("submission", command.externalTransactionId)
    }

    private fun newClaim(): SubmissionClaim =
        SubmissionClaim(
            id = UUID.randomUUID().toString(),
            expiresAt = CoreDateTimes.format(CoreDateTimes.current(clock).plusSeconds(properties.claimTtlSeconds)),
        )

    private fun retryAfterSeconds(record: SubmissionRecord): Long {
        val expiresAt = record.claimExpiresAt?.let(CoreDateTimes::parse) ?: return 1
        return Duration.between(CoreDateTimes.current(clock), expiresAt).seconds.coerceAtLeast(1)
    }

    private data class SubmissionClaim(
        val id: String,
        val expiresAt: String,
    )

    private data class SubmissionAttempt(
        val record: SubmissionRecord,
        val isNew: Boolean,
    )

    /** 벤더 본문을 이루는 canonical 값 전부. 같은 입력에서 항상 같은 [NetworkTransferRequest]가 나온다(회수의 "같은 본문"). */
    private data class PreparedNetworkTransfer(
        val scope: NetworkWalletScope,
        val vendorWalletId: String,
        val vendorAssetId: String,
        val destinationAddress: String,
        val amountBaseUnits: String,
    ) {
        fun request(externalTransactionId: String): NetworkTransferRequest =
            NetworkTransferRequest(
                scope = scope,
                vendorWalletId = vendorWalletId,
                vendorAssetId = vendorAssetId,
                destinationAddress = destinationAddress,
                amountBaseUnits = amountBaseUnits,
                externalId = externalTransactionId,
            )
    }
}
