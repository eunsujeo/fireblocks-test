package com.whatto.bcm.domain.admin

import java.math.BigDecimal
import java.time.Instant

data class SweepRequestInvestigation(
    val summary: SweepRequestSummary,
    val items: List<SweepRequestItemInvestigation>,
    val truncatedSources: List<String>,
)

data class SweepRequestSummary(
    val sweepRequestId: String,
    val externalSweepRequestId: String,
    val requester: String,
    val requesterEmployeeNo: String,
    val requesterBranchCode: String,
    val network: String,
    val symbol: String,
    val status: String,
    val itemCount: Int,
    val requestedAt: Instant,
    val finishedAt: Instant?,
    val retryable: Boolean,
    val nextAction: String,
)

data class SweepRequestItemInvestigation(
    val sweepItemId: String,
    val sequence: Int,
    val accountId: String,
    val status: String,
    val lastFailureCode: String?,
    val retryable: Boolean,
    val nextAction: String,
    val sourceEvents: List<SweepLinkedEvent>,
    val executions: List<SweepExecutionInvestigation>,
    val resultEvents: List<SweepLinkedEvent>,
)

data class SweepExecutionInvestigation(
    val executionId: String,
    val externalTransactionId: String,
    val status: String,
    val operatorAccountId: String,
    val contractAddress: String,
    val policyVersionId: String,
    val policySnapshotHash: String,
    val contractVersionId: String,
    val contractEvidenceId: String,
    val requestedAmount: BigDecimal,
    val actualAmount: BigDecimal?,
    val itemStatus: String,
    val failureCode: String?,
    val logIndex: Int?,
    val transactionId: String?,
    val transactionHash: String?,
    val requestedAt: Instant,
    val finishedAt: Instant?,
)

data class SweepLinkedEvent(
    val eventId: String,
    val eventType: String,
    val outboxStatus: String,
    val chainStatus: String?,
    val itemOutcome: String?,
    val failureCode: String?,
    val publishedAt: Instant?,
    val dawCompletedAt: Instant?,
)

data class SweepOperationsOverview(
    val acceptedRequestCount: Long,
    val blockedRequestCount: Long,
    val processingRequestCount: Long,
    val partialRequestCount: Long,
    val failedRequestCount: Long,
    val pendingItemCount: Long,
    val processingItemCount: Long,
    val oldestPendingRequestedAt: Instant?,
    val pendingEventCount: Long,
    val failedEventCount: Long,
    val awaitingDawCompletionCount: Long,
    val oldestAwaitingDawCompletionAt: Instant?,
)

interface SweepRequestInvestigationRepository {
    fun findByIdentifier(identifier: String): SweepRequestInvestigation?

    fun operationsOverview(): SweepOperationsOverview
}
