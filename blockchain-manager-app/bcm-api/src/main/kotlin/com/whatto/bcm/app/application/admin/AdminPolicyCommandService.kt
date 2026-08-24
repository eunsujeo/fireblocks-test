package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActivationRecord
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminDecisionRecord
import com.whatto.bcm.domain.admin.AdminPolicyLifecycle
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminPolicyVersion
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.admin.PolicyChangeRequest
import com.whatto.bcm.domain.admin.SweepExecutionPolicy
import com.whatto.bcm.domain.admin.SweepPolicyCeilingEvaluator
import com.whatto.bcm.domain.admin.SweepPolicyHardCeiling
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class AdminPolicyCommandService(
    private val policies: AdminPolicyRepository,
    private val transactions: TransactionRunner,
    private val ids: EventIdGenerator,
    private val clock: Clock,
    private val hardCeiling: SweepPolicyHardCeiling,
) {
    fun registerPolicy(command: RegisterSweepPolicyCommand): AdminPolicyVersion =
        transactions.run {
            requireOperator(command.actor)
            val latest = policies.findLatestPolicyVersion(command.scopeId)
            val evaluation = SweepPolicyCeilingEvaluator.evaluate(command.policy, hardCeiling)
            val payload = command.policy.canonicalJson()
            val ceilingSnapshot = hardCeiling.canonicalJson()
            val version =
                AdminPolicyVersion(
                    versionId = ids.nextId(),
                    scopeId = command.scopeId,
                    versionNumber = (latest?.versionNumber ?: 0) + 1,
                    schemaVersion = POLICY_SCHEMA_VERSION,
                    baseVersionId = latest?.versionId,
                    contractVersionId = command.contractVersionId,
                    payload = payload,
                    policyHash = sha256(payload),
                    ceilingSnapshot = ceilingSnapshot,
                    ceilingHash = sha256(ceilingSnapshot),
                    ceilingPassed = evaluation.passed,
                    registeredAt = now(),
                    registeredBy = command.actor,
                )
            policies.insertPolicyVersion(version).also {
                policies.initializePolicyBinding(command.scopeId, command.actor, version.registeredAt)
            }
        }

    fun requestChange(command: RequestPolicyChangeCommand): AdminChangeRequest =
        transactions.run {
            requireOperator(command.actor)
            val target =
                policies.findPolicyVersion(command.targetVersionId)
                    ?: throw ResourceNotFoundException("policyVersion", command.targetVersionId)
            val binding =
                policies.lockPolicyBinding(target.scopeId)
                    ?: throw ResourceNotFoundException("policyBinding", target.scopeId)
            val before = binding.activeVersionId?.let(policies::findPolicyVersion)
            val diffPayload = policyDiff(before, target)
            val impactPayload = command.impact.canonicalJson()
            val diffHash = sha256(diffPayload)
            val impactHash = sha256(impactPayload)
            val snapshotHash =
                sha256(
                    listOf(
                        target.scopeId,
                        binding.revision.toString(),
                        binding.activeVersionId.orEmpty(),
                        target.versionId,
                        diffHash,
                        impactHash,
                    ).joinToString("|"),
                )
            val requestedAt = now()
            val candidate =
                AdminChangeRequest(
                    lifecycle =
                        PolicyChangeRequest(
                            requestId = ids.nextId(),
                            requester = command.actor,
                            risk = command.risk,
                            targetSnapshotHash = snapshotHash,
                            baseBindingRevision = binding.revision,
                            targetVersionId = target.versionId,
                            expiresAt = requestedAt.plus(command.validFor),
                        ),
                    targetType = ChangeTargetType.POLICY,
                    scopeId = target.scopeId,
                    beforeVersionId = binding.activeVersionId,
                    evidenceId = null,
                    diffPayload = diffPayload,
                    diffHash = diffHash,
                    impactPayload = impactPayload,
                    impactHash = impactHash,
                    reason = command.reason,
                    workTicket = command.workTicket,
                    idempotencyKey = command.idempotencyKey,
                    requestedRole = AdminRole.BCM_OPERATOR,
                    requestedAt = requestedAt,
                )
            policies.findChangeRequestByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
                if (existing.sameRequestAs(candidate)) return@run existing
                throw ConflictException("policyChangeIdempotency", command.idempotencyKey)
            }
            policies.insertChangeRequest(candidate)
        }

    fun decide(command: DecidePolicyChangeCommand): AdminDecisionRecord =
        transactions.run {
            val request =
                policies.findChangeRequest(command.requestId)
                    ?: throw ResourceNotFoundException("policyChangeRequest", command.requestId)
            val existing = policies.findDecisions(command.requestId)
            val decision =
                AdminPolicyLifecycle.decide(
                    request.lifecycle,
                    existing.map(AdminDecisionRecord::decision),
                    command.actor,
                    command.decision,
                    command.snapshotHash,
                    now(),
                )
            existing.firstOrNull { it.decision === decision }
                ?: policies.insertDecision(AdminDecisionRecord(decision, command.opinion))
        }

    fun activate(command: ActivatePolicyChangeCommand): AdminActivationRecord =
        transactions.run {
            policies.findSuccessfulActivation(command.requestId, command.idempotencyKey)?.let { return@run it }
            val request =
                policies.findChangeRequest(command.requestId)
                    ?: throw ResourceNotFoundException("policyChangeRequest", command.requestId)
            check(request.targetType == ChangeTargetType.POLICY) { "request ${command.requestId} is not a policy change" }
            val binding =
                policies.lockPolicyBinding(request.scopeId)
                    ?: throw ResourceNotFoundException("policyBinding", request.scopeId)
            val now = now()
            AdminPolicyLifecycle.validateActivation(
                request.lifecycle,
                policies.findDecisions(request.lifecycle.requestId).map(AdminDecisionRecord::decision),
                binding.revision,
                request.lifecycle.targetSnapshotHash,
                policies.isPolicyReady(request.lifecycle.targetVersionId),
                now,
            )
            val expectedState = binding.canonicalJson()
            val requestHash = sha256("${request.lifecycle.requestId}|${command.idempotencyKey}|${request.lifecycle.targetSnapshotHash}")
            val correlationId = ids.nextId()
            policies.insertActivationIntent(
                actionId = ids.nextId(),
                correlationId = correlationId,
                request = request,
                idempotencyKey = command.idempotencyKey,
                requestHash = requestHash,
                expectedState = expectedState,
                expectedStateHash = sha256(expectedState),
                actor = command.actor,
                now = now,
            )
            val activated = policies.activatePolicyBinding(request, command.actor, now)
            val observedState = activated.canonicalJson()
            policies.insertActivationSuccess(
                actionId = ids.nextId(),
                correlationId = correlationId,
                request = request,
                idempotencyKey = command.idempotencyKey,
                requestHash = requestHash,
                expectedState = expectedState,
                expectedStateHash = sha256(expectedState),
                observedState = observedState,
                observedStateHash = sha256(observedState),
                actor = command.actor,
                now = now,
            )
        }

    private fun requireOperator(actor: AdminActor) {
        check(AdminRole.BCM_OPERATOR in actor.roles) { "BCM_OPERATOR role is required" }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun policyDiff(
        before: AdminPolicyVersion?,
        after: AdminPolicyVersion,
    ): String = "{\"after\":${after.payload},\"before\":${before?.payload ?: "null"}}"

    private fun AdminChangeRequest.sameRequestAs(other: AdminChangeRequest): Boolean =
        targetType == other.targetType &&
            scopeId == other.scopeId &&
            lifecycle.targetVersionId == other.lifecycle.targetVersionId &&
            lifecycle.risk == other.lifecycle.risk &&
            lifecycle.baseBindingRevision == other.lifecycle.baseBindingRevision &&
            diffHash == other.diffHash &&
            impactHash == other.impactHash &&
            reason == other.reason &&
            workTicket == other.workTicket

    private fun SweepExecutionPolicy.canonicalJson(): String =
        """{"allowanceCap":${allowanceCap.canonical()},"batchAmountCap":${batchAmountCap.canonical()},"batchSize":$batchSize,"boostAttempts":$boostAttempts,"enabled":$enabled,"itemAmountCap":${itemAmountCap.canonical()},"minimumAmount":${minimumAmount.canonical()}}"""

    private fun SweepPolicyHardCeiling.canonicalJson(): String =
        """{"executionEnabled":$executionEnabled,"maximumAllowance":${maximumAllowance.canonical()},"maximumBatchAmount":${maximumBatchAmount.canonical()},"maximumBatchSize":$maximumBatchSize,"maximumBoostAttempts":$maximumBoostAttempts,"maximumItemAmount":${maximumItemAmount.canonical()}}"""

    private fun PolicyImpactSnapshot.canonicalJson(): String =
        """{"affectedAccounts":$affectedAccounts,"externalDriftFree":$externalDriftFree,"openExecutions":$openExecutions}"""

    private fun com.whatto.bcm.domain.admin.AdminPolicyBinding.canonicalJson(): String =
        """{"activeVersionId":${activeVersionId?.let {
            "\"$it\""
        } ?: "null"},"revision":$revision,"scopeId":"$scopeId","snapshotHash":"$snapshotHash"}"""

    private fun java.math.BigDecimal.canonical(): String = stripTrailingZeros().toPlainString()

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val POLICY_SCHEMA_VERSION = "v1"
    }
}

data class RegisterSweepPolicyCommand(
    val scopeId: String,
    val policy: SweepExecutionPolicy,
    val contractVersionId: String?,
    val actor: AdminActor,
)

data class PolicyImpactSnapshot(
    val affectedAccounts: Int,
    val openExecutions: Int,
    val externalDriftFree: Boolean,
)

data class RequestPolicyChangeCommand(
    val targetVersionId: String,
    val risk: ChangeRisk,
    val impact: PolicyImpactSnapshot,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val actor: AdminActor,
    val validFor: Duration = Duration.ofHours(3),
)

data class DecidePolicyChangeCommand(
    val requestId: String,
    val decision: ChangeDecision,
    val snapshotHash: String,
    val opinion: String?,
    val actor: AdminActor,
)

data class ActivatePolicyChangeCommand(
    val requestId: String,
    val idempotencyKey: String,
    val actor: AdminActor,
)
