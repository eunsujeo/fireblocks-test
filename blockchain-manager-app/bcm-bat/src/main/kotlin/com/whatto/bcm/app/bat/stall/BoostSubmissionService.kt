package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.BoostAttempt
import com.whatto.bcm.domain.tx.BoostAttemptAcquisition
import com.whatto.bcm.domain.tx.BoostAttemptRepository
import com.whatto.bcm.domain.tx.BoostIntentRequest
import com.whatto.bcm.domain.tx.StallAlertReason
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.StallDecision
import com.whatto.bcm.domain.tx.StallDecisionPolicy
import com.whatto.bcm.domain.tx.StallLatestObservation
import com.whatto.bcm.domain.vendor.VendorFeeLevel
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock

sealed interface BoostSubmissionResult {
    data class Submitted(
        val newVendorTransactionId: String,
    ) : BoostSubmissionResult

    data class Alert(
        val reason: StallAlertReason,
    ) : BoostSubmissionResult

    data object InProgress : BoostSubmissionResult

    data object StaleCandidate : BoostSubmissionResult
}

fun interface BoostSubmitter {
    fun submit(
        candidate: StallCandidate,
        expectedTransactionHash: String,
    ): BoostSubmissionResult
}

@Service
class BoostSubmissionService(
    private val boosts: BoostAttemptRepository,
    private val submissions: SubmissionRecordRepository,
    private val accounts: AccountRepository,
    private val mappings: VendorAssetMappingRepository,
    private val vendorTransactions: VendorTransactionPort,
    private val transactionRunner: TransactionRunner,
    private val externalTransactionIds: BoostExternalTransactionIdGenerator,
    private val clock: Clock,
    private val properties: StallCheckProperties,
    private val claimIds: BoostClaimIdGenerator,
) : BoostSubmitter {
    override fun submit(
        candidate: StallCandidate,
        expectedTransactionHash: String,
    ): BoostSubmissionResult {
        val latest =
            vendorTransactions.transaction(candidate.record.activeVendorTxId)
                ?: return BoostSubmissionResult.Alert(StallAlertReason.VENDOR_TRANSACTION_NOT_FOUND)
        val decision = latest.decision(candidate)
        if (decision is StallDecision.Alert) return BoostSubmissionResult.Alert(decision.reason)
        val freshHash = (decision as StallDecision.BoostEligible).transactionHash
        if (freshHash != expectedTransactionHash) {
            return BoostSubmissionResult.Alert(StallAlertReason.TRANSACTION_HASH_MISMATCH)
        }
        val submission =
            submissions.findByVendorTransactionId(candidate.record.vendorTxId)
                ?: return BoostSubmissionResult.Alert(StallAlertReason.TRANSACTION_NOT_OWNED)
        if (submission.transactionType != SubmissionTransactionType.WITHDRAWAL) {
            return BoostSubmissionResult.Alert(StallAlertReason.TRANSACTION_TYPE_NOT_ELIGIBLE)
        }
        val prepared = prepare(submission)
        val now = CoreDateTimes.current(clock)
        val request =
            BoostIntentRequest(
                externalTransactionId = externalTransactionIds.nextId(),
                claimId = claimIds.nextId(),
                claimExpiresAt = CoreDateTimes.format(now.plusSeconds(properties.boostClaimTtlSeconds)),
                replacementVendorTransactionId = candidate.record.activeVendorTxId,
                replacementTransactionHash = freshHash,
                feeLevel = VendorFeeLevel.HIGH,
                useGasless = true,
                requestedAt = CoreDateTimes.format(now),
            )
        return when (
            val acquisition =
                transactionRunner.run {
                    boosts.acquire(
                        candidate.record,
                        request,
                        properties.maximumBoostAttempts,
                        CoreDateTimes.format(now),
                    )
                }
        ) {
            is BoostAttemptAcquisition.Acquired -> recoverOrSubmit(acquisition, prepared)
            BoostAttemptAcquisition.InProgress -> BoostSubmissionResult.InProgress
            BoostAttemptAcquisition.StaleCandidate -> BoostSubmissionResult.StaleCandidate
            BoostAttemptAcquisition.MaximumAttemptsReached ->
                BoostSubmissionResult.Alert(StallAlertReason.MAXIMUM_BOOST_ATTEMPTS_REACHED)
        }
    }

    private fun recoverOrSubmit(
        acquisition: BoostAttemptAcquisition.Acquired,
        prepared: PreparedBoost,
    ): BoostSubmissionResult {
        val attempt = acquisition.attempt
        if (!acquisition.newIntent) {
            val recovered = recover(attempt, prepared)
            if (recovered != null) return complete(attempt, recovered)
        }
        return submit(attempt, prepared)
    }

    private fun recover(
        attempt: BoostAttempt,
        prepared: PreparedBoost,
    ): String? {
        val recovered = vendorTransactions.transactionByExternalTransactionId(attempt.externalTransactionId) ?: return null
        if (!recovered.matches(attempt, prepared)) {
            throw ConflictException("boost", attempt.externalTransactionId)
        }
        return recovered.transactionId
    }

    private fun submit(
        attempt: BoostAttempt,
        prepared: PreparedBoost,
    ): BoostSubmissionResult {
        val result = vendorTransactions.submitTransaction(attempt.transferRequest(prepared))
        return when (result) {
            is VendorTransactionSubmission.Accepted -> complete(attempt, result.transactionId)
            is VendorTransactionSubmission.BadRequestNeedsLookup -> {
                val recovered = recover(attempt, prepared)
                if (recovered != null) {
                    complete(attempt, recovered)
                } else {
                    transactionRunner.run {
                        boosts.markFailedByClaim(
                            attempt.rootVendorTransactionId,
                            attempt.trySequence,
                            checkNotNull(attempt.claimId),
                            CoreDateTimes.now(clock),
                        )
                    }
                    BoostSubmissionResult.Alert(StallAlertReason.BOOST_SUBMISSION_REJECTED)
                }
            }
        }
    }

    private fun complete(
        attempt: BoostAttempt,
        newVendorTransactionId: String,
    ): BoostSubmissionResult {
        transactionRunner.run {
            boosts.markSubmittedByClaim(
                attempt.rootVendorTransactionId,
                attempt.trySequence,
                checkNotNull(attempt.claimId),
                newVendorTransactionId,
                CoreDateTimes.now(clock),
            )
        }
        return BoostSubmissionResult.Submitted(newVendorTransactionId)
    }

    private fun prepare(submission: SubmissionRecord): PreparedBoost {
        val source =
            checkNotNull(accounts.findByAccountId(submission.senderAccountId)) {
                "boost source account not found: accountId=${submission.senderAccountId}"
            }
        val mapping =
            checkNotNull(mappings.find(submission.network, submission.symbol)) {
                "boost asset mapping not found: network=${submission.network} symbol=${submission.symbol}"
            }
        val destination =
            when (submission.recipientType) {
                SubmissionRecipientType.ADDRESS -> VendorTransactionDestination.Address(submission.recipientValue)
                SubmissionRecipientType.WHITELISTED -> VendorTransactionDestination.Whitelisted(submission.recipientValue)
                SubmissionRecipientType.ACCOUNT -> error("internal transaction cannot be boosted")
            }
        return PreparedBoost(submission, source.vendorVaultId, mapping.vendorAssetId, destination)
    }

    private fun BoostAttempt.transferRequest(prepared: PreparedBoost) =
        VendorTransactionRequest(
            externalTransactionId = externalTransactionId,
            vendorAssetId = prepared.vendorAssetId,
            sourceVaultId = prepared.sourceVaultId,
            destination = prepared.destination,
            amount = prepared.submission.amount,
            note = null,
            travelRuleMessage = null,
            useGasless = useGasless,
            replaceTransactionHash = replacementTransactionHash,
            feeLevel = feeLevel,
        )

    private fun VendorTransaction.matches(
        attempt: BoostAttempt,
        prepared: PreparedBoost,
    ): Boolean =
        externalTransactionId == attempt.externalTransactionId &&
            vendorAssetId == prepared.vendorAssetId &&
            source.type == "VAULT_ACCOUNT" &&
            source.id == prepared.sourceVaultId &&
            destination.matches(prepared.destination, destinationAddress) &&
            BigDecimal(amount).compareTo(BigDecimal(prepared.submission.amount)) == 0

    private fun com.whatto.bcm.domain.vendor.VendorTransactionPeer.matches(
        expected: VendorTransactionDestination,
        address: String?,
    ): Boolean =
        when (expected) {
            is VendorTransactionDestination.Address -> type == "ONE_TIME_ADDRESS" && address == expected.address
            is VendorTransactionDestination.Whitelisted -> type == "EXTERNAL_WALLET" && id == expected.walletId
            is VendorTransactionDestination.Account -> type == "VAULT_ACCOUNT" && id == expected.vaultId
        }

    private fun VendorTransaction.decision(candidate: StallCandidate): StallDecision =
        StallDecisionPolicy.decide(
            candidate.record,
            candidate.submissionType,
            StallLatestObservation(transactionId, lifecycleStage, transactionHash, confirmationCount),
        )

    private data class PreparedBoost(
        val submission: SubmissionRecord,
        val sourceVaultId: String,
        val vendorAssetId: String,
        val destination: VendorTransactionDestination,
    )
}
