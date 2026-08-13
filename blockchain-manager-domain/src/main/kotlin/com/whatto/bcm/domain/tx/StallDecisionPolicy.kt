package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.vendor.VendorTransactionLifecycleStage

data class StallLatestObservation(
    val vendorTransactionId: String,
    val lifecycleStage: VendorTransactionLifecycleStage,
    val transactionHash: String?,
    val confirmationCount: Int,
)

sealed interface StallDecision {
    data class BoostEligible(
        val transactionHash: String,
    ) : StallDecision

    data class Alert(
        val reason: StallAlertReason,
    ) : StallDecision
}

enum class StallAlertReason {
    PRE_CHAIN_DELAY,
    MINED_CONFIRMATION_DELAY,
    TRANSACTION_NOT_OWNED,
    TRANSACTION_TYPE_NOT_ELIGIBLE,
    NETWORK_NOT_ELIGIBLE,
    TRANSACTION_HASH_MISSING,
    TRANSACTION_HASH_MISMATCH,
    ACTIVE_TRANSACTION_MISMATCH,
    TERMINAL_OBSERVED,
    UNKNOWN_VENDOR_STAGE,
    VENDOR_TRANSACTION_NOT_FOUND,
    AUTOMATIC_BOOST_DISABLED,
    MAXIMUM_BOOST_ATTEMPTS_REACHED,
    BOOST_SUBMISSION_REJECTED,
}

object StallDecisionPolicy {
    fun decide(
        record: TxRecord,
        submissionType: SubmissionTransactionType?,
        observation: StallLatestObservation,
    ): StallDecision {
        if (record.activeVendorTxId != observation.vendorTransactionId) {
            return StallDecision.Alert(StallAlertReason.ACTIVE_TRANSACTION_MISMATCH)
        }
        return when (observation.lifecycleStage) {
            VendorTransactionLifecycleStage.PRE_CHAIN ->
                StallDecision.Alert(StallAlertReason.PRE_CHAIN_DELAY)

            VendorTransactionLifecycleStage.TERMINAL ->
                StallDecision.Alert(StallAlertReason.TERMINAL_OBSERVED)

            VendorTransactionLifecycleStage.UNKNOWN ->
                StallDecision.Alert(StallAlertReason.UNKNOWN_VENDOR_STAGE)

            VendorTransactionLifecycleStage.CONFIRMING ->
                confirmingDecision(record, submissionType, observation)
        }
    }

    private fun confirmingDecision(
        record: TxRecord,
        submissionType: SubmissionTransactionType?,
        observation: StallLatestObservation,
    ): StallDecision {
        if (observation.confirmationCount > 0) {
            return StallDecision.Alert(StallAlertReason.MINED_CONFIRMATION_DELAY)
        }
        val transactionHash =
            observation.transactionHash
                ?: return StallDecision.Alert(StallAlertReason.TRANSACTION_HASH_MISSING)
        if (record.transactionHash != null && record.transactionHash != transactionHash) {
            return StallDecision.Alert(StallAlertReason.TRANSACTION_HASH_MISMATCH)
        }
        if (submissionType == null) {
            return StallDecision.Alert(StallAlertReason.TRANSACTION_NOT_OWNED)
        }
        if (submissionType !in BOOSTABLE_TRANSACTION_TYPES) {
            return StallDecision.Alert(StallAlertReason.TRANSACTION_TYPE_NOT_ELIGIBLE)
        }
        if (record.network !in EVM_NETWORKS) {
            return StallDecision.Alert(StallAlertReason.NETWORK_NOT_ELIGIBLE)
        }
        return StallDecision.BoostEligible(transactionHash)
    }

    private val BOOSTABLE_TRANSACTION_TYPES =
        setOf(
            SubmissionTransactionType.WITHDRAWAL,
            SubmissionTransactionType.SWEEP_APPROVE,
            SubmissionTransactionType.SWEEP_BATCH,
        )
    private val EVM_NETWORKS = setOf("ETHEREUM", "BASE")
}
