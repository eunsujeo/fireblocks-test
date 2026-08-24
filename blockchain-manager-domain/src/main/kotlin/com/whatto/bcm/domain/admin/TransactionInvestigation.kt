package com.whatto.bcm.domain.admin

import java.math.BigDecimal
import java.time.Instant

data class TransactionInvestigation(
    val summary: TransactionInvestigationSummary,
    val timeline: List<TransactionTimelineEntry>,
    val boosts: List<TransactionBoostAttempt>,
    val sweepExecution: TransactionSweepExecution?,
    val allowances: List<TransactionAllowance>,
    val feeQuotes: List<TransactionFeeQuote>,
    val truncatedSources: List<String>,
)

data class TransactionInvestigationSummary(
    val rootTransactionId: String,
    val activeTransactionId: String,
    val externalTransactionId: String?,
    val transactionHash: String?,
    val accountId: String,
    val network: String,
    val symbol: String,
    val transactionType: String?,
    val status: String,
    val confirmationCount: Int,
    val vendorSubStatus: String?,
    val vendorNetworkStatus: String?,
    val submissionStatus: String?,
    val amount: BigDecimal?,
    val senderAccountId: String?,
    val receiverType: String?,
    val receiverValue: String?,
    val sweepExecutionId: String?,
    val submissionRequestedAt: Instant?,
    val submissionRespondedAt: Instant?,
    val vendorCreatedAt: Instant,
    val firstDetectedAt: Instant,
    val lastChangedAt: Instant,
    val reconciliationCheckedAt: Instant?,
    val reconciliationCheckCount: Int,
    val reconciliationStoppedAt: Instant?,
)

data class TransactionTimelineEntry(
    val source: String,
    val code: String,
    val status: String?,
    val observedAt: Instant?,
    val identifier: String?,
)

data class TransactionBoostAttempt(
    val attemptSequence: Int,
    val externalTransactionId: String,
    val status: String,
    val replacedTransactionId: String,
    val replacedTransactionHash: String,
    val newTransactionId: String?,
    val feeLevel: String,
    val gasless: Boolean,
    val requestedAt: Instant,
    val respondedAt: Instant?,
)

data class TransactionSweepExecution(
    val executionId: String,
    val externalTransactionId: String,
    val status: String,
    val operatorAccountId: String,
    val contractAddress: String,
    val requestedTotalAmount: BigDecimal,
    val actualTotalAmount: BigDecimal?,
    val transactionId: String?,
    val transactionHash: String?,
    val requestedAt: Instant,
    val finishedAt: Instant?,
    val items: List<TransactionSweepItem>,
)

data class TransactionSweepItem(
    val sequence: Int,
    val accountId: String,
    val sourceAddress: String,
    val requestedAmount: BigDecimal,
    val actualAmount: BigDecimal?,
    val status: String,
    val failureCode: String?,
    val logIndex: Int?,
)

data class TransactionAllowance(
    val accountId: String,
    val network: String,
    val symbol: String,
    val contractAddress: String,
    val cap: BigDecimal,
    val observedAllowance: BigDecimal,
    val status: String,
    val checkedAt: Instant,
)

data class TransactionFeeQuote(
    val context: String,
    val level: String,
    val observedAt: Instant,
    val feePerByte: BigDecimal?,
    val gasPrice: BigDecimal?,
    val networkFee: BigDecimal?,
    val baseFee: BigDecimal?,
    val priorityFee: BigDecimal?,
)

interface TransactionInvestigationRepository {
    fun findByIdentifier(identifier: String): TransactionInvestigation?
}
