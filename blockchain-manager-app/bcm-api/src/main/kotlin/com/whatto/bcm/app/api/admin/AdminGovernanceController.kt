package com.whatto.bcm.app.api.admin

import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.admin.AdminGovernanceQueryService
import com.whatto.bcm.domain.admin.AdminAllowanceRevocationItemSummary
import com.whatto.bcm.domain.admin.AdminAllowanceRevocationSummary
import com.whatto.bcm.domain.admin.AdminBandSItemSummary
import com.whatto.bcm.domain.admin.AdminBandSSummary
import com.whatto.bcm.domain.admin.AdminChangeRequestDetail
import com.whatto.bcm.domain.admin.AdminContractSummary
import com.whatto.bcm.domain.admin.AdminDecisionRecord
import com.whatto.bcm.domain.admin.AdminExecutionGateOverview
import com.whatto.bcm.domain.admin.AdminExecutionGateResumeSummary
import com.whatto.bcm.domain.admin.AdminExecutionGateSummary
import com.whatto.bcm.domain.admin.AdminExternalControlEvidenceSummary
import com.whatto.bcm.domain.admin.AdminPolicySummary
import com.whatto.bcm.domain.admin.AdminWebhookRecoverySummary
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
class AdminGovernanceController(
    private val service: AdminGovernanceQueryService,
) {
    @GetMapping("/admin/contracts")
    fun contracts(request: HttpServletRequest): ApiResponse<List<AdminContractData>> =
        ApiResponse.of(service.contracts().map(AdminContractData::from), RequestIdFilter.requestIdOf(request))

    @GetMapping("/admin/policies")
    fun policies(request: HttpServletRequest): ApiResponse<List<AdminPolicyData>> =
        ApiResponse.of(service.policies().map(AdminPolicyData::from), RequestIdFilter.requestIdOf(request))

    @GetMapping("/admin/band-s")
    fun bandS(request: HttpServletRequest): ApiResponse<List<AdminBandSData>> =
        ApiResponse.of(service.bandS().map(AdminBandSData::from), RequestIdFilter.requestIdOf(request))

    @GetMapping("/admin/execution-gates")
    fun executionGates(request: HttpServletRequest): ApiResponse<AdminExecutionGateOverviewData> =
        ApiResponse.of(
            AdminExecutionGateOverviewData.from(service.executionGates()),
            RequestIdFilter.requestIdOf(request),
        )

    @GetMapping("/admin/change-requests/{requestId}")
    fun changeRequest(
        @PathVariable @Size(max = 36) requestId: String,
        request: HttpServletRequest,
    ): ApiResponse<AdminChangeRequestData> =
        ApiResponse.of(AdminChangeRequestData.from(service.changeRequest(requestId)), RequestIdFilter.requestIdOf(request))
}

data class AdminExecutionGateOverviewData(
    val observedAt: String,
    val truncated: Boolean,
    val gates: List<AdminExecutionGateData>,
    val externalControls: List<AdminExternalControlEvidenceData>,
    val allowanceRevocations: List<AdminAllowanceRevocationData>,
    val webhookRecoveries: List<AdminWebhookRecoveryData>,
    val resumes: List<AdminExecutionGateResumeData>,
) {
    companion object {
        fun from(value: AdminExecutionGateOverview) =
            AdminExecutionGateOverviewData(
                observedAt = value.observedAt.toString(),
                truncated = value.truncated,
                gates = value.gates.map(AdminExecutionGateData::from),
                externalControls = value.externalControls.map(AdminExternalControlEvidenceData::from),
                allowanceRevocations = value.allowanceRevocations.map(AdminAllowanceRevocationData::from),
                webhookRecoveries = value.webhookRecoveries.map(AdminWebhookRecoveryData::from),
                resumes = value.resumes.map(AdminExecutionGateResumeData::from),
            )
    }
}

