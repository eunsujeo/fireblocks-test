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
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
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
}

@Service
class SweepContractCallSubmissionService(
    private val submissions: SubmissionRecordRepository,
    private val vendor: VendorContractCallPort,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val properties: SweepProperties,
) : SweepContractCallSubmitter {
    override fun submit(command: SweepContractCallCommand): SweepContractCallResult {
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
        val requested = requestedRecord(command, fingerprint.requestHash, fingerprint.hashVersion, fingerprint.normalizedAmount, claim)
        val attempt =
            try {
                SubmissionAttempt(transactionRunner.run { submissions.insert(requested) }, isNew = true)
            } catch (conflict: ConflictException) {
                SubmissionAttempt(
                    submissions.findByExternalTransactionId(command.externalTransactionId) ?: throw conflict,
                    isNew = false,
                )
            }
        val current = attempt.record
        ensureSameRequest(current, command, fingerprint.requestHash)
        if (attempt.isNew) return submitToVendor(command, claim.id)
        return when (current.status) {
            SubmissionStatus.SUBMITTED -> submitted(current)
            SubmissionStatus.REQUESTED -> {
                val acquired = acquireClaim(command.externalTransactionId, claim)
                if (acquired.status == SubmissionStatus.SUBMITTED) submitted(acquired) else recoverOrSubmit(command, claim.id)
            }

            SubmissionStatus.FAILED -> {
                val acquired = acquireClaim(command.externalTransactionId, claim)
                if (acquired.status == SubmissionStatus.SUBMITTED) submitted(acquired) else submitToVendor(command, claim.id)
            }
        }
    }

    private fun acquireClaim(
        externalTransactionId: String,
        claim: SubmissionClaim,
    ): SubmissionRecord {
        val now = CoreDateTimes.now(clock)
        val acquired =
            transactionRunner.run {
                submissions.tryClaim(externalTransactionId, claim.id, claim.expiresAt, now)
            }
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
        requestHash: String,
    ) {
        if (current.requestHash != requestHash ||
            current.transactionType != command.transactionType ||
            current.senderAccountId != command.senderAccountId ||
            current.recipientType != SubmissionRecipientType.ADDRESS ||
            !current.recipientValue.equals(command.contractAddress, ignoreCase = true) ||
            current.network != command.network ||
            current.symbol != command.symbol ||
            current.sweepExecutionId != command.sweepExecutionId
        ) {
            throw ConflictException("submission", command.externalTransactionId)
        }
    }

    private fun requestedRecord(
        command: SweepContractCallCommand,
        requestHash: String,
        hashVersion: String,
        normalizedAmount: String,
        claim: SubmissionClaim,
    ) = SubmissionRecord(
        externalTransactionId = command.externalTransactionId,
        requestHash = requestHash,
        hashVersion = hashVersion,
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
        amount = normalizedAmount,
        requestedAt = CoreDateTimes.now(clock),
        respondedAt = null,
        sweepExecutionId = command.sweepExecutionId,
    )

    private fun newClaim(): SubmissionClaim {
        val now = LocalDateTime.now(clock)
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
}
