package com.whatto.bcm.infra.persistence.submission.fixture

import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType

object SubmissionRecordFixture {
    fun fixture(
        externalTransactionId: String = "wd-260713-0042",
        requestHash: String = "a".repeat(64),
        status: SubmissionStatus = SubmissionStatus.REQUESTED,
        claimId: String? = "claim-owner-1",
        claimExpiresAt: String? = "20260807120030",
        transactionType: SubmissionTransactionType = SubmissionTransactionType.WITHDRAWAL,
        vendorTransactionId: String? = null,
        amount: String = "1.5",
        respondedAt: String? = null,
    ) = SubmissionRecord(
        externalTransactionId = externalTransactionId,
        requestHash = requestHash,
        hashVersion = "v1",
        status = status,
        claimId = claimId,
        claimExpiresAt = claimExpiresAt,
        transactionType = transactionType,
        vendorTransactionId = vendorTransactionId,
        senderAccountId = "acct_pool_02",
        recipientType = SubmissionRecipientType.ADDRESS,
        recipientValue = "0x9fE2",
        network = "ETHEREUM",
        symbol = "USDC",
        amount = amount,
        requestedAt = "20260807120000",
        respondedAt = respondedAt,
    )
}
