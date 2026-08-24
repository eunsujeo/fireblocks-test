package com.whatto.bcm.domain.admin

import java.time.Instant

enum class ChangeTargetType {
    POLICY,
    CONTRACT,
    BAND_S,
    ALLOWANCE_REVOKE,
    EXECUTION_GATE,
}

data class AdminPolicyVersion(
    val versionId: String,
    val scopeId: String,
    val versionNumber: Int,
    val schemaVersion: String,
    val baseVersionId: String?,
    val contractVersionId: String?,
    val payload: String,
    val policyHash: String,
    val ceilingSnapshot: String,
    val ceilingHash: String,
    val ceilingPassed: Boolean,
    val registeredAt: Instant,
    val registeredBy: AdminActor,
)

data class AdminChangeRequest(
    val lifecycle: PolicyChangeRequest,
    val targetType: ChangeTargetType,
    val scopeId: String,
    val beforeVersionId: String?,
    val evidenceId: String?,
    val diffPayload: String,
    val diffHash: String,
    val impactPayload: String,
    val impactHash: String,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val requestedRole: AdminRole,
    val requestedAt: Instant,
)

data class AdminPolicyBinding(
    val scopeId: String,
    val activeVersionId: String?,
    val revision: Long,
    val snapshotHash: String,
)

data class AdminDecisionRecord(
    val decision: PolicyDecision,
    val opinion: String?,
)

data class AdminActivationRecord(
    val actionId: String,
    val correlationId: String,
    val requestId: String,
    val idempotencyKey: String,
    val targetVersionId: String,
    val bindingRevision: Long,
    val snapshotHash: String,
    val activatedAt: Instant,
)

interface AdminPolicyRepository {
    fun insertPolicyVersion(version: AdminPolicyVersion): AdminPolicyVersion

    fun findPolicyVersion(versionId: String): AdminPolicyVersion?

    fun findLatestPolicyVersion(scopeId: String): AdminPolicyVersion?

    fun initializePolicyBinding(
        scopeId: String,
        actor: AdminActor,
        now: Instant,
    ): AdminPolicyBinding

    fun insertChangeRequest(request: AdminChangeRequest): AdminChangeRequest

    fun findChangeRequest(requestId: String): AdminChangeRequest?

    fun findChangeRequestByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): AdminChangeRequest?

    fun findDecisions(requestId: String): List<AdminDecisionRecord>

    fun insertDecision(decision: AdminDecisionRecord): AdminDecisionRecord

    fun lockPolicyBinding(scopeId: String): AdminPolicyBinding?

    fun isPolicyReady(versionId: String): Boolean

    fun findSuccessfulActivation(
        requestId: String,
        idempotencyKey: String,
    ): AdminActivationRecord?

    fun insertActivationIntent(
        actionId: String,
        correlationId: String,
        request: AdminChangeRequest,
        idempotencyKey: String,
        requestHash: String,
        expectedState: String,
        expectedStateHash: String,
        actor: AdminActor,
        now: Instant,
    )

    fun activatePolicyBinding(
        request: AdminChangeRequest,
        actor: AdminActor,
        now: Instant,
    ): AdminPolicyBinding

    fun insertActivationSuccess(
        actionId: String,
        correlationId: String,
        request: AdminChangeRequest,
        idempotencyKey: String,
        requestHash: String,
        expectedState: String,
        expectedStateHash: String,
        observedState: String,
        observedStateHash: String,
        actor: AdminActor,
        now: Instant,
    ): AdminActivationRecord
}
