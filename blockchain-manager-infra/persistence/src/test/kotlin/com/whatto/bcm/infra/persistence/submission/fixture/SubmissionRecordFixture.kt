package com.whatto.bcm.infra.persistence.submission.fixture

import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical

object SubmissionRecordFixture {
    fun fixture(
        externalTransactionId: String = "wd-260713-0042",
        requestHash: String = "a".repeat(64),
        hashVersion: String = "v1",
        status: SubmissionStatus = SubmissionStatus.REQUESTED,
        claimId: String? = "claim-owner-1",
        claimExpiresAt: String? = "20260807120030",
        transactionType: SubmissionTransactionType = SubmissionTransactionType.WITHDRAWAL,
        vendorTransactionId: String? = null,
        senderAccountId: String = "acct_pool_02",
        recipientType: SubmissionRecipientType = SubmissionRecipientType.ADDRESS,
        recipientValue: String = "0x9fE2",
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        amount: String = "1.5",
        requestedAt: String = "20260807120000",
        respondedAt: String? = null,
        callData: String? = null,
        vendorCanonical: SubmissionVendorCanonical? = null,
    ) = SubmissionRecord(
        externalTransactionId = externalTransactionId,
        requestHash = requestHash,
        hashVersion = hashVersion,
        status = status,
        claimId = claimId,
        claimExpiresAt = claimExpiresAt,
        transactionType = transactionType,
        vendorTransactionId = vendorTransactionId,
        senderAccountId = senderAccountId,
        recipientType = recipientType,
        recipientValue = recipientValue,
        network = network,
        symbol = symbol,
        amount = amount,
        requestedAt = requestedAt,
        respondedAt = respondedAt,
        callData = callData,
        vendorCanonical = vendorCanonical,
    )
}