data class AdminExecutionGateResumeData(
    val resumeId: String,
    val requestId: String,
    val network: String,
    val type: String,
    val state: String,
    val stoppedEventId: String,
    val contractVersionId: String,
    val contractEvidenceId: String,
    val revocationExecutionId: String,
    val causeEvidenceUri: String,
    val causeEvidenceHash: String,
    val requestedAt: String,
    val expiresAt: String,
    val requestedByEmployeeNo: String,
    val approvalCount: Int,
    val requiredApprovals: Int,
    val securityApprovalCount: Int,
    val latestCheckStatus: String?,
    val latestCheckObservedAt: String?,
    val latestCheckValidUntil: String?,
    val issues: List<String>,
    val resumeReady: Boolean,
    val disabledReasons: List<String>,
    val retryable: Boolean,
    val retryCondition: String,
    val statusPath: String,
) {
    companion object {
        fun from(value: AdminExecutionGateResumeSummary) =
            AdminExecutionGateResumeData(
                value.resumeId,
                value.requestId,
                value.network,
                value.type.name,
                value.state,
                value.stoppedEventId,
                value.contractVersionId,
                value.contractEvidenceId,
                value.revocationExecutionId,
                value.causeEvidenceUri,
                value.causeEvidenceHash,
                value.requestedAt.toString(),
                value.expiresAt.toString(),
                value.requestedByEmployeeNo,
                value.approvalCount,
                value.requiredApprovals,
                value.securityApprovalCount,
                value.latestCheckStatus?.name,
                value.latestCheckObservedAt?.toString(),
                value.latestCheckValidUntil?.toString(),
                value.issues,
                value.resumeReady,
                value.disabledReasons,
                value.retryable,
                value.retryCondition,
                value.statusPath,
            )
    }
}

data class AdminWebhookRecoveryData(
    val requestId: String,
    val webhookId: String,
    val state: String,
    val scope: String,
    val requiredEvents: List<String>,
    val requestedAt: String,
    val requestedByEmployeeNo: String,
    val approvedAt: String,
    val approvedByEmployeeNo: String,
    val reason: String,
    val workTicket: String,
    val latestEvent: String?,
    val callType: String?,
    val calledAt: String?,
    val resultAt: String?,
    val previousStatus: String?,
    val currentStatus: String?,
    val missingRequiredEvents: List<String>,
    val scopeFrom: String?,
    val scopeTo: String?,
    val scheduledNotificationCount: Int?,
    val errorCode: String?,
    val retryable: Boolean,
    val retryCondition: String,
    val statusPath: String,
) {
    companion object {
        fun from(value: AdminWebhookRecoverySummary) =
            AdminWebhookRecoveryData(
                requestId = value.requestId,
                webhookId = value.webhookId,
                state = value.state.name,
                scope = value.scope.name,
                requiredEvents = value.requiredEvents,
                requestedAt = value.requestedAt.toString(),
                requestedByEmployeeNo = value.requestedByEmployeeNo,
                approvedAt = value.approvedAt.toString(),
                approvedByEmployeeNo = value.approvedByEmployeeNo,
                reason = value.reason,
                workTicket = value.workTicket,
                latestEvent = value.latestEvent?.name,
                callType = value.callType?.name,
                calledAt = value.calledAt?.toString(),
                resultAt = value.resultAt?.toString(),
                previousStatus = value.previousStatus,
                currentStatus = value.currentStatus,
                missingRequiredEvents = value.missingRequiredEvents,
                scopeFrom = value.scopeFrom?.toString(),
                scopeTo = value.scopeTo?.toString(),
                scheduledNotificationCount = value.scheduledNotificationCount,
                errorCode = value.errorCode,
                retryable = value.retryable,
                retryCondition = value.retryCondition,
                statusPath = value.statusPath,
            )
    }
}

data class AdminAllowanceRevocationData(
    val executionId: String,
    val requestId: String,
    val network: String,
    val contractVersionId: String,
    val contractBindingRevision: Long,
    val sweepContractAddress: String,
    val targetSnapshotHash: String,
    val status: String,
    val totalCount: Int,
    val zeroConfirmedCount: Int,
    val submittingCount: Int,
    val failedCount: Int,
    val registeredAt: String,
    val items: List<AdminAllowanceRevocationItemData>,
    val retryable: Boolean,
    val retryCondition: String,
    val statusPath: String,
) {
    companion object {
        fun from(value: AdminAllowanceRevocationSummary) =
            AdminAllowanceRevocationData(
                executionId = value.executionId,
                requestId = value.requestId,
                network = value.network,
                contractVersionId = value.contractVersionId,
                contractBindingRevision = value.contractBindingRevision,
                sweepContractAddress = value.sweepContractAddress,
                targetSnapshotHash = value.targetSnapshotHash,
                status = value.status.name,
                totalCount = value.totalCount,
                zeroConfirmedCount = value.zeroConfirmedCount,
                submittingCount = value.submittingCount,
                failedCount = value.failedCount,
                registeredAt = value.registeredAt.toString(),
                items = value.items.map(AdminAllowanceRevocationItemData::from),
                retryable = value.retryable,
                retryCondition = value.retryCondition,
                statusPath = value.statusPath,
            )
    }
}

