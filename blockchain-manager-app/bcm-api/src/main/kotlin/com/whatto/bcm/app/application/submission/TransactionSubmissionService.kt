package com.whatto.bcm.app.application.submission

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.ExecutionGatePolicy
import com.whatto.bcm.domain.admin.ExecutionGateRepository
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.submission.SubmissionConflictAlert
import com.whatto.bcm.domain.submission.SubmissionConflictAlertPort
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
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

@Service
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
) {
    fun submit(command: TransactionSubmissionCommand): TransactionSubmissionResult {
        val prepared = prepare(command)
        enforceExecutionGate(command, prepared)
        return submit(command, prepared)
    }

    fun submitManaged(command: ManagedTransactionSubmissionCommand): TransactionSubmissionResult {
        val mapping = mappings.requiredMapping(command.network, command.symbol)
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
        return submit(
            logicalCommand,
            PreparedSubmission(
                sourceVaultId = command.sourceVaultId,
                vendorAssetId = mapping.vendorAssetId,
                recipientType = command.recipientType,
                recipientValue = command.recipientValue,
                vendorDestination = command.vendorDestination,
                transactionType = SubmissionTransactionType.BAND_S,
                useGasless = command.useGasless,
            ),
        )
    }

    private fun enforceExecutionGate(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
    ) {
        if (prepared.transactionType != SubmissionTransactionType.WITHDRAWAL) return
        val currentGate = executionGates.findCurrent(command.network, ExecutionGateType.WITHDRAWAL) ?: return
        val existing = submissions.findByExternalTransactionId(command.externalTransactionId)
        if (existing?.status == SubmissionStatus.REQUESTED || existing?.status == SubmissionStatus.SUBMITTED) return
        ExecutionGatePolicy.requireOpen(currentGate)
    }

    private fun submit(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
    ): TransactionSubmissionResult {
        val fingerprint = fingerprint(command, prepared)
        val claim = newClaim()
        val requested = requestedRecord(command, prepared, fingerprint, claim)
        val attempt = initialAttempt(command, prepared, requested)

        val current = attempt.record
        ensureSameRequest(current, command, prepared, fingerprint)
        if (attempt.isNew) {
            return submitToVendor(command, prepared, claim.id)
        }
        return when (current.status) {
            SubmissionStatus.SUBMITTED -> {
                TransactionSubmissionResult(
                    checkNotNull(current.vendorTransactionId) { "SUBMITTED submission has no vendor transaction id" },
                )
            }

            SubmissionStatus.REQUESTED -> {
                val acquired = acquireClaim(command, prepared, claim, requireOpenForFailed = false)
                if (acquired.status == SubmissionStatus.SUBMITTED) {
                    TransactionSubmissionResult(
                        checkNotNull(acquired.vendorTransactionId) {
                            "SUBMITTED submission has no vendor transaction id"
                        },
                    )
                } else {
                    recoverOrSubmit(command, prepared, claim.id)
                }
            }

            SubmissionStatus.FAILED -> {
                val acquired = acquireClaim(command, prepared, claim, requireOpenForFailed = true)
                if (acquired.status == SubmissionStatus.SUBMITTED) {
                    TransactionSubmissionResult(
                        checkNotNull(acquired.vendorTransactionId) {
                            "SUBMITTED submission has no vendor transaction id"
                        },
                    )
                } else {
                    submitToVendor(command, prepared, claim.id)
                }
            }
        }
    }

    private fun acquireClaim(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
        claim: SubmissionClaim,
        requireOpenForFailed: Boolean,
    ): SubmissionRecord {
        val acquisition =
            transactionRunner.run {
                if (requireOpenForFailed && prepared.transactionType == SubmissionTransactionType.WITHDRAWAL) {
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

    private fun initialAttempt(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
        requested: SubmissionRecord,
    ): SubmissionAttempt =
        try {
            transactionRunner.run {
                if (prepared.transactionType == SubmissionTransactionType.WITHDRAWAL) {
                    val gate = executionGates.lockAndFindCurrent(command.network, ExecutionGateType.WITHDRAWAL)
                    val existing = submissions.findByExternalTransactionId(command.externalTransactionId)
                    if (existing != null) {
                        if (existing.status == SubmissionStatus.FAILED) ExecutionGatePolicy.requireOpen(gate)
                        return@run SubmissionAttempt(existing, isNew = false)
                    }
                    ExecutionGatePolicy.requireOpen(gate)
                }
                SubmissionAttempt(submissions.insert(requested), isNew = true)
            }
        } catch (conflict: ConflictException) {
            SubmissionAttempt(
                submissions.findByExternalTransactionId(command.externalTransactionId) ?: throw conflict,
                isNew = false,
            )
        }

    private fun recoverOrSubmit(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
        claimId: String,
    ): TransactionSubmissionResult {
        val recovered =
            vendor.transactionByExternalTransactionId(command.externalTransactionId)
        return if (recovered == null) {
            submitToVendor(command, prepared, claimId)
        } else {
            completeRecovered(command, prepared, claimId, recovered)
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
            recipientType = recipient.type,
            recipientValue = recipient.value,
            vendorDestination = destination,
            transactionType = recipient.type.transactionType(),
            useGasless = recipient.type.transactionType() != SubmissionTransactionType.INTERNAL,
        )
    }

    private fun fingerprint(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
    ): SubmissionRequestFingerprint =
        SubmissionRequestHashes.v1(
            senderType = "ACCOUNT",
            senderAccountId = command.senderAccountId,
            recipientType = prepared.recipientType.name,
            recipientValue = prepared.recipientValue,
            network = command.network,
            symbol = command.symbol,
            amount = command.amount,
        )

    private fun requestedRecord(
        command: TransactionSubmissionCommand,
        prepared: PreparedSubmission,
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
            transactionType = prepared.transactionType,
            vendorTransactionId = null,
            senderAccountId = command.senderAccountId,
            recipientType = prepared.recipientType,
            recipientValue = prepared.recipientValue,
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
        prepared: PreparedSubmission,
        fingerprint: SubmissionRequestFingerprint,
    ) {
        val same =
            existing.transactionType == prepared.transactionType &&
                if (existing.hashVersion == fingerprint.hashVersion) {
                    existing.requestHash == fingerprint.requestHash
                } else {
                    existing.senderAccountId == command.senderAccountId &&
                        existing.recipientType == prepared.recipientType &&
                        existing.recipientValue == prepared.recipientValue &&
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

private data class PreparedSubmission(
    val sourceVaultId: String,
    val vendorAssetId: String,
    val recipientType: SubmissionRecipientType,
    val recipientValue: String,
    val vendorDestination: VendorTransactionDestination,
    val transactionType: SubmissionTransactionType,
    val useGasless: Boolean,
)

private data class SubmissionAttempt(
    val record: SubmissionRecord,
    val isNew: Boolean,
)
