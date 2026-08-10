package com.whatto.bcm.domain.submission

interface SubmissionRecordRepository {
    fun insert(record: SubmissionRecord): SubmissionRecord

    fun tryClaim(
        externalTransactionId: String,
        claimId: String,
        claimExpiresAt: String,
        now: String,
    ): SubmissionRecord?

    fun markSubmitted(
        externalTransactionId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ): SubmissionRecord

    fun markSubmittedByClaim(
        externalTransactionId: String,
        claimId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ): SubmissionRecord

    fun markFailedByClaim(
        externalTransactionId: String,
        claimId: String,
        respondedAt: String,
    ): SubmissionRecord

    fun findByExternalTransactionId(externalTransactionId: String): SubmissionRecord?

    fun findByVendorTransactionId(vendorTransactionId: String): SubmissionRecord?
}