data class AdminAllowanceRevocationItemData(
    val sequence: Int,
    val accountId: String,
    val network: String,
    val symbol: String,
    val sourceVaultId: String,
    val ownerAddress: String,
    val tokenContractAddress: String,
    val beforeObservedAllowance: String,
    val externalTransactionId: String,
    val latestStatus: String?,
    val vendorTransactionId: String?,
    val observedAllowance: String,
    val observedAt: String,
    val errorCode: String?,
    val occurredAt: String?,
) {
    companion object {
        fun from(value: AdminAllowanceRevocationItemSummary) =
            AdminAllowanceRevocationItemData(
                sequence = value.sequence,
                accountId = value.accountId,
                network = value.network,
                symbol = value.symbol,
                sourceVaultId = value.sourceVaultId,
                ownerAddress = value.ownerAddress,
                tokenContractAddress = value.tokenContractAddress,
                beforeObservedAllowance = value.beforeObservedAllowance.toPlainString(),
                externalTransactionId = value.externalTransactionId,
                latestStatus = value.latestStatus?.name,
                vendorTransactionId = value.vendorTransactionId,
                observedAllowance = value.observedAllowance.toPlainString(),
                observedAt = value.observedAt.toString(),
                errorCode = value.errorCode,
                occurredAt = value.occurredAt?.toString(),
            )
    }
}

data class AdminExternalControlEvidenceData(
    val evidenceId: String,
    val network: String,
    val contractVersionId: String,
    val status: String,
    val completionReady: Boolean,
    val snapshotHash: String,
    val tapSourceId: String,
    val tapBlocked: Boolean?,
    val pinnedBlockNumber: String,
    val expectedOperatorSetHash: String,
    val firstEndpointId: String,
    val firstPaused: Boolean?,
    val firstOperatorSetHash: String?,
    val secondEndpointId: String,
    val secondPaused: Boolean?,
    val secondOperatorSetHash: String?,
    val observedAt: String,
    val validUntil: String,
    val reason: String,
    val workTicket: String,
    val actorEmployeeNo: String,
    val issues: List<String>,
) {
    companion object {
        fun from(value: AdminExternalControlEvidenceSummary) =
            AdminExternalControlEvidenceData(
                evidenceId = value.evidenceId,
                network = value.network,
                contractVersionId = value.contractVersionId,
                status = value.status.name,
                completionReady = value.completionReady,
                snapshotHash = value.snapshotHash,
                tapSourceId = value.tapSourceId,
                tapBlocked = value.tapBlocked,
                pinnedBlockNumber = value.pinnedBlockNumber.toString(),
                expectedOperatorSetHash = value.expectedOperatorSetHash,
                firstEndpointId = value.firstEndpointId,
                firstPaused = value.firstPaused,
                firstOperatorSetHash = value.firstOperatorSetHash,
                secondEndpointId = value.secondEndpointId,
                secondPaused = value.secondPaused,
                secondOperatorSetHash = value.secondOperatorSetHash,
                observedAt = value.observedAt.toString(),
                validUntil = value.validUntil.toString(),
                reason = value.reason,
                workTicket = value.workTicket,
                actorEmployeeNo = value.actorEmployeeNo,
                issues = value.issues,
            )
    }
}

