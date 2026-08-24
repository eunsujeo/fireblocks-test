package com.whatto.bcm.domain.job

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

data class JobState(
    val jobName: String,
    val lastRunAt: String,
    val lastSucceededAt: String?,
)

interface JobStateRepository {
    fun markStarted(
        jobName: String,
        at: String,
    )

    fun markSucceeded(
        jobName: String,
        at: String,
    )

    /** 새 검증 주기를 시작하면서 이전 성공 증적을 폐기한다. */
    fun markValidationStarted(
        jobName: String,
        at: String,
    ) {
        markStarted(jobName, at)
    }

    fun find(jobName: String): JobState?
}

object OperationalJobNames {
    const val SWEEP_BATCH_EXECUTION = "sweep-batch-execution"
}

data class RuntimeAttestationEntry(
    val scopeKey: String,
    val fingerprint: String,
) {
    companion object {
        fun gate(
            scopeKey: String,
            sequence: Int,
            state: String,
        ) = RuntimeAttestationEntry("gate:$scopeKey", "$sequence|$state")
    }
}

object RuntimeAttestation {
    fun jobName(
        prefix: String,
        entries: List<RuntimeAttestationEntry>,
    ): String {
        val canonical = entries.sortedBy(RuntimeAttestationEntry::scopeKey).joinToString("\n") { "${it.scopeKey}|${it.fingerprint}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
