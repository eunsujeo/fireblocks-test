package com.whatto.bcm.domain.admin

import java.math.BigInteger
import java.time.Instant

data class TapBatchObservation(
    val blocked: Boolean,
    val observedAt: Instant,
)

data class EmergencyContractObservation(
    val blockNumber: BigInteger,
    val paused: Boolean,
    val operatorSetHash: String,
    val observedAt: Instant,
)

data class EmergencyExternalControlCandidate(
    val tapSourceId: String,
    val tap: TapBatchObservation?,
    val pinnedBlockNumber: BigInteger,
    val expectedOperatorSetHash: String,
    val firstEndpointId: String,
    val first: EmergencyContractObservation?,
    val secondEndpointId: String,
    val second: EmergencyContractObservation?,
    val sourceErrors: List<String>,
    val observedAt: Instant,
    val validUntil: Instant,
)

enum class EmergencyExternalControlStatus {
    CONFIRMED,
    DRIFT,
    STALE,
    UNCONFIRMED,
    ERROR,
}

data class EmergencyExternalControlEvaluation(
    val status: EmergencyExternalControlStatus,
    val issues: List<String>,
) {
    val completionReady: Boolean = status == EmergencyExternalControlStatus.CONFIRMED
}

object EmergencyExternalControlEvaluator {
    fun evaluate(
        candidate: EmergencyExternalControlCandidate,
        now: Instant,
    ): EmergencyExternalControlEvaluation {
        if (!now.isBefore(candidate.validUntil)) {
            return EmergencyExternalControlEvaluation(EmergencyExternalControlStatus.STALE, listOf("EVIDENCE_EXPIRED"))
        }
        if (candidate.sourceErrors.isNotEmpty()) {
            return EmergencyExternalControlEvaluation(
                EmergencyExternalControlStatus.ERROR,
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
            return EmergencyExternalControlEvaluation(EmergencyExternalControlStatus.UNCONFIRMED, missing)
        }

        val tap = requireNotNull(candidate.tap)
        val first = requireNotNull(candidate.first)
        val second = requireNotNull(candidate.second)
        val issues =
            buildList {
                if (candidate.firstEndpointId == candidate.secondEndpointId) add("RPC_NOT_INDEPENDENT")
                if (!tap.blocked) add("TAP_BATCH_NOT_BLOCKED")
                if (tap.observedAt.isAfter(candidate.validUntil)) add("TAP_OBSERVED_AFTER_EXPIRY")
                evaluateRpc(1, candidate, first)
                evaluateRpc(2, candidate, second)
            }
        return EmergencyExternalControlEvaluation(
            if (issues.isEmpty()) EmergencyExternalControlStatus.CONFIRMED else EmergencyExternalControlStatus.DRIFT,
            issues,
        )
    }

    private fun MutableList<String>.evaluateRpc(
        sequence: Int,
        candidate: EmergencyExternalControlCandidate,
        observation: EmergencyContractObservation,
    ) {
        val prefix = "RPC_$sequence"
        if (observation.blockNumber != candidate.pinnedBlockNumber) add("${prefix}_BLOCK_MISMATCH")
        if (!observation.paused) add("${prefix}_NOT_PAUSED")
        if (observation.operatorSetHash != candidate.expectedOperatorSetHash) add("${prefix}_OPERATOR_DRIFT")
        if (observation.observedAt.isAfter(candidate.validUntil)) add("${prefix}_OBSERVED_AFTER_EXPIRY")
    }
}

data class EmergencyExternalControlEvidence(
    val evidenceId: String,
    val network: String,
    val contractVersionId: String,
    val snapshotHash: String,
    val candidate: EmergencyExternalControlCandidate,
    val evaluation: EmergencyExternalControlEvaluation,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val registeredBy: AdminActor,
)

fun interface EmergencyExternalControlVerificationPort {
    fun collect(
        version: AdminContractVersion,
        now: Instant,
    ): EmergencyExternalControlCandidate
}

interface EmergencyExternalControlRepository {
    fun findByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): EmergencyExternalControlEvidence?

    fun findLatestByNetwork(network: String): EmergencyExternalControlEvidence?

    fun insert(evidence: EmergencyExternalControlEvidence): EmergencyExternalControlEvidence
}