data class AdminExecutionGateData(
    val network: String,
    val type: String,
    val state: String,
    val stoppedAt: String?,
    val reason: String?,
    val workTicket: String?,
    val actorEmployeeNo: String?,
    val sequence: Int?,
    val newExecutionAllowed: Boolean,
    val existingExecutionRecoveryAllowed: Boolean,
    val emergencyRevocationAllowed: Boolean,
    val disabledReasons: List<String>,
) {
    companion object {
        fun from(value: AdminExecutionGateSummary) =
            AdminExecutionGateData(
                network = value.network,
                type = value.type.name,
                state = value.state.name,
                stoppedAt = value.stoppedAt?.toString(),
                reason = value.reason,
                workTicket = value.workTicket,
                actorEmployeeNo = value.actorEmployeeNo,
                sequence = value.sequence,
                newExecutionAllowed = value.newExecutionAllowed,
                existingExecutionRecoveryAllowed = value.existingExecutionRecoveryAllowed,
                emergencyRevocationAllowed = value.emergencyRevocationAllowed,
                disabledReasons = value.disabledReasons,
            )
    }
}

data class AdminContractData(
    val versionId: String,
    val scopeId: String,
    val network: String,
    val use: String,
    val version: String,
    val address: String,
    val state: String,
    val runtimeCodeHash: String,
    val evidenceStatus: String?,
    val evidenceValidUntil: String?,
    val active: Boolean,
) {
    companion object {
        fun from(value: AdminContractSummary) =
            AdminContractData(
                value.versionId,
                value.scopeId,
                value.network,
                value.use,
                value.version,
                value.address,
                value.state,
                value.runtimeCodeHash,
                value.evidenceStatus,
                value.evidenceValidUntil?.toString(),
                value.active,
            )
    }
}

data class AdminPolicyData(
    val versionId: String,
    val scopeId: String,
    val versionNumber: Int,
    val schemaVersion: String,
    val state: String,
    val policyHash: String,
    val ceilingPassed: Boolean,
    val active: Boolean,
    val registeredAt: String,
) {
    companion object {
        fun from(value: AdminPolicySummary) =
            AdminPolicyData(
                value.versionId,
                value.scopeId,
                value.versionNumber,
                value.schemaVersion,
                value.state,
                value.policyHash,
                value.ceilingPassed,
                value.active,
                value.registeredAt.toString(),
            )
    }
}

data class AdminBandSData(
    val proposalId: String,
    val sourceProposalId: String,
    val snapshotId: String,
    val sourceRequestId: String,
    val policyVersionId: String,
    val snapshotHash: String,
    val inputHash: String,
    val observedAt: String,
    val expiresAt: String,
    val inputComplete: Boolean,
    val issueCodes: List<String>,
    val totalAssetKrwAmount: String,
    val observedHotKrwAmount: String,
    val observedColdKrwAmount: String,
    val effectiveHotKrwAmount: String,
    val hotRatio: String,
    val lowerRatio: String,
    val targetRatio: String,
    val upperRatio: String,
    val direction: String,
    val proposalHash: String,
    val totalKrwAmount: String,
    val afterHotRatio: String,
    val state: String,
    val requestId: String?,
    val requestState: String?,
    val approvalCount: Int,
    val requiredApprovals: Int,
    val executionId: String?,
    val executionStatus: String?,
    val reservedAt: String?,
    val executionReady: Boolean,
    val disabledReasons: List<String>,
    val items: List<AdminBandSItemData>,
) {
    companion object {
        fun from(value: AdminBandSSummary) =
            AdminBandSData(
                proposalId = value.proposalId,
                sourceProposalId = value.sourceProposalId,
                snapshotId = value.snapshotId,
                sourceRequestId = value.sourceRequestId,
                policyVersionId = value.policyVersionId,
                snapshotHash = value.snapshotHash,
                inputHash = value.inputHash,
                observedAt = value.observedAt.toString(),
                expiresAt = value.expiresAt.toString(),
                inputComplete = value.inputComplete,
                issueCodes = value.issueCodes,
                totalAssetKrwAmount = value.totalAssetKrwAmount.toPlainString(),
                observedHotKrwAmount = value.observedHotKrwAmount.toPlainString(),
                observedColdKrwAmount = value.observedColdKrwAmount.toPlainString(),
                effectiveHotKrwAmount = value.effectiveHotKrwAmount.toPlainString(),
                hotRatio = value.hotRatio.toPlainString(),
                lowerRatio = value.lowerRatio.toPlainString(),
                targetRatio = value.targetRatio.toPlainString(),
                upperRatio = value.upperRatio.toPlainString(),
                direction = value.direction.name,
                proposalHash = value.proposalHash,
                totalKrwAmount = value.totalKrwAmount.toPlainString(),
                afterHotRatio = value.afterHotRatio.toPlainString(),
                state = value.state,
                requestId = value.requestId,
                requestState = value.requestState,
                approvalCount = value.approvalCount,
                requiredApprovals = value.requiredApprovals,
                executionId = value.executionId,
                executionStatus = value.executionStatus?.name,
                reservedAt = value.reservedAt?.toString(),
                executionReady = value.executionReady,
                disabledReasons = value.disabledReasons,
                items = value.items.map(AdminBandSItemData::from),
            )
    }
}

