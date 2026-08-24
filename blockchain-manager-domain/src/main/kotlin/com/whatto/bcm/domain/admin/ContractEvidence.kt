package com.whatto.bcm.domain.admin

import java.math.BigInteger
import java.time.Instant

data class ContractExpectedState(
    val chainId: Long,
    val codeHash: String,
    val immutableHash: String,
    val pinnedBlockNumber: BigInteger,
)

data class RpcObservation(
    val endpointId: String,
    val chainId: Long,
    val codeHash: String,
    val immutableHash: String,
    val blockNumber: BigInteger,
    val observedAt: Instant,
)

data class ExternalControlEvidence(
    val tapMatches: Boolean,
    val callbackMatches: Boolean,
    val gaslessPassed: Boolean,
    val auditPassed: Boolean,
    val revokeDrillPassed: Boolean,
    val launchGatePassed: Boolean,
) {
    fun allPassed(): Boolean = tapMatches && callbackMatches && gaslessPassed && auditPassed && revokeDrillPassed && launchGatePassed

    companion object {
        fun allPassed() = ExternalControlEvidence(true, true, true, true, true, true)
    }
}

data class ContractEvidenceCandidate(
    val expected: ContractExpectedState,
    val first: RpcObservation?,
    val second: RpcObservation?,
    val controls: ExternalControlEvidence,
    val observedAt: Instant,
    val validUntil: Instant,
)

enum class ContractEvidenceStatus {
    VALID,
    INVALID,
    STALE,
    ERROR,
}

data class ContractEvidenceEvaluation(
    val status: ContractEvidenceStatus,
    val issues: List<String>,
) {
    val activationReady: Boolean = status == ContractEvidenceStatus.VALID
}

object ContractEvidenceEvaluator {
    fun evaluate(
        candidate: ContractEvidenceCandidate,
        now: Instant,
    ): ContractEvidenceEvaluation {
        if (!now.isBefore(candidate.validUntil)) {
            return ContractEvidenceEvaluation(ContractEvidenceStatus.STALE, listOf("EVIDENCE_EXPIRED"))
        }
        val first =
            candidate.first
                ?: return ContractEvidenceEvaluation(ContractEvidenceStatus.ERROR, listOf("RPC_1_UNAVAILABLE"))
        val second =
            candidate.second
                ?: return ContractEvidenceEvaluation(ContractEvidenceStatus.ERROR, listOf("RPC_2_UNAVAILABLE"))
        val issues =
            buildList {
                if (first.endpointId == second.endpointId) add("RPC_NOT_INDEPENDENT")
                listOf(first, second).forEachIndexed { index, observation ->
                    val source = "RPC_${index + 1}"
                    if (observation.blockNumber != candidate.expected.pinnedBlockNumber) add("${source}_BLOCK_MISMATCH")
                    if (observation.chainId != candidate.expected.chainId) add("${source}_CHAIN_MISMATCH")
                    if (observation.codeHash != candidate.expected.codeHash) add("${source}_CODE_HASH_MISMATCH")
                    if (observation.immutableHash != candidate.expected.immutableHash) add("${source}_IMMUTABLE_MISMATCH")
                    if (observation.observedAt.isAfter(candidate.validUntil)) add("${source}_OBSERVED_AFTER_EXPIRY")
                }
                if (!candidate.controls.allPassed()) add("EXTERNAL_CONTROL_DRIFT")
            }
        return ContractEvidenceEvaluation(
            if (issues.isEmpty()) ContractEvidenceStatus.VALID else ContractEvidenceStatus.INVALID,
            issues,
        )
    }
}
