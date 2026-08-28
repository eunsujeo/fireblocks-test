package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.vendor.VendorContractCall
import com.whatto.bcm.domain.vendor.VendorContractCallPort
import com.whatto.bcm.domain.vendor.VendorContractCallRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.support.submission.SubmissionRequestFingerprint
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.UUID

data class SweepContractCallCommand(
    val externalTransactionId: String,
    val transactionType: SubmissionTransactionType,
    val senderAccountId: String,
    val sourceVaultId: String,
    val network: String,
    val symbol: String,
    val contractAddress: String,
    val semanticAmount: String,
    val callData: String,
    val sweepExecutionId: String? = null,
) {
    init {
        require(
            transactionType == SubmissionTransactionType.SWEEP_APPROVE ||
                transactionType == SubmissionTransactionType.SWEEP_BATCH,
        ) { "contract call submission must be a sweep transaction" }
    }
}

data class SweepContractCallResult(
    val vendorTransactionId: String,
)

fun interface SweepContractCallSubmitter {
    fun submit(command: SweepContractCallCommand): SweepContractCallResult

    fun submit(
        command: SweepContractCallCommand,
        recordIntent: () -> Unit,
    ): SweepContractCallResult {
        recordIntent()
        return submit(command)
    }

    fun submit(
        command: SweepContractCallCommand,
        recordIntent: () -> Unit,
        recordFailedRetry: () -> Unit,
    ): SweepContractCallResult {
        recordIntent()
        return submit(command)
    }
}

