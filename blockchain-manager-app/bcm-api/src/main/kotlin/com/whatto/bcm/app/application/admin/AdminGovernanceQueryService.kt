package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.AdminBandSSummary
import com.whatto.bcm.domain.admin.AdminChangeRequestDetail
import com.whatto.bcm.domain.admin.AdminContractSummary
import com.whatto.bcm.domain.admin.AdminExecutionGateOverview
import com.whatto.bcm.domain.admin.AdminExecutionGateSummary
import com.whatto.bcm.domain.admin.AdminGovernanceQueryRepository
import com.whatto.bcm.domain.admin.AdminPolicySummary
import com.whatto.bcm.domain.admin.AdminRuntimeReadiness
import com.whatto.bcm.domain.admin.ExecutionGatePolicy
import com.whatto.bcm.domain.admin.toAdminSummary
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

@Service
class AdminGovernanceQueryService(
    private val governance: AdminGovernanceQueryRepository,
    private val clock: Clock,
    private val release: AdminSweepReleaseProperties = AdminSweepReleaseProperties(),
    private val hardCeiling: AdminPolicyHardCeilingProperties = AdminPolicyHardCeilingProperties(),
) {
    fun contracts(): List<AdminContractSummary> = governance.findContracts(clock.instant())

    fun policies(): List<AdminPolicySummary> = governance.findPolicies(clock.instant())

    fun changeRequest(requestId: String): AdminChangeRequestDetail =
        governance.findChangeRequest(requestId, clock.instant())
            ?: throw ResourceNotFoundException("policyChangeRequest", requestId)

    fun bandS(): List<AdminBandSSummary> = governance.findBandS(clock.instant())

    fun runtimeReadiness(): AdminRuntimeReadiness =
        AdminRuntimeReadiness(
            observedAt = clock.instant(),
            webhook = governance.findWebhookRuntimeObservation(),
        )

    fun executionGates(): AdminExecutionGateOverview {
        val observedAt = clock.instant()
        val scopes = governance.findExecutionGateScopes(observedAt, hardCeiling.toDomain(), MAX_EXECUTION_GATES + 1)
        val externalControls = governance.findLatestExternalControls(MAX_EXTERNAL_CONTROLS + 1)
        val allowanceRevocations = governance.findAllowanceRevocations(MAX_ALLOWANCE_REVOCATIONS + 1)
        val webhookRecoveries = governance.findWebhookRecoveries(MAX_WEBHOOK_RECOVERIES + 1)
        val resumes = governance.findExecutionGateResumes(observedAt, MAX_EXECUTION_GATE_RESUMES + 1)
        return AdminExecutionGateOverview(
            observedAt = observedAt,
            truncated =
                scopes.size > MAX_EXECUTION_GATES ||
                    externalControls.size > MAX_EXTERNAL_CONTROLS ||
                    allowanceRevocations.size > MAX_ALLOWANCE_REVOCATIONS ||
                    webhookRecoveries.size > MAX_WEBHOOK_RECOVERIES ||
                    resumes.size > MAX_EXECUTION_GATE_RESUMES,
            gates =
                scopes.take(MAX_EXECUTION_GATES).map { scope ->
                    val current = scope.current
                    val releaseReady = releaseReady(scope)
                    val availability =
                        ExecutionGatePolicy.availability(
                            type = scope.type,
                            current = current,
                            releaseReady = releaseReady,
                            emergencyRevocationReady = release.emergencyRevocationReady(scope.network),
                        )
                    AdminExecutionGateSummary(
                        network = scope.network,
                        type = scope.type,
                        state = availability.state,
                        stoppedAt = current?.takeIf { it.status.name == "STOPPED" }?.occurredAt,
                        reason = current?.takeIf { it.status.name == "STOPPED" }?.reason,
                        workTicket = current?.takeIf { it.status.name == "STOPPED" }?.workTicket,
                        actorEmployeeNo = current?.takeIf { it.status.name == "STOPPED" }?.actor?.employeeNo,
                        sequence = current?.sequence,
                        newExecutionAllowed = availability.newExecutionAllowed,
                        existingExecutionRecoveryAllowed = availability.existingExecutionRecoveryAllowed,
                        emergencyRevocationAllowed = availability.emergencyRevocationAllowed,
                        disabledReasons = availability.disabledReasons,
                    )
                },
            externalControls = externalControls.take(MAX_EXTERNAL_CONTROLS).map { it.asOf(observedAt) },
            allowanceRevocations = allowanceRevocations.take(MAX_ALLOWANCE_REVOCATIONS),
            webhookRecoveries =
                webhookRecoveries
                    .take(MAX_WEBHOOK_RECOVERIES)
                    .map { it.toAdminSummary(observedAt, WEBHOOK_INTENT_TIMEOUT) },
            resumes = resumes.take(MAX_EXECUTION_GATE_RESUMES),
        )
    }

    private fun releaseReady(scope: com.whatto.bcm.domain.admin.AdminExecutionGateScope): Boolean =
        when (scope.type) {
            com.whatto.bcm.domain.admin.ExecutionGateType.WITHDRAWAL -> true
            com.whatto.bcm.domain.admin.ExecutionGateType.SWEEP ->
                scope.releaseContextReady &&
                    release.batchSubmissionReady(scope.network)

            com.whatto.bcm.domain.admin.ExecutionGateType.APPROVE ->
                scope.releaseContextReady &&
                    release.normalApprovalReady(scope.network)
        }

    private fun AdminPolicyHardCeilingProperties.toDomain() =
        com.whatto.bcm.domain.admin.SweepPolicyHardCeiling(
            executionEnabled,
            maximumBatchSize,
            maximumAllowance,
            maximumItemAmount,
            maximumBatchAmount,
            maximumBoostAttempts,
        )

    private companion object {
        const val MAX_EXECUTION_GATES = 300
        const val MAX_EXTERNAL_CONTROLS = 100
        const val MAX_ALLOWANCE_REVOCATIONS = 100
        const val MAX_WEBHOOK_RECOVERIES = 100
        const val MAX_EXECUTION_GATE_RESUMES = 100
        val WEBHOOK_INTENT_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
