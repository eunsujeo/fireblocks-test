package com.whatto.bcm.domain.admin

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

data class AdminContractSummary(
    val versionId: String,
    val scopeId: String,
    val network: String,
    val use: String,
    val version: String,
    val address: String,
    val state: String,
    val runtimeCodeHash: String,
    val evidenceStatus: String?,
    val evidenceValidUntil: Instant?,
    val active: Boolean,
)

data class AdminPolicySummary(
    val versionId: String,
    val scopeId: String,
    val versionNumber: Int,
    val schemaVersion: String,
    val state: String,
    val policyHash: String,
    val ceilingPassed: Boolean,
    val active: Boolean,
    val registeredAt: Instant,
)

data class AdminChangeRequestDetail(
    val request: AdminChangeRequest,
    val state: String,
    val decisions: List<AdminDecisionRecord>,
    val requiredApprovals: Int,
    val approvalCount: Int,
    val securityApprovalRequired: Boolean,
    val securityApprovalCount: Int,
    val activationReady: Boolean,
    val disabledReasons: List<String>,
)

data class AdminBandSSummary(
    val proposalId: String,
    val sourceProposalId: String,
    val snapshotId: String,
    val sourceRequestId: String,
    val policyVersionId: String,
    val snapshotHash: String,
    val inputHash: String,
    val observedAt: Instant,
    val expiresAt: Instant,
    val inputComplete: Boolean,
    val issueCodes: List<String>,
    val totalAssetKrwAmount: java.math.BigDecimal,
    val observedHotKrwAmount: java.math.BigDecimal,
    val observedColdKrwAmount: java.math.BigDecimal,
    val effectiveHotKrwAmount: java.math.BigDecimal,
    val hotRatio: java.math.BigDecimal,
    val lowerRatio: java.math.BigDecimal,
    val targetRatio: java.math.BigDecimal,
    val upperRatio: java.math.BigDecimal,
    val direction: BandSDirection,
    val proposalHash: String,
    val totalKrwAmount: java.math.BigDecimal,
    val afterHotRatio: java.math.BigDecimal,
    val state: String,
    val requestId: String?,
    val requestState: String?,
    val approvalCount: Int,
    val requiredApprovals: Int,
    val executionId: String?,
    val executionStatus: BandSExecutionStatus?,
    val reservedAt: Instant?,
    val executionReady: Boolean,
    val disabledReasons: List<String>,
    val items: List<AdminBandSItemSummary>,
)

data class AdminBandSItemSummary(
    val sequence: Int,
    val dependsOnSequence: Int?,
    val legType: BandSLegType,
    val network: String,
    val tokenSymbol: String,
    val sourceVaultId: String?,
    val destinationVaultId: String?,
    val destinationAddress: String?,
    val amount: java.math.BigDecimal,
    val krwAmount: java.math.BigDecimal,
    val expectedFeeAmount: java.math.BigDecimal,
    val itemHash: String,
    val executable: Boolean,
    val blockReason: String?,
    val executionStatus: BandSExecutionEventStatus?,
)

data class AdminExecutionGateScope(
    val network: String,
    val type: ExecutionGateType,
    val current: ExecutionGateEvent?,
    val releaseContextReady: Boolean = false,
)

data class AdminExecutionGateSummary(
    val network: String,
    val type: ExecutionGateType,
    val state: ExecutionGateState,
    val stoppedAt: Instant?,
    val reason: String?,
    val workTicket: String?,
    val actorEmployeeNo: String?,
    val sequence: Int?,
    val newExecutionAllowed: Boolean,
    val existingExecutionRecoveryAllowed: Boolean,
    val emergencyRevocationAllowed: Boolean,
    val disabledReasons: List<String>,
)

data class AdminExecutionGateOverview(
    val observedAt: Instant,
    val truncated: Boolean,
    val gates: List<AdminExecutionGateSummary>,
    val externalControls: List<AdminExternalControlEvidenceSummary>,
    val allowanceRevocations: List<AdminAllowanceRevocationSummary>,
    val webhookRecoveries: List<AdminWebhookRecoverySummary>,
    val resumes: List<AdminExecutionGateResumeSummary> = emptyList(),
)