@Service
class SweepContractCallSubmissionService(
    private val submissions: SubmissionRecordRepository,
    private val vendor: VendorContractCallPort,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val properties: SweepProperties,
) : SweepContractCallSubmitter {
    override fun submit(command: SweepContractCallCommand): SweepContractCallResult = submitInternal(command, null, null)

    override fun submit(
        command: SweepContractCallCommand,
        recordIntent: () -> Unit,
    ): SweepContractCallResult = submitInternal(command, recordIntent, null)

    override fun submit(
        command: SweepContractCallCommand,
        recordIntent: () -> Unit,
        recordFailedRetry: () -> Unit,
    ): SweepContractCallResult = submitInternal(command, recordIntent, recordFailedRetry)

    private fun submitInternal(
        command: SweepContractCallCommand,
        recordIntent: (() -> Unit)?,
        recordFailedRetry: (() -> Unit)?,
    ): SweepContractCallResult {
        val fingerprint =
            SubmissionRequestHashes.contractCallV1(
                command.senderAccountId,
                command.contractAddress,
                command.network,
                command.symbol,
                command.semanticAmount,
                command.callData,
            )
        val claim = newClaim()
        val requested = requestedRecord(command, fingerprint, claim)
        val attempt =
            try {
                SubmissionAttempt(
                    transactionRunner.run {
                        submissions.insert(requested).also { recordIntent?.invoke() }
                    },
                    isNew = true,
                )
            } catch (conflict: ConflictException) {
                SubmissionAttempt(
                    submissions.findByExternalTransactionId(command.externalTransactionId) ?: throw conflict,
                    isNew = false,
                )
            }
        val current = attempt.record
        ensureSameRequest(current, command, fingerprint)
        if (attempt.isNew) return submitToVendor(command, claim.id)
        return when (current.status) {
            SubmissionStatus.SUBMITTED -> submitted(current)
            SubmissionStatus.REQUESTED -> {
                val acquired = acquireClaim(command.externalTransactionId, claim, null)
                if (acquired.status == SubmissionStatus.SUBMITTED) submitted(acquired) else recoverOrSubmit(command, claim.id)
            }

            SubmissionStatus.FAILED -> {
                val acquired = acquireClaim(command.externalTransactionId, claim, recordFailedRetry)
                if (acquired.status == SubmissionStatus.SUBMITTED) submitted(acquired) else submitToVendor(command, claim.id)
            }
        }
    }

    private fun acquireClaim(
        externalTransactionId: String,
        claim: SubmissionClaim,
        recordFailedRetry: (() -> Unit)?,
    ): SubmissionRecord {
        val now = CoreDateTimes.now(clock)
        val acquisition =
            transactionRunner.run {
                val record = submissions.tryClaim(externalTransactionId, claim.id, claim.expiresAt, now)
                if (record?.status == SubmissionStatus.REQUESTED) recordFailedRetry?.invoke()
                ClaimAcquisition(record)
            }
        val acquired = acquisition.record
        if (acquired != null) return acquired
        val current =
            submissions.findByExternalTransactionId(externalTransactionId)
                ?: throw ConflictException("submission", externalTransactionId)
        if (current.status == SubmissionStatus.SUBMITTED) return current
        throw SubmissionInProgressException(externalTransactionId, retryAfterSeconds(current, now))
    }

    private fun recoverOrSubmit(
        command: SweepContractCallCommand,
        claimId: String,
    ): SweepContractCallResult {
        val recovered = vendor.contractCallByExternalTransactionId(command.externalTransactionId)
        return if (recovered == null) submitToVendor(command, claimId) else completeRecovered(command, claimId, recovered)
    }

    private fun submitToVendor(
        command: SweepContractCallCommand,
        claimId: String,
    ): SweepContractCallResult =
        try {
            when (
                val result =
                    vendor.submitContractCall(
                        VendorContractCallRequest(
                            command.externalTransactionId,
                            command.network,
                            command.sourceVaultId,
                            command.contractAddress,
                            command.callData,
                            useGasless = true,
                        ),
                    )
            ) {
                is VendorTransactionSubmission.Accepted -> complete(command.externalTransactionId, claimId, result.transactionId)
                is VendorTransactionSubmission.BadRequestNeedsLookup -> {
                    val recovered = vendor.contractCallByExternalTransactionId(command.externalTransactionId)
                    if (recovered == null) throw RelayRejectedException("contract call rejected", result.rejection)
                    completeRecovered(command, claimId, recovered)
                }
            }
        } catch (rejected: RelayRejectedException) {
            try {
                transactionRunner.run {
                    submissions.markFailedByClaim(command.externalTransactionId, claimId, CoreDateTimes.now(clock))
                }
            } catch (conflict: ConflictException) {
                rejected.addSuppressed(conflict)
            }
            throw rejected
        }

    private fun completeRecovered(
        command: SweepContractCallCommand,
        claimId: String,
        recovered: VendorContractCall,
    ): SweepContractCallResult {
        val matches =
            recovered.externalTransactionId == command.externalTransactionId &&
                recovered.sourceVaultId == command.sourceVaultId &&
                recovered.contractAddress.equals(command.contractAddress, ignoreCase = true) &&
                recovered.callData.equals(command.callData, ignoreCase = true)
        if (!matches) throw ConflictException("submission", command.externalTransactionId)
        return complete(command.externalTransactionId, claimId, recovered.transactionId)
    }

    private fun complete(
        externalTransactionId: String,
        claimId: String,
        vendorTransactionId: String,
    ): SweepContractCallResult {
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
            val current = submissions.findByExternalTransactionId(externalTransactionId) ?: throw conflict
            if (current.status == SubmissionStatus.SUBMITTED && current.vendorTransactionId == vendorTransactionId) {
                return SweepContractCallResult(vendorTransactionId)
            }
            if (current.status == SubmissionStatus.SUBMITTED) throw conflict
            throw VendorApiException("recordSubmittedContractCall", null, conflict)
        }
        return SweepContractCallResult(vendorTransactionId)
    }

    private fun submitted(record: SubmissionRecord): SweepContractCallResult =
        SweepContractCallResult(
            checkNotNull(record.vendorTransactionId) { "SUBMITTED sweep submission has no vendor transaction id" },
        )

    private fun ensureSameRequest(
        current: SubmissionRecord,
        command: SweepContractCallCommand,
        fingerprint: SubmissionRequestFingerprint,
    ) {
        if (current.requestHash != fingerprint.requestHash ||
            current.hashVersion != fingerprint.hashVersion ||
            current.transactionType != command.transactionType ||
            current.senderAccountId != command.senderAccountId ||
            current.recipientType != SubmissionRecipientType.ADDRESS ||
            !current.recipientValue.equals(command.contractAddress, ignoreCase = true) ||
            current.network != command.network ||
            current.symbol != command.symbol ||
            current.callData != fingerprint.normalizedCallData ||
            current.sweepExecutionId != command.sweepExecutionId
        ) {
            throw ConflictException("submission", command.externalTransactionId)
        }
    }

    private fun requestedRecord(
        command: SweepContractCallCommand,
        fingerprint: SubmissionRequestFingerprint,
        claim: SubmissionClaim,
    ) = SubmissionRecord(
        externalTransactionId = command.externalTransactionId,
        requestHash = fingerprint.requestHash,
        hashVersion = fingerprint.hashVersion,
        status = SubmissionStatus.REQUESTED,
        claimId = claim.id,
        claimExpiresAt = claim.expiresAt,
        transactionType = command.transactionType,
        vendorTransactionId = null,
        senderAccountId = command.senderAccountId,
        recipientType = SubmissionRecipientType.ADDRESS,
        recipientValue = command.contractAddress,
        network = command.network,
        symbol = command.symbol,
        amount = fingerprint.normalizedAmount,
        requestedAt = CoreDateTimes.now(clock),
        respondedAt = null,
        sweepExecutionId = command.sweepExecutionId,
        callData = fingerprint.normalizedCallData,
    )

    private fun newClaim(): SubmissionClaim {
        val now = CoreDateTimes.current(clock)
        return SubmissionClaim(
            UUID.randomUUID().toString(),
            CoreDateTimes.format(now.plusSeconds(properties.claimTtlSeconds)),
        )
    }

    private fun retryAfterSeconds(
        current: SubmissionRecord,
        now: String,
    ): Long {
        val expiresAt = current.claimExpiresAt ?: return 1
        return Duration.between(CoreDateTimes.parse(now), CoreDateTimes.parse(expiresAt)).seconds.coerceAtLeast(1)
    }

    private data class SubmissionClaim(
        val id: String,
        val expiresAt: String,
    )

    private data class SubmissionAttempt(
        val record: SubmissionRecord,
        val isNew: Boolean,
    )

    private data class ClaimAcquisition(
        val record: SubmissionRecord?,
    )
}
