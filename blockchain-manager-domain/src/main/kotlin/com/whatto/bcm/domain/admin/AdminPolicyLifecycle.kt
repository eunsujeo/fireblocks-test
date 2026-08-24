package com.whatto.bcm.domain.admin

import java.time.Instant

enum class AdminRole {
    BCM_VIEWER,
    BCM_OPERATOR,
    BCM_APPROVER,
    BCM_SECURITY_APPROVER,
    BCM_AUDITOR,
}

data class AdminActor(
    val employeeNo: String,
    val branchCode: String,
    val roles: Set<AdminRole>,
)

enum class ChangeRisk(
    val requiredApprovals: Int,
    val securityApproverRequired: Boolean,
) {
    GENERAL(1, false),
    FUND(1, false),
    SECURITY(2, true),
    RESUME(2, true),
}

enum class ChangeDecision {
    APPROVE,
    REJECT,
}

data class PolicyChangeRequest(
    val requestId: String,
    val requester: AdminActor,
    val risk: ChangeRisk,
    val targetSnapshotHash: String,
    val baseBindingRevision: Long,
    val targetVersionId: String,
    val expiresAt: Instant,
)

data class PolicyDecision(
    val requestId: String,
    val actor: AdminActor,
    val decision: ChangeDecision,
    val snapshotHash: String,
    val decidedAt: Instant,
)

data class ActivationPermission(
    val requestId: String,
    val targetVersionId: String,
    val targetSnapshotHash: String,
    val baseBindingRevision: Long,
    val requiredApprovals: Int,
)

sealed class PolicyChangeException(
    message: String,
) : RuntimeException(message)

class SelfApprovalNotAllowed(
    requestId: String,
) : PolicyChangeException("requester cannot approve request $requestId")

class ApproverRoleRequired(
    employeeNo: String,
) : PolicyChangeException("actor $employeeNo does not have an approver role")

class ConflictingDecision(
    requestId: String,
    employeeNo: String,
) : PolicyChangeException("actor $employeeNo already decided request $requestId")

class StaleChangeSnapshot(
    requestId: String,
) : PolicyChangeException("request $requestId snapshot is stale")

class ChangeRequestExpired(
    requestId: String,
) : PolicyChangeException("request $requestId is expired")

class ChangeRequestRejected(
    requestId: String,
) : PolicyChangeException("request $requestId is rejected")

class ApprovalQuorumNotSatisfied(
    requestId: String,
) : PolicyChangeException("request $requestId approval quorum is not satisfied")

class SecurityApproverRequired(
    requestId: String,
) : PolicyChangeException("request $requestId requires a security approver")

class ChangeTargetNotReady(
    requestId: String,
) : PolicyChangeException("request $requestId target verification is not ready")

object AdminPolicyLifecycle {
    fun decide(
        request: PolicyChangeRequest,
        existingDecisions: List<PolicyDecision>,
        actor: AdminActor,
        decision: ChangeDecision,
        snapshotHash: String,
        now: Instant,
    ): PolicyDecision {
        if (actor.employeeNo == request.requester.employeeNo) {
            throw SelfApprovalNotAllowed(request.requestId)
        }
        if (actor.roles.none { it == AdminRole.BCM_APPROVER || it == AdminRole.BCM_SECURITY_APPROVER }) {
            throw ApproverRoleRequired(actor.employeeNo)
        }
        if (snapshotHash != request.targetSnapshotHash) {
            throw StaleChangeSnapshot(request.requestId)
        }
        if (!now.isBefore(request.expiresAt)) {
            throw ChangeRequestExpired(request.requestId)
        }
        existingDecisions.firstOrNull { it.actor.employeeNo == actor.employeeNo }?.let { existing ->
            if (existing.decision == decision && existing.snapshotHash == snapshotHash) return existing
            throw ConflictingDecision(request.requestId, actor.employeeNo)
        }
        return PolicyDecision(request.requestId, actor, decision, snapshotHash, now)
    }

    fun validateActivation(
        request: PolicyChangeRequest,
        decisions: List<PolicyDecision>,
        currentBindingRevision: Long,
        currentSnapshotHash: String,
        targetReady: Boolean,
        now: Instant,
    ): ActivationPermission {
        val requestDecisions = decisions.filter { it.requestId == request.requestId }
        if (requestDecisions.any { it.decision == ChangeDecision.REJECT }) {
            throw ChangeRequestRejected(request.requestId)
        }
        if (!now.isBefore(request.expiresAt)) {
            throw ChangeRequestExpired(request.requestId)
        }
        if (currentBindingRevision != request.baseBindingRevision || currentSnapshotHash != request.targetSnapshotHash) {
            throw StaleChangeSnapshot(request.requestId)
        }
        if (!targetReady) {
            throw ChangeTargetNotReady(request.requestId)
        }
        val approvals =
            requestDecisions
                .filter { it.decision == ChangeDecision.APPROVE && it.snapshotHash == request.targetSnapshotHash }
                .distinctBy { it.actor.employeeNo }
        if (approvals.size < request.risk.requiredApprovals) {
            throw ApprovalQuorumNotSatisfied(request.requestId)
        }
        if (request.risk.securityApproverRequired && approvals.none { AdminRole.BCM_SECURITY_APPROVER in it.actor.roles }) {
            throw SecurityApproverRequired(request.requestId)
        }
        return ActivationPermission(
            request.requestId,
            request.targetVersionId,
            request.targetSnapshotHash,
            request.baseBindingRevision,
            request.risk.requiredApprovals,
        )
    }
}