data class AdminExecutionGateResumeSummary(
    val resumeId: String,
    val requestId: String,
    val network: String,
    val type: ExecutionGateType,
    val state: String,
    val stoppedEventId: String,
    val contractVersionId: String,
    val contractEvidenceId: String,
    val revocationExecutionId: String,
    val causeEvidenceUri: String,
    val causeEvidenceHash: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val requestedByEmployeeNo: String,
    val approvalCount: Int,
    val requiredApprovals: Int,
    val securityApprovalCount: Int,
    val latestCheckStatus: ExecutionGateResumeCheckStatus?,
    val latestCheckObservedAt: Instant?,
    val latestCheckValidUntil: Instant?,
    val issues: List<String>,
    val resumeReady: Boolean,
    val disabledReasons: List<String>,
    val retryable: Boolean,
    val retryCondition: String,
    val statusPath: String = ADMIN_EXECUTION_GATE_STATUS_PATH,
)

data class AdminWebhookRecoverySummary(
    val requestId: String,
    val webhookId: String,
    val state: WebhookRecoveryState,
    val scope: WebhookRecoveryScope,
    val requiredEvents: List<String>,
    val requestedAt: Instant,
    val requestedByEmployeeNo: String,
    val approvedAt: Instant,
    val approvedByEmployeeNo: String,
    val reason: String,
    val workTicket: String,
    val latestEvent: WebhookRecoveryEventStatus?,
    val callType: WebhookRecoveryCallType?,
    val calledAt: Instant?,
    val resultAt: Instant?,
    val previousStatus: String?,
    val currentStatus: String?,
    val missingRequiredEvents: List<String>,
    val scopeFrom: Instant?,
    val scopeTo: Instant?,
    val scheduledNotificationCount: Int?,
    val errorCode: String?,
    val retryable: Boolean,
    val retryCondition: String,
    val statusPath: String = ADMIN_EXECUTION_GATE_STATUS_PATH,
)

fun WebhookRecoveryView.toAdminSummary(
    now: Instant,
    intentTimeout: Duration,
): AdminWebhookRecoverySummary {
    val latest = events.maxByOrNull(WebhookRecoveryEvent::sequence)
    val state = state(now, intentTimeout)
    val subscriptionResults = events.filter { it.status in WEBHOOK_SUBSCRIPTION_RESULTS }
    val firstSubscription = subscriptionResults.firstOrNull()
    val latestSubscription = subscriptionResults.lastOrNull()
    return AdminWebhookRecoverySummary(
        requestId = request.requestId,
        webhookId = request.webhookId,
        state = state,
        scope = request.scope,
        requiredEvents = request.requiredEvents.sorted(),
        requestedAt = request.requestedAt,
        requestedByEmployeeNo = request.requestedBy.employeeNo,
        approvedAt = request.approvedAt,
        approvedByEmployeeNo = request.approvedBy.employeeNo,
        reason = request.reason,
        workTicket = request.workTicket,
        latestEvent = latest?.status,
        callType = latest?.callType,
        calledAt = latest?.calledAt,
        resultAt = latest?.resultAt,
        previousStatus = firstSubscription?.webhookStatus,
        currentStatus = latestSubscription?.webhookStatus,
        missingRequiredEvents = (request.requiredEvents - (latestSubscription?.observedEvents ?: emptySet())).sorted(),
        scopeFrom = events.lastOrNull { it.scopeFrom != null }?.scopeFrom,
        scopeTo = events.lastOrNull { it.scopeTo != null }?.scopeTo,
        scheduledNotificationCount = events.lastOrNull { it.scheduledNotificationCount != null }?.scheduledNotificationCount,
        errorCode = latest?.errorCode,
        retryable = state == WebhookRecoveryState.ACCEPTED,
        retryCondition =
            when (state) {
                WebhookRecoveryState.ACCEPTED -> "EXECUTION_READY"
                WebhookRecoveryState.IN_PROGRESS -> "WAIT_FOR_CURRENT_ATTEMPT"
                WebhookRecoveryState.AMBIGUOUS -> "STATUS_RECONCILIATION_REQUIRED"
                WebhookRecoveryState.FAILED -> "NEW_REQUEST_REQUIRED"
                WebhookRecoveryState.COMPLETED -> "COMPLETED"
            },
    )
}

private val WEBHOOK_SUBSCRIPTION_RESULTS =
    setOf(WebhookRecoveryEventStatus.STATUS_OBSERVED, WebhookRecoveryEventStatus.ACTIVATED)

