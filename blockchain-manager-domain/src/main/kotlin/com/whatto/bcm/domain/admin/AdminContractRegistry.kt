package com.whatto.bcm.domain.admin

import java.math.BigInteger
import java.time.Instant

data class AdminContractVersion(
    val versionId: String,
    val scopeId: String,
    val network: String,
    val use: String,
    val version: String,
    val address: String,
    val releaseCommit: String,
    val artifactHash: String,
    val abiHash: String,
    val runtimeCodeHash: String,
    val deploymentTransactionHash: String,
    val deploymentBlockNumber: BigInteger,
    val immutableValues: String,
    val immutableHash: String,
    val ceilingSnapshot: String,
    val ceilingHash: String,
    val releaseUri: String,
    val registeredAt: Instant,
    val registeredBy: AdminActor,
)

data class AdminContractEvidence(
    val evidenceId: String,
    val contractVersionId: String,
    val snapshotHash: String,
    val candidate: ContractEvidenceCandidate,
    val evaluation: ContractEvidenceEvaluation,
    val documentEvidence: String,
    val documentEvidenceHash: String,
    val firstEndpointId: String,
    val secondEndpointId: String,
    val registeredBy: AdminActor,
)

data class AdminContractBinding(
    val scopeId: String,
    val network: String,
    val use: String,
    val activeVersionId: String?,
    val revision: Long,
    val evidenceId: String?,
    val snapshotHash: String,
)

fun interface ContractVerificationPort {
    fun collect(
        version: AdminContractVersion,
        now: Instant,
    ): ContractVerificationResult
}

data class ContractVerificationResult(
    val candidate: ContractEvidenceCandidate,
    val firstEndpointId: String,
    val secondEndpointId: String,
    val documentEvidence: String,
)

interface AdminContractRepository {
    fun insertVersion(version: AdminContractVersion): AdminContractVersion

    fun findVersion(versionId: String): AdminContractVersion?

    fun initializeBinding(
        version: AdminContractVersion,
        actor: AdminActor,
        now: Instant,
    ): AdminContractBinding

    fun insertEvidence(evidence: AdminContractEvidence): AdminContractEvidence

    fun findEvidence(evidenceId: String): AdminContractEvidence?

    fun findLatestEvidence(versionId: String): AdminContractEvidence?

    fun lockBinding(scopeId: String): AdminContractBinding?

    fun activateBinding(
        request: AdminChangeRequest,
        actor: AdminActor,
        now: Instant,
    ): AdminContractBinding
}
