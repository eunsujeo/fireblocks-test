package com.whatto.bcm.domain.sweep

import com.whatto.bcm.domain.admin.SweepExecutionPolicy
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
