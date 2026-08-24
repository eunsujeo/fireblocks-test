package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.AdminBandSSummary
import com.whatto.bcm.domain.admin.AdminChangeRequestDetail
import com.whatto.bcm.domain.admin.AdminContractSummary
import com.whatto.bcm.domain.admin.AdminExecutionGateOverview
import com.whatto.bcm.domain.admin.AdminExecutionGateSummary
import com.whatto.bcm.domain.admin.AdminGovernanceQueryRepository
import com.whatto.bcm.domain.admin.AdminPolicySummary
import com.whatto.bcm.domain.admin.AdminRuntimeReadiness
import com.whatto.bcm.domain.admin.AdminSweepRuntimeReadiness
import com.whatto.bcm.domain.admin.ExecutionGatePolicy
import com.whatto.bcm.domain.admin.toAdminSummary
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.job.OperationalJobNames
import com.whatto.bcm.domain.job.RuntimeAttestation
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@Service
class AdminGovernanceQueryService(
    private val governance: AdminGovernanceQueryRepository,
    private val clock: Clock,
    private val jobs: JobStateRepository,
    private val release: AdminSweepReleaseProperties = AdminSweepReleaseProperties(),
    private val hardCeiling: AdminPolicyHardCeilingProperties = AdminPolicyHardCeilingProperties(),
) {
    fun contracts(): List<AdminContractSummary> = governance.findContracts(clock.instant())

    fun policies(): List<AdminPolicySummary> = governance.findPolicies(clock.instant())

    fun changeRequest(requestId: String): AdminChangeRequestDetail =
        governance.findChangeRequest(requestId, clock.instant())
            ?: throw ResourceNotFoundException("policyChangeRequest", requestId)

    fun bandS(): List<AdminBandSSummary> = governance.findBandS(clock.instant())

    fun runtimeReadiness(): AdminRuntimeReadiness {
        val observedAt = clock.instant()
        val contracts = governance.findContracts(observedAt)
        val policies = governance.findPolicies(observedAt)
        val activeContracts = contracts.filter { it.active && it.use == "SWEEP" }
        val activePolicies = policies.filter { it.active && it.contractVersionId != null }
        val validContracts =
            activeContracts.filter {
                it.evidenceStatus == "VALID" && it.evidenceValidUntil?.isAfter(observedAt) == true
            }
        val validContractIds = validContracts.mapTo(mutableSetOf()) { it.versionId }
        val eligiblePolicies = activePolicies.filter { it.ceilingPassed && it.contractVersionId in validContractIds }
        val eligibleContractIds = eligiblePolicies.mapTo(mutableSetOf()) { it.contractVersionId }
        val executor = jobs.find(OperationalJobNames.SWEEP_BATCH_EXECUTION)
        val executorLastRunAt = executor?.lastRunAt.toInstantOrNull()
        val executorLastSucceededAt = executor?.lastSucceededAt.toInstantOrNull()
        val attestationJobName =
            RuntimeAttestation.jobName(
                SWEEP_ATTESTATION_PREFIX,
                governance.findSweepRuntimeAttestationEntries(observedAt),
            )
        val attestation = jobs.find(attestationJobName)
        val attestationLastRunAt = attestation?.lastRunAt.toInstantOrNull()
        val attestationLastSucceededAt = attestation?.lastSucceededAt.toInstantOrNull()
        val disabledReasons =
            buildList {
                if (executorLastRunAt == null) {
                    add("SWEEP_EXECUTOR_NOT_OBSERVED")
                } else if (!executorLastRunAt.isFresh(observedAt)) {
                    add("SWEEP_EXECUTOR_HEARTBEAT_STALE")
                }
                if (executorLastSucceededAt == null || executorLastRunAt?.let { executorLastSucceededAt.isBefore(it) } == true) {
                    add("SWEEP_EXECUTOR_LAST_RUN_NOT_SUCCEEDED")
                } else if (!executorLastSucceededAt.isFresh(observedAt)) {
                    add("SWEEP_EXECUTOR_SUCCESS_STALE")
                }
                if (attestationLastRunAt == null) {
                    add("SWEEP_RUNTIME_NOT_ATTESTED")
                } else if (!attestationLastRunAt.isFresh(observedAt)) {
                    add("SWEEP_RUNTIME_ATTESTATION_STALE")
                }
                if (attestationLastSucceededAt == null ||
                    attestationLastRunAt?.let { attestationLastSucceededAt.isBefore(it) } == true ||
                    executorLastRunAt?.let { attestationLastSucceededAt.isBefore(it) } == true
                ) {
                    add("SWEEP_RUNTIME_ATTESTATION_FAILED")
                } else if (!attestationLastSucceededAt.isFresh(observedAt)) {
                    add("SWEEP_RUNTIME_ATTESTATION_STALE")
                }
                if (activeContracts.isEmpty()) add("NO_ACTIVE_SWEEP_CONTRACT")
                if (activePolicies.isEmpty()) add("NO_ACTIVE_SWEEP_POLICY")
                if (activeContracts.any { it !in validContracts }) add("SWEEP_CONTRACT_EVIDENCE_NOT_VALID")
                if (activePolicies.any { !it.ceilingPassed }) add("SWEEP_POLICY_CEILING_NOT_PASSED")
                if (activePolicies.any { it.contractVersionId !in validContractIds } ||
                    validContracts.any { it.versionId !in eligibleContractIds }
                ) {
                    add("SWEEP_POLICY_CONTRACT_BINDING_MISMATCH")
                }
            }
        return AdminRuntimeReadiness(
            observedAt = observedAt,
            webhook = governance.findWebhookRuntimeObservation(),
            sweep =
                AdminSweepRuntimeReadiness(
                    enabled = disabledReasons.isEmpty(),
                    state = if (disabledReasons.isEmpty()) "READY" else "DISABLED",
                    activeContractCount = activeContracts.size,
                    activePolicyCount = activePolicies.size,
                    executorLastRunAt = executorLastRunAt,
                    executorLastSucceededAt = executorLastSucceededAt,
                    disabledReasons = disabledReasons,
                ),
        )
    }

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

    private fun String?.toInstantOrNull(): Instant? = this?.let { CoreDateTimes.parse(it).toInstant(ZoneOffset.UTC) }

    private fun Instant?.isFresh(observedAt: Instant): Boolean =
        this != null && !isAfter(observedAt) && !isBefore(observedAt.minus(SWEEP_EXECUTOR_HEARTBEAT_TTL))

    private companion object {
        const val MAX_EXECUTION_GATES = 300
        const val MAX_EXTERNAL_CONTROLS = 100
        const val MAX_ALLOWANCE_REVOCATIONS = 100
        const val MAX_WEBHOOK_RECOVERIES = 100
        const val MAX_EXECUTION_GATE_RESUMES = 100
        val WEBHOOK_INTENT_TIMEOUT: Duration = Duration.ofSeconds(60)
        val SWEEP_EXECUTOR_HEARTBEAT_TTL: Duration = Duration.ofMinutes(3)
        const val SWEEP_ATTESTATION_PREFIX = "sweep-attestation:"
    }
}
