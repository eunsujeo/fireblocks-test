package com.whatto.bcm.app.api.admin

import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.admin.AdminSweepRequestInvestigationService
import com.whatto.bcm.domain.admin.SweepExecutionInvestigation
import com.whatto.bcm.domain.admin.SweepLinkedEvent
import com.whatto.bcm.domain.admin.SweepOperationsOverview
import com.whatto.bcm.domain.admin.SweepRequestInvestigation
import com.whatto.bcm.domain.admin.SweepRequestItemInvestigation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminSweepRequestInvestigationController(
    private val service: AdminSweepRequestInvestigationService,
) {
    @GetMapping("/admin/sweep-request-investigations/{identifier}")
    fun investigation(
        @PathVariable @NotBlank @Size(max = 128) identifier: String,
        request: HttpServletRequest,
    ): ApiResponse<AdminSweepRequestInvestigationData> =
        ApiResponse.of(
            AdminSweepRequestInvestigationData.from(service.investigate(identifier)),
            RequestIdFilter.requestIdOf(request),
        )

    @GetMapping("/admin/sweep-operations")
    fun operations(request: HttpServletRequest): ApiResponse<AdminSweepOperationsData> =
        ApiResponse.of(
            AdminSweepOperationsData.from(service.operationsOverview()),
            RequestIdFilter.requestIdOf(request),
        )
}

data class AdminSweepRequestInvestigationData(
    val sweepRequestId: String,
    val externalSweepRequestId: String,
    val requester: String,
    val requesterEmployeeNo: String,
    val requesterBranchCode: String,
    val network: String,
    val symbol: String,
    val status: String,
    val itemCount: Int,
    val requestedAt: String,
    val finishedAt: String?,
    val retryable: Boolean,
    val nextAction: String,
    val items: List<AdminSweepRequestItemData>,
    val truncatedSources: List<String>,
) {
    companion object {
        fun from(investigation: SweepRequestInvestigation) =
            AdminSweepRequestInvestigationData(
                sweepRequestId = investigation.summary.sweepRequestId,
                externalSweepRequestId = investigation.summary.externalSweepRequestId,
                requester = investigation.summary.requester,
                requesterEmployeeNo = investigation.summary.requesterEmployeeNo,
                requesterBranchCode = investigation.summary.requesterBranchCode,
                network = investigation.summary.network,
                symbol = investigation.summary.symbol,
                status = investigation.summary.status,
                itemCount = investigation.summary.itemCount,
                requestedAt = investigation.summary.requestedAt.toString(),
                finishedAt = investigation.summary.finishedAt?.toString(),
                retryable = investigation.summary.retryable,
                nextAction = investigation.summary.nextAction,
                items = investigation.items.map(AdminSweepRequestItemData::from),
                truncatedSources = investigation.truncatedSources,
            )
    }
}

data class AdminSweepRequestItemData(
    val sweepItemId: String,
    val sequence: Int,
    val accountId: String,
    val status: String,
    val lastFailureCode: String?,
    val retryable: Boolean,
    val nextAction: String,
    val sourceEvents: List<AdminSweepLinkedEventData>,
    val executions: List<AdminSweepExecutionData>,
    val resultEvents: List<AdminSweepLinkedEventData>,
) {
    companion object {
        fun from(item: SweepRequestItemInvestigation) =
            AdminSweepRequestItemData(
                sweepItemId = item.sweepItemId,
                sequence = item.sequence,
                accountId = item.accountId,
                status = item.status,
                lastFailureCode = item.lastFailureCode,
                retryable = item.retryable,
                nextAction = item.nextAction,
                sourceEvents = item.sourceEvents.map(AdminSweepLinkedEventData::from),
                executions = item.executions.map(AdminSweepExecutionData::from),
                resultEvents = item.resultEvents.map(AdminSweepLinkedEventData::from),
            )
    }
}

data class AdminSweepExecutionData(
    val executionId: String,
    val externalTransactionId: String,
    val status: String,
    val operatorAccountId: String,
    val contractAddress: String,
    val policyVersionId: String,
    val policySnapshotHash: String,
    val contractVersionId: String,
    val contractEvidenceId: String,
    val requestedAmount: String,
    val actualAmount: String?,
    val itemStatus: String,
    val failureCode: String?,
    val logIndex: Int?,
    val transactionId: String?,
    val transactionHash: String?,
    val requestedAt: String,
    val finishedAt: String?,
) {
    companion object {
        fun from(execution: SweepExecutionInvestigation) =
            AdminSweepExecutionData(
                executionId = execution.executionId,
                externalTransactionId = execution.externalTransactionId,
                status = execution.status,
                operatorAccountId = execution.operatorAccountId,
                contractAddress = execution.contractAddress,
                policyVersionId = execution.policyVersionId,
                policySnapshotHash = execution.policySnapshotHash,
                contractVersionId = execution.contractVersionId,
                contractEvidenceId = execution.contractEvidenceId,
                requestedAmount = execution.requestedAmount.toPlainString(),
                actualAmount = execution.actualAmount?.toPlainString(),
                itemStatus = execution.itemStatus,
                failureCode = execution.failureCode,
                logIndex = execution.logIndex,
                transactionId = execution.transactionId,
                transactionHash = execution.transactionHash,
                requestedAt = execution.requestedAt.toString(),
                finishedAt = execution.finishedAt?.toString(),
            )
    }
}

data class AdminSweepLinkedEventData(
    val eventId: String,
    val eventType: String,
    val outboxStatus: String,
    val chainStatus: String?,
    val itemOutcome: String?,
    val failureCode: String?,
    val publishedAt: String?,
    val dawCompletedAt: String?,
) {
    companion object {
        fun from(event: SweepLinkedEvent) =
            AdminSweepLinkedEventData(
                eventId = event.eventId,
                eventType = event.eventType,
                outboxStatus = event.outboxStatus,
                chainStatus = event.chainStatus,
                itemOutcome = event.itemOutcome,
                failureCode = event.failureCode,
                publishedAt = event.publishedAt?.toString(),
                dawCompletedAt = event.dawCompletedAt?.toString(),
            )
    }
}

data class AdminSweepOperationsData(
    val acceptedRequestCount: Long,
    val blockedRequestCount: Long,
    val processingRequestCount: Long,
    val partialRequestCount: Long,
    val failedRequestCount: Long,
    val pendingItemCount: Long,
    val processingItemCount: Long,
    val oldestPendingRequestedAt: String?,
    val pendingEventCount: Long,
    val failedEventCount: Long,
    val awaitingDawCompletionCount: Long,
    val oldestAwaitingDawCompletionAt: String?,
) {
    companion object {
        fun from(overview: SweepOperationsOverview) =
            AdminSweepOperationsData(
                acceptedRequestCount = overview.acceptedRequestCount,
                blockedRequestCount = overview.blockedRequestCount,
                processingRequestCount = overview.processingRequestCount,
                partialRequestCount = overview.partialRequestCount,
                failedRequestCount = overview.failedRequestCount,
                pendingItemCount = overview.pendingItemCount,
                processingItemCount = overview.processingItemCount,
                oldestPendingRequestedAt = overview.oldestPendingRequestedAt?.toString(),
                pendingEventCount = overview.pendingEventCount,
                failedEventCount = overview.failedEventCount,
                awaitingDawCompletionCount = overview.awaitingDawCompletionCount,
                oldestAwaitingDawCompletionAt = overview.oldestAwaitingDawCompletionAt?.toString(),
            )
    }
}
