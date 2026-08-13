package com.whatto.bcm.domain.submission

import com.whatto.bcm.domain.event.EventType

data class SubmissionRecord(
    val externalTransactionId: String,
    val requestHash: String,
    val hashVersion: String,
    val status: SubmissionStatus,
    val claimId: String?,
    val claimExpiresAt: String?,
    val transactionType: SubmissionTransactionType,
    val vendorTransactionId: String?,
    val senderAccountId: String,
    val recipientType: SubmissionRecipientType,
    val recipientValue: String,
    val network: String,
    val symbol: String,
    val amount: String,
    val requestedAt: String,
    val respondedAt: String?,
    val sweepExecutionId: String? = null,
)

enum class SubmissionStatus {
    REQUESTED,
    SUBMITTED,
    FAILED,
}

enum class SubmissionTransactionType {
    WITHDRAWAL,
    INTERNAL,
    SWEEP_APPROVE,
    SWEEP_BATCH,

    ;

    fun customerEventType(): EventType? =
        when (this) {
            WITHDRAWAL -> EventType.WITHDRAWAL
            INTERNAL -> EventType.INTERNAL
            SWEEP_APPROVE, SWEEP_BATCH -> null
        }
}

enum class SubmissionRecipientType {
    ADDRESS,
    ACCOUNT,
    WHITELISTED,

    ;

    fun transactionType(): SubmissionTransactionType =
        when (this) {
            ACCOUNT -> SubmissionTransactionType.INTERNAL
            ADDRESS, WHITELISTED -> SubmissionTransactionType.WITHDRAWAL
        }
}