data class AdminAllowanceRevocationSummary(
    val executionId: String,
    val requestId: String,
    val network: String,
    val contractVersionId: String,
    val contractBindingRevision: Long,
    val sweepContractAddress: String,
    val targetSnapshotHash: String,
    val status: AllowanceRevocationExecutionStatus,
    val totalCount: Int,
    val zeroConfirmedCount: Int,
    val submittingCount: Int,
    val failedCount: Int,
    val registeredAt: Instant,
    val items: List<AdminAllowanceRevocationItemSummary>,
    val retryable: Boolean,
    val retryCondition: String,
    val statusPath: String = ADMIN_EXECUTION_GATE_STATUS_PATH,
)

const val ADMIN_EXECUTION_GATE_STATUS_PATH = "/admin/execution-gates"

data class AdminAllowanceRevocationItemSummary(
    val sequence: Int,
    val accountId: String,
    val network: String,
    val symbol: String,
    val sourceVaultId: String,
    val ownerAddress: String,
    val tokenContractAddress: String,
    val beforeObservedAllowance: BigDecimal,
    val externalTransactionId: String,
    val latestStatus: AllowanceRevocationEventStatus?,
    val vendorTransactionId: String?,
    val observedAllowance: BigDecimal,
    val observedAt: Instant,
    val errorCode: String?,
    val occurredAt: Instant?,
)

data class AdminExternalControlEvidenceSummary(
    val evidenceId: String,
    val network: String,
    val contractVersionId: String,
    val status: EmergencyExternalControlStatus,
    val completionReady: Boolean,
    val snapshotHash: String,
    val tapSourceId: String,
    val tapBlocked: Boolean?,
    val pinnedBlockNumber: BigInteger,
    val expectedOperatorSetHash: String,
    val firstEndpointId: String,
    val firstPaused: Boolean?,
    val firstOperatorSetHash: String?,
    val secondEndpointId: String,
    val secondPaused: Boolean?,
    val secondOperatorSetHash: String?,
    val observedAt: Instant,
    val validUntil: Instant,
    val reason: String,
    val workTicket: String,
    val actorEmployeeNo: String,
    val issues: List<String>,
) {
    fun asOf(now: Instant): AdminExternalControlEvidenceSummary =
        if (now.isBefore(validUntil)) {
            this
        } else {
            copy(
                status = EmergencyExternalControlStatus.STALE,
                completionReady = false,
                issues = (issues + "EVIDENCE_EXPIRED").distinct(),
            )
        }
}

interface AdminGovernanceQueryRepository {
    fun findContracts(now: Instant): List<AdminContractSummary>

    fun findPolicies(now: Instant): List<AdminPolicySummary>

    fun findChangeRequest(
        requestId: String,
        now: Instant,
    ): AdminChangeRequestDetail?

    fun findBandS(now: Instant): List<AdminBandSSummary>

    fun findExecutionGateScopes(
        now: Instant,
        hardCeiling: SweepPolicyHardCeiling,
        limit: Int,
    ): List<AdminExecutionGateScope>

    fun findLatestExternalControls(limit: Int): List<AdminExternalControlEvidenceSummary>

    fun findAllowanceRevocations(limit: Int): List<AdminAllowanceRevocationSummary>

    fun findWebhookRecoveries(limit: Int): List<WebhookRecoveryView>

    fun findExecutionGateResumes(
        now: Instant,
        limit: Int,
    ): List<AdminExecutionGateResumeSummary>

    fun findWebhookRuntimeObservation(): AdminWebhookRuntimeObservation
}

enum class AdminWebhookRuntimeState {
    NEVER_RECEIVED,
    HEALTHY,
    BACKLOG,
    POISONED,
}

data class AdminWebhookRuntimeObservation(
    val lastReceivedAt: Instant?,
    val pendingInboxCount: Long,
    val poisonedInboxCount: Long,
    val pendingOutboxCount: Long,
    val poisonedOutboxCount: Long,
) {
    val state: AdminWebhookRuntimeState
        get() =
            when {
                poisonedInboxCount > 0 || poisonedOutboxCount > 0 -> AdminWebhookRuntimeState.POISONED
                pendingInboxCount > 0 || pendingOutboxCount > 0 -> AdminWebhookRuntimeState.BACKLOG
                lastReceivedAt == null -> AdminWebhookRuntimeState.NEVER_RECEIVED
                else -> AdminWebhookRuntimeState.HEALTHY
            }
}

data class AdminRuntimeReadiness(
    val observedAt: Instant,
    val webhook: AdminWebhookRuntimeObservation,
)
