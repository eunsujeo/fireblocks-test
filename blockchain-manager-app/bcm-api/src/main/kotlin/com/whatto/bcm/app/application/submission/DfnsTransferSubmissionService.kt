package com.whatto.bcm.app.application.submission

import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.asset.AssetDecimals
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.UnprocessableRequestException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.submission.NetworkTransferSubmissionAction
import com.whatto.bcm.domain.submission.NetworkTransferSubmissionPolicy
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical
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
 * Fireblocks 경로와 다른 네 가지.
 * 1. 제출 키가 50자를 넘으면 **원장에 적기 전에** 거절한다 — 원장 폭(128)이 벤더 한계(50)보다 넓어 제출되지 못할 행을 만들 수 있다.
 * 2. **원장을 먼저 읽는다.** 이미 결말이 난 건은 현재 자산 매핑을 읽지 않고 답한다 — 매핑이 해제됐다고 원래 `txId`를 못 돌려주면 안 된다.
 * 3. 회수 본문은 **제출 시점에 저장한 벤더 canonical 값**(03 V28)으로만 만든다. 회수 시점의 매핑·정밀도를 다시 읽으면
 *    그 사이 교체된 값으로 다른 본문이 나가 "같은 본문"이라는 멱등 회수의 안전 조건이 깨진다.
 * 4. `FAILED` 재시도를 열지 않는다 — 벤더 `Failed`가 체인 제출 여부를 확정하지 못해 새 키로 보내면 이중 지급이 될 수 있다.
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
        val destinationAddress = destinationAddressOf(command)
        val fingerprint = fingerprint(command, destinationAddress)

        // 기존 행이 있으면 벤더 조회는 물론 자산 매핑도 읽지 않는다 — 결말은 원장이 이미 알고 있다.
        submissions.findByExternalTransactionId(command.externalTransactionId)?.let { existing ->
            return resume(command, destinationAddress, fingerprint, existing)
        }

        val prepared = prepare(command, destinationAddress)
        val claim = newClaim()
        val attempt = insertOrFind(requestedRecord(command, prepared, fingerprint, claim))
        if (!attempt.isNew) return resume(command, destinationAddress, fingerprint, attempt.record)
        return submitToVendor(prepared, command.externalTransactionId, claim.id, firstSubmission = true)
    }

    /** 이미 원장에 있는 키의 처리. 내용 대조를 먼저 하고 상태에 따라 갈린다. */
    private fun resume(
        command: TransactionSubmissionCommand,
        destinationAddress: String,
        fingerprint: SubmissionRequestFingerprint,
        current: SubmissionRecord,
    ): TransactionSubmissionResult {
        ensureSameRequest(current, command, destinationAddress, fingerprint)
        return when (NetworkTransferSubmissionPolicy.decide(current)) {
            NetworkTransferSubmissionAction.AlreadySubmitted -> result(current)

            NetworkTransferSubmissionAction.Recover -> {
                val claim = newClaim()
                val acquired = acquireClaim(command.externalTransactionId, claim)
                if (acquired.status == SubmissionStatus.SUBMITTED) {
                    result(acquired)
                } else {
                    // 같은 본문 재제출이 곧 회수다 — 벤더가 처음 만든 엔티티를 200으로 돌려주므로 이중 전송이 아니다.
                    submitToVendor(storedRequest(acquired), command.externalTransactionId, claim.id, firstSubmission = false)
                }
            }

            NetworkTransferSubmissionAction.RetryNotAllowed ->
                NetworkTransferSubmissionPolicy.rejectRetry(command.externalTransactionId)

            // 행이 있는데 Submit이 나올 수 없다 — decide는 null에서만 Submit을 낸다.
            NetworkTransferSubmissionAction.Submit -> error("existing submission decided as new submit")
        }
    }

    private fun destinationAddressOf(command: TransactionSubmissionCommand): String =
        // Dfns 경로의 목적지는 주소뿐이다 — 계정 간 내부이체·화이트리스트 지갑은 별도 계약 전이라 만들지 않는다.
        when (val recipient = command.recipient) {
            is TransactionSubmissionRecipient.Address -> recipient.address
            else -> throw InvalidRequestException("recipient")
        }

    /** 최초 제출의 본문을 만든다. 여기서 확정한 값은 원장에 함께 적혀 회수 때 그대로 다시 쓰인다(03 V28). */
    private fun prepare(
        command: TransactionSubmissionCommand,
        destinationAddress: String,
    ): PreparedNetworkTransfer {
        val mapping = mappings.requiredCurrentMapping(command.network, command.symbol)
        // 정밀도가 없으면 최소 단위를 만들 수 없다. 0이나 사람 단위를 그대로 보내지 않는다 — 단위가 뒤섞이면 조용한 금액 사고다.
        val decimals = mapping.decimals ?: throw InvalidRequestException("decimals")
        val scope = NetworkWalletScope(origin, command.senderAccountId, command.network)
        val wallet = wallets.findWallet(scope) ?: throw InvalidRequestException("senderAccountId")
        return PreparedNetworkTransfer(
            scope = scope,
            destinationAddress = destinationAddress,
            canonical =
                SubmissionVendorCanonical(
                    vendorWalletId = wallet.vendorWalletId,
                    vendorAssetId = mapping.vendorAssetId,
                    amountBaseUnits = AssetDecimals.baseUnitsOf(command.amount, decimals),
                    decimals = decimals,
                ),
        )
    }

    /**
     * 회수 본문을 **저장된 값에서만** 만든다. 현재 매핑을 다시 읽지 않는다 — 그 사이 자산 키나 정밀도가 바뀌었으면
     * 같은 키로 다른 본문이 나가 이중 전송이 된다.
     *
     * 저장된 값이 없으면(V28 이전 행·다른 제공자가 만든 행) 재구성할 수 없다. **추측해서 보내지 않고** 거절한다 —
     * 회수는 못 하지만 원장은 `REQUESTED`로 남아 운영이 판단할 수 있다.
     */
    private fun storedRequest(record: SubmissionRecord): PreparedNetworkTransfer {
        val canonical =
            record.vendorCanonical
                ?: throw UnprocessableRequestException("submission", record.externalTransactionId)
        return PreparedNetworkTransfer(
            scope = NetworkWalletScope(origin, record.senderAccountId, record.network),
            destinationAddress = record.recipientValue,
            canonical = canonical,
        )
    }

    private fun submitToVendor(
        prepared: PreparedNetworkTransfer,
        externalTransactionId: String,
        claimId: String,
        firstSubmission: Boolean,
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

            is NetworkTransferSubmission.Conflict -> conflict(externalTransactionId, claimId, firstSubmission)
        }
    }

    /**
     * 표식 있는 `409`의 처리. **최초 제출에서만 확정 거절이다** — 그때는 우리가 이 키로 보낸 적이 없는데 벤더가 다른 본문을 이미 갖고 있다는 뜻이라
     * 02의 `409`·`422` 계열로 `FAILED`가 맞다.
     *
     * 회수(재제출)에서는 `FAILED`로 굳히지 않는다. 앞 제출이 **진행 중일 때** 같은 본문 재제출이 무엇을 주는지는 아직 실측하지 못한
     * 수용 항목이라(계약13), 진행 중 전송이 `409`로 보일 가능성을 배제할 수 없다. 그 상태에서 종결로 적으면 실제로 나간 전송에
     * 확정 거절을 돌려주게 되고 지금은 되살릴 경로(발신 이동 대조)도 없다. 그래서 `REQUESTED`를 유지하고 재시도 가능한 실패로 올린다.
     */
    private fun conflict(
        externalTransactionId: String,
        claimId: String,
        firstSubmission: Boolean,
    ): Nothing {
        if (!firstSubmission) {
            throw VendorApiException("resubmitTransfer", null, null)
        }
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

    private fun result(record: SubmissionRecord): TransactionSubmissionResult =
        TransactionSubmissionResult(
            checkNotNull(record.vendorTransactionId) { "SUBMITTED submission has no vendor transaction id" },
        )

    private fun fingerprint(
        command: TransactionSubmissionCommand,
        destinationAddress: String,
    ): SubmissionRequestFingerprint =
        SubmissionRequestHashes.v1(
            senderType = "ACCOUNT",
            senderAccountId = command.senderAccountId,
            recipientType = SubmissionRecipientType.ADDRESS.name,
            recipientValue = destinationAddress,
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
            vendorCanonical = prepared.canonical,
        )

    private fun ensureSameRequest(
        existing: SubmissionRecord,
        command: TransactionSubmissionCommand,
        destinationAddress: String,
        fingerprint: SubmissionRequestFingerprint,
    ) {
        val same =
            if (existing.hashVersion == fingerprint.hashVersion) {
                existing.requestHash == fingerprint.requestHash
            } else {
                existing.senderAccountId == command.senderAccountId &&
                    existing.recipientType == SubmissionRecipientType.ADDRESS &&
                    existing.recipientValue == destinationAddress &&
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

    /** 벤더 본문을 이루는 값 전부. 같은 입력에서 항상 같은 [NetworkTransferRequest]가 나온다(회수의 "같은 본문"). */
    private data class PreparedNetworkTransfer(
        val scope: NetworkWalletScope,
        val destinationAddress: String,
        val canonical: SubmissionVendorCanonical,
    ) {
        fun request(externalTransactionId: String): NetworkTransferRequest =
            NetworkTransferRequest(
                scope = scope,
                vendorWalletId = canonical.vendorWalletId,
                vendorAssetId = canonical.vendorAssetId,
                destinationAddress = destinationAddress,
                amountBaseUnits = canonical.amountBaseUnits,
                externalId = externalTransactionId,
            )
    }
}
