package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.admin.SweepExecutionPolicy
import com.whatto.bcm.domain.job.JobState
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.job.OperationalJobNames
import com.whatto.bcm.domain.sweep.ActiveSweepRuntimeContext
import com.whatto.bcm.domain.sweep.SweepExecutionGatePort
import com.whatto.bcm.domain.sweep.SweepExecutionGateSnapshot
import com.whatto.bcm.domain.sweep.SweepRuntimeAttestationRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SweepBatchJobTest {
    @Test
    fun `sweep 실행 주기는 실제 시작과 성공 heartbeat를 남긴다`() {
        val command = SweepBatchExecutionCommand { SweepBatchExecutionResult.NoCandidates }
        val jobs = RecordingJobs()
        val attestor = attestor()
        val job = SweepBatchJob(command, jobs, CLOCK, attestor)

        job.run()

        assertThat(jobs.started).containsExactly(OperationalJobNames.SWEEP_BATCH_EXECUTION to "20260818010000")
        assertThat(jobs.validationStarted).containsExactly(ATTESTATION_JOB to "20260818010000")
        assertThat(jobs.succeeded).containsExactly(
            OperationalJobNames.SWEEP_BATCH_EXECUTION to "20260818010000",
            ATTESTATION_JOB to "20260818010000",
        )
    }

    @Test
    fun `sweep 실행 실패는 시작 heartbeat만 남기고 성공으로 오판하지 않는다`() {
        val command = SweepBatchExecutionCommand { throw IllegalStateException("release gate closed") }
        val jobs = RecordingJobs()
        val job = SweepBatchJob(command, jobs, CLOCK, attestor())

        assertThatThrownBy { job.run() }.isInstanceOf(IllegalStateException::class.java)

        assertThat(jobs.started).hasSize(1)
        assertThat(jobs.validationStarted).hasSize(1)
        assertThat(jobs.succeeded).isEmpty()
    }

    @Test
    fun `후보가 없어도 BAT runtime 불일치는 성공 증적을 남기지 않는다`() {
        var commandCalls = 0
        val command =
            SweepBatchExecutionCommand {
                commandCalls += 1
                SweepBatchExecutionResult.NoCandidates
            }
        val jobs = RecordingJobs()
        val context = runtimeContext()
        val attestor =
            SweepRuntimeAttestor(
                SweepRuntimeAttestationRepository { listOf(context) },
                SweepRuntimeGuard { _, _ -> throw IllegalStateException("BAT runtime differs") },
                SweepExecutionGatePort { SweepExecutionGateSnapshot(it, 0, "OPEN") },
                sweepProperties(),
            )
        val job = SweepBatchJob(command, jobs, CLOCK, attestor)

        assertThatThrownBy { job.run() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("BAT runtime differs")

        assertThat(commandCalls).isZero()
        assertThat(jobs.validationStarted.single().second).isEqualTo("20260818010000")
        assertThat(jobs.validationStarted.single().first).startsWith("sweep-attestation:")
        assertThat(jobs.succeeded).isEmpty()
    }

    @Test
    fun `검증 뒤 gate 중지를 관찰하면 runtime 성공 증적을 남기지 않는다`() {
        val command = SweepBatchExecutionCommand { SweepBatchExecutionResult.Stopped("BASE") }
        val jobs = RecordingJobs()
        val job = SweepBatchJob(command, jobs, CLOCK, attestor())

        job.run()

        assertThat(jobs.succeeded).containsExactly(
            OperationalJobNames.SWEEP_BATCH_EXECUTION to "20260818010000",
        )
    }

    private fun runtimeContext() =
        ActiveSweepRuntimeContext(
            network = "BASE",
            symbol = "USDC",
            policyVersionId = "policy-v1",
            policySnapshotHash = "a".repeat(64),
            policy =
                SweepExecutionPolicy(
                    enabled = true,
                    minimumAmount = BigDecimal.ONE,
                    batchSize = 10,
                    allowanceCap = BigDecimal.TEN,
                    itemAmountCap = BigDecimal.TEN,
                    batchAmountCap = BigDecimal.TEN,
                    boostAttempts = 1,
                ),
            contractVersionId = "contract-v1",
            contractEvidenceId = "evidence-v1",
            contractAddress = "0xabc",
        )

    private fun sweepProperties() =
        SweepProperties(
            batchSize = 10,
            thresholds = listOf(SweepAssetThreshold("BASE", "USDC", "1", "10")),
            contracts = listOf(SweepNetworkContract("BASE", "0xabc")),
            security =
                SweepSecurityProperties(
                    batchSubmissionEnabled = true,
                    tapBatchPolicyVerified = true,
                    callbackVerified = true,
                    universalGaslessVerified = true,
                    sweepContractVerified = true,
                    batchSubmissionEnabledNetworks = setOf("BASE"),
                ),
        )

    private fun attestor(): SweepRuntimeAttestationCommand = RecordingAttestor()

    private class RecordingAttestor : SweepRuntimeAttestationCommand {
        private val observation = SweepRuntimeAttestationObservation(ATTESTATION_JOB, emptyList(), emptyList())

        override fun observe(observedAt: Instant): SweepRuntimeAttestationObservation = observation

        override fun requireReady(observation: SweepRuntimeAttestationObservation) {
        }
    }

    private class RecordingJobs : JobStateRepository {
        val started = mutableListOf<Pair<String, String>>()
        val validationStarted = mutableListOf<Pair<String, String>>()
        val succeeded = mutableListOf<Pair<String, String>>()

        override fun markStarted(
            jobName: String,
            at: String,
        ) {
            started += jobName to at
        }

        override fun markSucceeded(
            jobName: String,
            at: String,
        ) {
            succeeded += jobName to at
        }

        override fun markValidationStarted(
            jobName: String,
            at: String,
        ) {
            validationStarted += jobName to at
        }

        override fun find(jobName: String): JobState? = null
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-18T01:00:00Z"), ZoneOffset.UTC)
        const val ATTESTATION_JOB = "sweep-attestation:test"
    }
}
