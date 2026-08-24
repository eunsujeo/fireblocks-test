package com.whatto.bcm.domain.sweep

import com.whatto.bcm.domain.admin.SweepExecutionPolicy
import com.whatto.bcm.domain.job.RuntimeAttestationEntry
import java.time.Instant

data class ActiveSweepRuntimeContext(
    val network: String,
    val symbol: String,
    val policyVersionId: String,
    val policySnapshotHash: String,
    val policy: SweepExecutionPolicy,
    val contractVersionId: String,
    val contractEvidenceId: String,
    val contractAddress: String,
)

fun interface SweepRuntimePolicyRepository {
    fun findActive(
        network: String,
        symbol: String,
        observedAt: Instant,
    ): ActiveSweepRuntimeContext?
}

fun interface SweepRuntimeAttestationRepository {
    fun findAllActive(observedAt: Instant): List<ActiveSweepRuntimeContext>
}

data class SweepExecutionGateSnapshot(
    val network: String,
    val sequence: Int,
    val state: String,
) {
    val open: Boolean
        get() = state != "STOPPED"

    fun attestationEntry(): RuntimeAttestationEntry = RuntimeAttestationEntry.gate("SWEEP|$network", sequence, state)
}

fun interface SweepExecutionGatePort {
    fun findCurrent(network: String): SweepExecutionGateSnapshot
}

fun ActiveSweepRuntimeContext.attestationEntry(): RuntimeAttestationEntry =
    RuntimeAttestationEntry(
        scopeKey = "$network|$symbol",
        fingerprint =
            listOf(
                policyVersionId,
                policySnapshotHash,
                policy.enabled.toString(),
                policy.minimumAmount.canonical(),
                policy.batchSize.toString(),
                policy.allowanceCap.canonical(),
                policy.itemAmountCap.canonical(),
                policy.batchAmountCap.canonical(),
                policy.boostAttempts.toString(),
                contractVersionId,
                contractEvidenceId,
                contractAddress.lowercase(),
            ).joinToString("|"),
    )

private fun java.math.BigDecimal.canonical(): String = stripTrailingZeros().toPlainString()
