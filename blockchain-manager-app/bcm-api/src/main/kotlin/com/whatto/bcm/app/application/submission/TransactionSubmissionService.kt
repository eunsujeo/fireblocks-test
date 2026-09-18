package com.whatto.bcm.app.application.submission

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.ExecutionGatePolicy
import com.whatto.bcm.domain.admin.ExecutionGateRepository
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.submission.SubmissionConflictAlert
import com.whatto.bcm.domain.submission.SubmissionConflictAlertPort
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionRequestPolicy
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import com.whatto.bcm.support.submission.SubmissionRequestFingerprint
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import com.whatto.bcm.support.time.CoreDateTimes
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Fireblocks·로컬 경로의 제출 유스케이스. 회수는 `externalTxId` 단건 조회다(02 "벤더에 나갔는데 우리 기록이 없을 때").
 * Dfns 경로는 같은 경계의 [DfnsTransferSubmissionService]가 맡는다 — 제공자마다 하나만 조립된다.
 */
@Service
@ConditionalOnFireblocksProtocol
class TransactionSubmissionService(
    private val submissions: SubmissionRecordRepository,
    private val accounts: AccountQueryService,
    private val mappings: VendorAssetMappingQueryService,
    private val vendor: VendorTransactionPort,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val properties: TransactionSubmissionProperties,
    private val conflictAlerts: SubmissionConflictAlertPort,
    private val executionGates: ExecutionGateRepository,
) : TransactionSubmissionWork {
    override fun submit(command: TransactionSubmissionCommand): TransactionSubmissionResult {
        // 벤더 자원(vault·자산 매핑·수신 계정)은 **실제로 제출할 때만** 읽는다 — 먼저 읽으면 기존 키의 멱등 응답과 회수가
        // 현재 자원 오류에 가려진다(02 "신규 키 선행 검사"). 판정에 필요한 값은 요청만으로 정해진다.
        val logical = logical(command)
        enforceDistinctAccounts(command)
        enforceExecutionGate(command, logical)
        return submit(command, logical) { prepare(command) }
    }

    /** 요청만으로 정해지는 값 — 벤더를 읽지 않는다. */
    private fun logical(command: TransactionSubmissionCommand) =
        LogicalSubmission(
            recipientType = command.recipient.type,
            recipientValue = command.recipient.value,
            transactionType = command.recipient.type.transactionType(),
        )

    /**
     * 자기 계정으로 보내는 요청을 막는다 — **제공자 공통 정책**이다(02 "신규 키 선행 검사").
     *
     * 이미 `REQUESTED`·`SUBMITTED`인 키는 그대로 둔다. 회수는 이미 벌어진 일의 불확실성 해소라 막으면 그 거래가 고립되고,
     * `SUBMITTED`는 되돌릴 수도 없다. 반대로 `FAILED` 재시도는 **새 자금 이동**이라 막는다.
     */
    private fun enforceDistinctAccounts(command: TransactionSubmissionCommand) {
        // 자기 전송이 아니면 여기서 끝난다 — 거의 모든 요청이 그렇고, 원장을 읽지 않는다.
        if (!SubmissionRequestPolicy.isSelfTransfer(command.senderAccountId, command.recipient.type, command.recipient.value)) return
        // **행이 있으면 여기서 답하지 않는다** — 같은 키·다른 내용의 `409`가 이 `400`에 가려지면 안 된다(02 멱등 표).
        // 기존 행의 처리는 내용 대조 뒤 상태별로 갈린다([rejectSelfTransferRetry]).
        if (submissions.findByExternalTransactionId(command.externalTransactionId) != null) return
        throw InvalidRequestException("recipient")
    }

    /**
     * 기존 `FAILED` 행의 자기 계정 재시도를 막는다. **내용 대조(`409`) 뒤에** 부른다 —
     * 회수는 이미 벌어진 일의 불확실성 해소라 열어 두고, 재시도는 **새 자금 이동**이라 막는다(02).
     */
    private fun rejectSelfTransferRetry(command: TransactionSubmissionCommand) {
        SubmissionRequestPolicy.requireDistinctAccounts(command.senderAccountId, command.recipient.type, command.recipient.value)
    }

    fun submitManaged(command: ManagedTransactionSubmissionCommand): TransactionSubmissionResult {
        val logicalCommand =
            TransactionSubmissionCommand(
                externalTransactionId = command.externalTransactionId,
                senderAccountId = command.sourceVaultId,
                recipient = command.logicalRecipient(),
                network = command.network,
                symbol = command.symbol,
                amount = command.amount,
                note = command.note,
                travelRuleMessage = null,
            )
        val logical =
            LogicalSubmission(
                recipientType = command.recipientType,
                recipientValue = command.recipientValue,
                transactionType = SubmissionTransactionType.BAND_S,
            )
        // 자산 매핑도 제출 시점에 읽는다 — 밴드S 키의 멱등 응답이 현재 매핑 오류에 가려지면 안 된다.
        return submit(logicalCommand, logical) {
            PreparedSubmission(
                sourceVaultId = command.sourceVaultId,
                vendorAssetId = mappings.requiredMapping(command.network, command.symbol).vendorAssetId,
                vendorDestination = command.vendorDestination,
                useGasless = command.useGasless,
            )
        }
    }

    private fun enforceExecutionGate(
        command: TransactionSubmissionCommand,
        logical: LogicalSubmission,
    ) {
        if (logical.transactionType != SubmissionTransactionType.WITHDRAWAL) return
        val currentGate = executionGates.findCurrent(command.network, ExecutionGateType.WITHDRAWAL) ?: return
        val existing = submissions.findByExternalTransactionId(command.externalTransactionId)
        if (existing?.status == SubmissionStatus.REQUESTED || existing?.status == SubmissionStatus.SUBMITTED) return
        ExecutionGatePolicy.requireOpen(currentGate)
    }

    private fun submit(
        command: TransactionSubmissionCommand,
        logical: LogicalSubmission,
        prepare: () -> PreparedSubmission,
    ): TransactionSubmissionResult {
        val fingerprint = fingerprint(command, logical)
        val claim = newClaim()
        val requested = requestedRecord(command, logical, fingerprint, claim)
        val attempt = initialAttempt(command, logical, requested, prepare)

        val current = attempt.record
        ensureSameRequest(current, command, logical, fingerprint)
        if (attempt.isNew) {
            return submitToVendor(command, checkNotNull(attempt.prepared) { "new submission prepared nothing" }, claim.id)
        }
        return when (current.status) {
            SubmissionStatus.SUBMITTED -> {
                TransactionSubmissionResult(
                    checkNotNull(current.vendorTransactionId) { "SUBMITTED submission has no vendor transaction id" },
                )
            }

            SubmissionStatus.REQUESTED -> {
                val acquired = acquireClaim(command, logical, claim, requireOpenForFailed = false)
                if (acquired.status == SubmissionStatus.SUBMITTED) {
                    TransactionSubmissionResult(
                        checkNotNull(acquired.vendorTransactionId) {
                            "SUBMITTED submission has no vendor transaction id"
                        },
                    )
                } else {
                    recoverOrSubmit(command, prepare, claim.id)
                }
            }

            SubmissionStatus.FAILED -> {
                rejectSelfTransferRetry(command)
                val acquired = acquireClaim(command, logical, claim, requireOpenForFailed = true)
                if (acquired.status == SubmissionStatus.SUBMITTED) {
                    TransactionSubmissionResult(
                        checkNotNull(acquired.vendorTransactionId) {
                            "SUBMITTED submission has no vendor transaction id"
                        },
                    )
                } else {
                    submitToVendor(command, prepare(), claim.id)
                }
            }
        }
    }

    private fun acquireClaim(
        command: TransactionSubmissionCommand,
        logical: LogicalSubmission,
        claim: SubmissionClaim,
        requireOpenForFailed: Boolean,
    ): SubmissionRecord {
        val acquisition =
            transactionRunner.run {
                if (requireOpenForFailed && logical.transactionType == SubmissionTransactionType.WITHDRAWAL) {
                    val current = submissions.findByExternalTransactionId(command.externalTransactionId)
                    if (current?.status == SubmissionStatus.FAILED) {
                        ExecutionGatePolicy.requireOpen(
                            executionGates.lockAndFindCurrent(command.network, ExecutionGateType.WITHDRAWAL),
                        )
                    }
                }
                ClaimAcquisition(
                    submissions.tryClaim(
                        command.externalTransactionId,
                        claim.id,
                        claim.expiresAt,
                        CoreDateTimes.now(clock),
                    ),
                )
            }
        val acquired = acquisition.record
        if (acquired != null) return acquired

        val current =
            submissions.findByExternalTransactionId(command.externalTransactionId)
                ?: throw ConflictException("submission", command.externalTransactionId)
        if (current.status == SubmissionStatus.SUBMITTED) {
            return current
        }
        throw SubmissionInProgressException(command.externalTransactionId, retryAfterSeconds(current))
    }

    /**
     * 그 키의 첫 행을 만든다. **벤더 자원은 행을 넣기 직전, 같은 트랜잭션 안에서 읽는다**(02 "신규 키 선행 검사") —
     * 읽다 실패하면 행이 생기지 않아, 제출할 수 없는 `REQUESTED`와 살아 있는 소유권이 남지 않는다.
     * 기존 행을 찾은 갈래에서는 읽지 않는다 — 멱등 응답과 회수가 현재 자원 오류에 가려지면 안 된다.
     */
    private fun initialAttempt(
        command: TransactionSubmissionCommand,
        logical: LogicalSubmission,
        requested: SubmissionRecord,
        prepare: () -> PreparedSubmission,
    ): SubmissionAttempt =
        try {
            transactionRunner.run {
                val withdrawal = logical.transactionType == SubmissionTransactionType.WITHDRAWAL
                val gate = if (withdrawal) executionGates.lockAndFindCurrent(command.network, ExecutionGateType.WITHDRAWAL) else null
                // **거래 구분과 무관하게** 기존 행을 먼저 본다 — 내부이체·밴드S도 기존 키면 자원을 읽지 않고 되돌아가야 한다.
                val existing = submissions.findByExternalTransactionId(command.externalTransactionId)
                if (existing != null) {
                    if (withdrawal && existing.status == SubmissionStatus.FAILED) ExecutionGatePolicy.requireOpen(gate)
                    return@run SubmissionAttempt(existing, isNew = false, prepared = null)
                }
                if (withdrawal) ExecutionGatePolicy.requireOpen(gate)
                val prepared = prepare()
                SubmissionAttempt(submissions.insert(requested), isNew = true, prepared = prepared)
            }
        } catch (conflict: ConflictException) {
            SubmissionAttempt(
                submissions.findByExternalTransactionId(command.externalTransactionId) ?: throw conflict,
                isNew = false,
                prepared = null,
            )
        }

    /**
     * 소유권을 뺏은 뒤의 회수. **벤더 조회가 먼저다**(02 "만료 뒤 뺏은 소유자는 제출하기 전에 벤더 조회부터 한다") —
     * 앞 소유자가 죽기 직전에 제출을 마쳤을 수 있고, 조회를 건너뛰면 그게 곧 이중 출금이다.
     * 벤더 자원은 조회 **뒤에** 읽는다. 순서를 뒤집으면 현재 매핑이 깨진 것만으로 조회 자체를 못 한다.
     */
    private fun recoverOrSubmit(
        command: TransactionSubmissionCommand,
        prepare: () -> PreparedSubmission,
        claimId: String,
    ): TransactionSubmissionResult {
        val recovered =
            vendor.transactionByExternalTransactionId(command.externalTransactionId)
        return if (recovered == null) {
            submitToVendor(command, prepare(), claimId)
        } else {
            completeRecovered(command, prepare(), claimId, recovered)
        }
    }

    private fun submitToVendor(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
        claimId: String,
    ): TransactionSubmissionResult =
        try {
            when (
                val result =
                    vendor.submitTransaction(
                        VendorTransactionRequest(
                            externalTransactionId = command.externalTransactionId,
                            vendorAssetId = prepared.vendorAssetId,
                            sourceVaultId = prepared.sourceVaultId,
                            destination = prepared.vendorDestination,
                            amount = command.amount,
                            note = command.note,
                            travelRuleMessage = command.travelRuleMessage,
                            useGasless = prepared.useGasless,
                        ),
                    )
            ) {
                is VendorTransactionSubmission.Accepted -> {
                    complete(command.externalTransactionId, claimId, result.transactionId)
                }

                is VendorTransactionSubmission.BadRequestNeedsLookup -> {
                    // 400 은 중복인지 검증 실패인지 응답만으로 가르지 않는다 (02 벤더 응답별 처리).
                    // 조회 자체가 실패하면 VendorApiException 이 그대로 올라가 REQUESTED 가 유지된다.
                    val recovered = vendor.transactionByExternalTransactionId(command.externalTransactionId)
                    if (recovered == null) {
                        // 조회는 성공했는데 거래가 없다 — 그때야 확정 거절이다.
                        throw RelayRejectedException("transaction rejected", result.rejection)
                    }
                    completeRecovered(command, prepared, claimId, recovered)
                }
            }
        } catch (rejected: RelayRejectedException) {
            try {
                transactionRunner.run {
                    submissions.markFailedByClaim(
                        command.externalTransactionId,
                        claimId,
                        CoreDateTimes.now(clock),
                    )
                }
            } catch (conflict: ConflictException) {
                rejected.addSuppressed(conflict)
            }
            throw rejected
        }

    private fun completeRecovered(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
        claimId: String,
        recovered: VendorTransaction,
    ): TransactionSubmissionResult {
        if (!recovered.matches(command, prepared)) {
            val conflict = ConflictException("submission", command.externalTransactionId)
            val current =
                submissions.findByExternalTransactionId(command.externalTransactionId)
                    ?: throw conflict
            conflictAlerts.alert(
                SubmissionConflictAlert(
                    externalTransactionId = command.externalTransactionId,
                    observedVendorTransactionId = recovered.transactionId,
                    recordedVendorTransactionId = current.vendorTransactionId,
                    recordedStatus = current.status,
                ),
            )
            throw conflict
        }
        return complete(command.externalTransactionId, claimId, recovered.transactionId)
    }

    private fun VendorTransaction.matches(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
    ): Boolean {
        val sameAmount =
            try {
                BigDecimal(amount).compareTo(BigDecimal(command.amount)) == 0
            } catch (exception: NumberFormatException) {
                throw VendorApiException("validateRecoveredTransaction", null, exception)
            }
        val sameDestination =
            when (val destination = prepared.vendorDestination) {
                is VendorTransactionDestination.Address ->
                    this.destination.type == "ONE_TIME_ADDRESS" && destinationAddress == destination.address

                is VendorTransactionDestination.Account ->
                    this.destination.type == "VAULT_ACCOUNT" && this.destination.id == destination.vaultId

                is VendorTransactionDestination.Whitelisted ->
                    this.destination.type == "EXTERNAL_WALLET" && this.destination.id == destination.walletId
            }
        return externalTransactionId == command.externalTransactionId &&
            vendorAssetId == prepared.vendorAssetId &&
            source.type == "VAULT_ACCOUNT" &&
            source.id == prepared.sourceVaultId &&
            sameDestination &&
            sameAmount
    }

    private fun complete(
        externalTransactionId: String,
        claimId: String,
        vendorTransactionId: String,
    ): TransactionSubmissionResult {
        try {
            transactionRunner.run {
                submissions.markSubmittedByClaim(
                    externalTransactionId,
                    claimId,
                    vendorTransactionId,
                    CoreDateTimes.now(clock),
                )
            }
        } catch (conflict: ConflictException) {
            if (conflict.resource != "submission") throw conflict
            val current = submissions.findByExternalTransactionId(externalTransactionId) ?: throw conflict
            if (current.status == SubmissionStatus.SUBMITTED && current.vendorTransactionId == vendorTransactionId) {
                logger.warn(
                    "제출 소유권을 잃은 뒤 같은 벤더 접수 결과로 수렴 externalTransactionId={} vendorTransactionId={}",
                    externalTransactionId,
                    vendorTransactionId,
                )
                return TransactionSubmissionResult(vendorTransactionId)
            }

            conflictAlerts.alert(
                SubmissionConflictAlert(
                    externalTransactionId = externalTransactionId,
                    observedVendorTransactionId = vendorTransactionId,
                    recordedVendorTransactionId = current.vendorTransactionId,
                    recordedStatus = current.status,
                ),
            )
            if (current.status == SubmissionStatus.SUBMITTED) throw conflict

            // 벤더는 접수했지만 현재 claim의 결과로 원장에 남기지 못했다. 202로 성공을 숨기지 않고
            // REQUESTED 재시도의 externalTxId 회수 경로로 수렴시킨다.
            throw VendorApiException("recordSubmittedTransaction", null, conflict)
        }
        return TransactionSubmissionResult(vendorTransactionId)
    }

    private fun prepare(command: TransactionSubmissionCommand): PreparedSubmission {
        val source = accounts.requiredAccount(command.senderAccountId)
        val mapping = mappings.requiredMapping(command.network, command.symbol)
        val recipient = command.recipient
        val destination =
            when (recipient) {
                is TransactionSubmissionRecipient.Address -> {
                    VendorTransactionDestination.Address(recipient.address)
                }

                is TransactionSubmissionRecipient.Account -> {
                    val account = accounts.requiredAccount(recipient.accountId)
                    VendorTransactionDestination.Account(account.requireVendorVaultId())
                }

                is TransactionSubmissionRecipient.Whitelisted -> {
                    VendorTransactionDestination.Whitelisted(recipient.walletId)
                }
            }
        return PreparedSubmission(
            sourceVaultId = source.requireVendorVaultId(),
            vendorAssetId = mapping.vendorAssetId,
            vendorDestination = destination,
            useGasless = recipient.type.transactionType() != SubmissionTransactionType.INTERNAL,
        )
    }

    private fun fingerprint(
        command: TransactionSubmissionCommand,
        logical: LogicalSubmission,
    ): SubmissionRequestFingerprint =
        SubmissionRequestHashes.v1(
            senderType = "ACCOUNT",
            senderAccountId = command.senderAccountId,
            recipientType = logical.recipientType.name,
            recipientValue = logical.recipientValue,
            network = command.network,
            symbol = command.symbol,
            amount = command.amount,
        )

    private fun requestedRecord(
        command: TransactionSubmissionCommand,
        logical: LogicalSubmission,
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
            transactionType = logical.transactionType,
            vendorTransactionId = null,
            senderAccountId = command.senderAccountId,
            recipientType = logical.recipientType,
            recipientValue = logical.recipientValue,
            network = command.network,
            symbol = command.symbol,
            amount = fingerprint.normalizedAmount,
            requestedAt = CoreDateTimes.now(clock),
            respondedAt = null,
        )

    private fun newClaim(): SubmissionClaim =
        SubmissionClaim(
            id = UUID.randomUUID().toString(),
            expiresAt =
                CoreDateTimes.format(
                    CoreDateTimes.current(clock).plusSeconds(properties.claimTtlSeconds),
                ),
        )

    private fun retryAfterSeconds(record: SubmissionRecord): Long {
        val expiresAt = record.claimExpiresAt?.let(CoreDateTimes::parse) ?: return 1
        return Duration.between(CoreDateTimes.current(clock), expiresAt).seconds.coerceAtLeast(1)
    }

    private data class SubmissionClaim(
        val id: String,
        val expiresAt: String,
    )

    private data class ClaimAcquisition(
        val record: SubmissionRecord?,
    )

    private fun ensureSameRequest(
        existing: SubmissionRecord,
        command: TransactionSubmissionCommand,
        logical: LogicalSubmission,
        fingerprint: SubmissionRequestFingerprint,
    ) {
        val same =
            existing.transactionType == logical.transactionType &&
                if (existing.hashVersion == fingerprint.hashVersion) {
                    existing.requestHash == fingerprint.requestHash
                } else {
                    existing.senderAccountId == command.senderAccountId &&
                        existing.recipientType == logical.recipientType &&
                        existing.recipientValue == logical.recipientValue &&
                        existing.network == command.network &&
                        existing.symbol == command.symbol &&
                        BigDecimal(existing.amount).compareTo(BigDecimal(command.amount)) == 0
                }
        if (!same) {
            throw ConflictException("submission", command.externalTransactionId)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(TransactionSubmissionService::class.java)
    }
}

data class TransactionSubmissionCommand(
    val externalTransactionId: String,
    val senderAccountId: String,
    val recipient: TransactionSubmissionRecipient,
    val network: String,
    val symbol: String,
    val amount: String,
    val note: String?,
    val travelRuleMessage: Map<String, Any?>?,
)

data class ManagedTransactionSubmissionCommand(
    val externalTransactionId: String,
    val sourceVaultId: String,
    val recipientType: SubmissionRecipientType,
    val recipientValue: String,
    val vendorDestination: VendorTransactionDestination,
    val network: String,
    val symbol: String,
    val amount: String,
    val useGasless: Boolean,
    val note: String?,
) {
    init {
        require(
            when (recipientType) {
                SubmissionRecipientType.ADDRESS -> vendorDestination is VendorTransactionDestination.Address
                SubmissionRecipientType.ACCOUNT -> vendorDestination is VendorTransactionDestination.Account
                SubmissionRecipientType.WHITELISTED -> vendorDestination is VendorTransactionDestination.Whitelisted
            },
        ) { "managed submission recipient and vendor destination must match" }
    }

    fun logicalRecipient(): TransactionSubmissionRecipient =
        when (recipientType) {
            SubmissionRecipientType.ADDRESS -> TransactionSubmissionRecipient.Address(recipientValue)
            SubmissionRecipientType.ACCOUNT -> TransactionSubmissionRecipient.Account(recipientValue)
            SubmissionRecipientType.WHITELISTED -> TransactionSubmissionRecipient.Whitelisted(recipientValue)
        }
}

sealed interface TransactionSubmissionRecipient {
    val type: SubmissionRecipientType
    val value: String

    data class Address(
        val address: String,
    ) : TransactionSubmissionRecipient {
        override val type = SubmissionRecipientType.ADDRESS
        override val value = address
    }

    data class Account(
        val accountId: String,
    ) : TransactionSubmissionRecipient {
        override val type = SubmissionRecipientType.ACCOUNT
        override val value = accountId
    }

    data class Whitelisted(
        val walletId: String,
    ) : TransactionSubmissionRecipient {
        override val type = SubmissionRecipientType.WHITELISTED
        override val value = walletId
    }
}

data class TransactionSubmissionResult(
    val transactionId: String,
)

/**
 * 벤더 조회 없이 **요청만으로** 정해지는 값. 멱등 판정(`req_hash`·내용 대조)·업무 계열·게이트가 이것만 쓴다.
 * 벤더 자원(vault·자산·목적지)은 실제로 제출할 때만 필요하므로 [PreparedSubmission]으로 따로 만든다 —
 * 그래야 **기존 키 판정이 현재 자원 상태보다 앞설 수 있다**(02 "신규 키 선행 검사").
 */
private data class LogicalSubmission(
    val recipientType: SubmissionRecipientType,
    val recipientValue: String,
    val transactionType: SubmissionTransactionType,
)

/** 벤더 본문에만 쓰이는 값. 판정에 쓰이는 논리값은 [LogicalSubmission]이 갖는다 — 둘을 섞으면 분리한 이유가 흐려진다. */
private data class PreparedSubmission(
    val sourceVaultId: String,
    val vendorAssetId: String,
    val vendorDestination: VendorTransactionDestination,
    val useGasless: Boolean,
)

private data class SubmissionAttempt(
    val record: SubmissionRecord,
    val isNew: Boolean,
    /** 새 행을 만든 갈래에서만 채운다 — 그 갈래만 벤더 자원을 읽는다. */
    val prepared: PreparedSubmission?,
)
