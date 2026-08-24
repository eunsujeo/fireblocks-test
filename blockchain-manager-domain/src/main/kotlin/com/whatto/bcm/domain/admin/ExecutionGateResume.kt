package com.whatto.bcm.domain.admin

import java.time.Instant

data class ExecutionGateResumeSnapshot(
    val resumeId: String,
    val network: String,
    val type: ExecutionGateType,
    val stoppedEventId: String,
    val contractVersionId: String,
    val contractBindingRevision: Long,
    val contractEvidenceId: String,
    val revocationExecutionId: String,
    val expectedOperatorSetHash: String,
    val causeEvidenceUri: String,
    val causeEvidenceHash: String,
    val targetSnapshotHash: String,
    val registeredAt: Instant,
    val registeredBy: AdminActor,
)

data class ExecutionGateResumeCheckCandidate(
    val tapSourceId: String,
    val tap: TapBatchObservation?,
    val pinnedBlockNumber: java.math.BigInteger,
    val expectedOperatorSetHash: String,
    val firstEndpointId: String,
    val first: EmergencyContractObservation?,
    val secondEndpointId: String,
    val second: EmergencyContractObservation?,
    val sourceErrors: List<String>,
    val observedAt: Instant,
    val validUntil: Instant,
)

enum class ExecutionGateResumeCheckStatus {
    READY,
    DRIFT,
    STALE,
    UNCONFIRMED,
    ERROR,
}

data class ExecutionGateResumeCheckEvaluation(
    val status: ExecutionGateResumeCheckStatus,
    val issues: List<String>,
) {
    val resumeReady: Boolean = status == ExecutionGateResumeCheckStatus.READY
}

data class ExecutionGateResumeCheck(
    val checkId: String,
    val resumeId: String,
    val sequence: Int,
    val snapshotHash: String,
    val candidate: ExecutionGateResumeCheckCandidate,
    val evaluation: ExecutionGateResumeCheckEvaluation,
    val idempotencyKey: String,
    val registeredBy: AdminActor,
)

data class ExecutionGateResumeView(
    val snapshot: ExecutionGateResumeSnapshot,
    val request: AdminChangeRequest,
    val checks: List<ExecutionGateResumeCheck>,
)

object ExecutionGateResumeEvaluator {
    fun evaluate(
        candidate: ExecutionGateResumeCheckCandidate,
        now: Instant,
    ): ExecutionGateResumeCheckEvaluation {
        if (!now.isBefore(candidate.validUntil)) {
            return ExecutionGateResumeCheckEvaluation(ExecutionGateResumeCheckStatus.STALE, listOf("CHECK_EXPIRED"))
        }
        if (candidate.sourceErrors.isNotEmpty()) {
            return ExecutionGateResumeCheckEvaluation(
                ExecutionGateResumeCheckStatus.ERROR,
                candidate.sourceErrors.distinct(),
            )
        }
        val missing =
            buildList {
                if (candidate.tap == null) add("TAP_UNCONFIRMED")
                if (candidate.first == null) add("RPC_1_UNCONFIRMED")
                if (candidate.second == null) add("RPC_2_UNCONFIRMED")
            }
        if (missing.isNotEmpty()) {
            return ExecutionGateResumeCheckEvaluation(ExecutionGateResumeCheckStatus.UNCONFIRMED, missing)
        }

        val tap = requireNotNull(candidate.tap)
        val first = requireNotNull(candidate.first)
        val second = requireNotNull(candidate.second)
        val issues =
            buildList {
                if (candidate.firstEndpointId == candidate.secondEndpointId) add("RPC_NOT_INDEPENDENT")
                if (tap.blocked) add("TAP_BATCH_BLOCKED")
                if (tap.observedAt.isAfter(candidate.validUntil)) add("TAP_OBSERVED_AFTER_EXPIRY")
                evaluateRpc(1, candidate, first)
                evaluateRpc(2, candidate, second)
            }
        return ExecutionGateResumeCheckEvaluation(
            if (issues.isEmpty()) ExecutionGateResumeCheckStatus.READY else ExecutionGateResumeCheckStatus.DRIFT,
            issues,
        )
    }

    private fun MutableList<String>.evaluateRpc(
        sequence: Int,
        candidate: ExecutionGateResumeCheckCandidate,
        observation: EmergencyContractObservation,
    ) {
        val prefix = "RPC_$sequence"
        if (observation.blockNumber != candidate.pinnedBlockNumber) add("${prefix}_BLOCK_MISMATCH")
        if (observation.paused) add("${prefix}_PAUSED")
        if (observation.operatorSetHash != candidate.expectedOperatorSetHash) add("${prefix}_OPERATOR_DRIFT")
        if (observation.observedAt.isAfter(candidate.validUntil)) add("${prefix}_OBSERVED_AFTER_EXPIRY")
    }
}

interface ExecutionGateResumeRepository {
    fun insertSnapshot(snapshot: ExecutionGateResumeSnapshot): ExecutionGateResumeSnapshot

    fun findSnapshot(resumeId: String): ExecutionGateResumeSnapshot?

    fun findViewByRequest(requestId: String): ExecutionGateResumeView?

    fun appendCheck(check: ExecutionGateResumeCheck): ExecutionGateResumeCheck

    fun findCheckByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): ExecutionGateResumeCheck?

    fun findLatestCheck(resumeId: String): ExecutionGateResumeCheck?

    fun insertResumeIntent(
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

    fun insertResumeSuccess(
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
    )
}