data class AdminBandSItemData(
    val sequence: Int,
    val dependsOnSequence: Int?,
    val legType: String,
    val network: String,
    val tokenSymbol: String,
    val sourceVaultId: String?,
    val destinationVaultId: String?,
    val destinationAddress: String?,
    val amount: String,
    val krwAmount: String,
    val expectedFeeAmount: String,
    val itemHash: String,
    val executable: Boolean,
    val blockReason: String?,
    val executionStatus: String?,
) {
    companion object {
        fun from(value: AdminBandSItemSummary) =
            AdminBandSItemData(
                value.sequence,
                value.dependsOnSequence,
                value.legType.name,
                value.network,
                value.tokenSymbol,
                value.sourceVaultId,
                value.destinationVaultId,
                value.destinationAddress,
                value.amount.toPlainString(),
                value.krwAmount.toPlainString(),
                value.expectedFeeAmount.toPlainString(),
                value.itemHash,
                value.executable,
                value.blockReason,
                value.executionStatus?.name,
            )
    }
}

data class AdminChangeRequestData(
    val requestId: String,
    val targetType: String,
    val scopeId: String,
    val targetVersionId: String,
    val state: String,
    val risk: String,
    val snapshotHash: String,
    val diff: String,
    val impact: String,
    val reason: String,
    val workTicket: String,
    val requesterEmployeeNo: String,
    val requestedAt: String,
    val expiresAt: String,
    val requiredApprovals: Int,
    val approvalCount: Int,
    val securityApprovalRequired: Boolean,
    val securityApprovalCount: Int,
    val activationReady: Boolean,
    val disabledReasons: List<String>,
    val decisions: List<AdminChangeDecisionData>,
) {
    companion object {
        fun from(value: AdminChangeRequestDetail): AdminChangeRequestData {
            val request = value.request
            return AdminChangeRequestData(
                requestId = request.lifecycle.requestId,
                targetType = request.targetType.name,
                scopeId = request.scopeId,
                targetVersionId = request.lifecycle.targetVersionId,
                state = value.state,
                risk = request.lifecycle.risk.name,
                snapshotHash = request.lifecycle.targetSnapshotHash,
                diff = request.diffPayload,
                impact = request.impactPayload,
                reason = request.reason,
                workTicket = request.workTicket,
                requesterEmployeeNo = request.lifecycle.requester.employeeNo,
                requestedAt = request.requestedAt.toString(),
                expiresAt = request.lifecycle.expiresAt.toString(),
                requiredApprovals = value.requiredApprovals,
                approvalCount = value.approvalCount,
                securityApprovalRequired = value.securityApprovalRequired,
                securityApprovalCount = value.securityApprovalCount,
                activationReady = value.activationReady,
                disabledReasons = value.disabledReasons,
                decisions = value.decisions.map(AdminChangeDecisionData::from),
            )
        }
    }
}

data class AdminChangeDecisionData(
    val employeeNo: String,
    val role: String,
    val decision: String,
    val opinion: String?,
    val decidedAt: String,
) {
    companion object {
        fun from(value: AdminDecisionRecord): AdminChangeDecisionData {
            val decision = value.decision
            return AdminChangeDecisionData(
                employeeNo = decision.actor.employeeNo,
                role =
                    decision.actor.roles
                        .single()
                        .name,
                decision = decision.decision.name,
                opinion = value.opinion,
                decidedAt = decision.decidedAt.toString(),
            )
        }
    }
}
