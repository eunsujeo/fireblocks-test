package com.whatto.bcm.app.bat.submission

import com.whatto.bcm.domain.job.JobState
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.submission.PendingSubmissionCheck
import com.whatto.bcm.domain.submission.PendingSubmissionRecoveryRepository
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class SubmissionRecoveryJobTest {
    @Test
    fun `미결 제출 회수 스케줄러는 기본 비활성이다`() {
        val properties = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
        val environment = StandardEnvironment().apply { propertySources.addFirst(properties) }

        assertThat(environment.getProperty("bcm.submission-recovery.enabled", Boolean::class.java)).isFalse()
    }

    @Test
    fun `재점검 간격은 벤더 단건 조회의 최장 시간보다 길어야 한다`() {
        val fireblocks =
            FireblocksProperties(
                maxAttempts = 1,
                connectTimeoutMillis = 3_000,
                readTimeoutMillis = 10_000,
            )

        assertThatThrownBy {
            SubmissionRecoverySafetyConfig(
                SubmissionRecoveryProperties(retryAfterSeconds = 13),
                fireblocks,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        SubmissionRecoverySafetyConfig(
            SubmissionRecoveryProperties(retryAfterSeconds = 14),
            fireblocks,
        )
    }

    @Test
    fun `조회에서 찾은 거래만 SUBMITTED로 회수하고 미발견 거래는 재제출하지 않는다`() {
        val recovery =
            RecordingRecoveryRepository(
                mutableListOf(
                    PendingSubmissionCheck("wd-found", "20260807120000", 1),
                    PendingSubmissionCheck("wd-missing", "20260807120000", 1),
                ),
            )
        val vendor = RecordingVendor(foundIds = setOf("wd-found"))
        val jobs = RecordingJobStateRepository()
        val job = job(recovery, vendor, jobs)

        job.run()

        assertThat(vendor.lookups).containsExactly("wd-found", "wd-missing")
        assertThat(vendor.submissions).isEmpty()
        assertThat(recovery.recovered).containsExactly(RecoveredSubmission("wd-found", "tx-wd-found", NOW))
        assertThat(jobs.started).containsExactly(JOB_NAME to NOW)
        assertThat(jobs.succeeded).containsExactly(JOB_NAME to NOW)
    }

    @Test
    fun `한 조회가 실패해도 다음 건을 계속 확인하고 해당 주기를 성공으로 기록하지 않는다`() {
        val recovery =
            RecordingRecoveryRepository(
                mutableListOf(
                    PendingSubmissionCheck("wd-error", "20260807120000", 1),
                    PendingSubmissionCheck("wd-found", "20260807120000", 1),
                ),
            )
        val vendor = RecordingVendor(foundIds = setOf("wd-found"), failingIds = setOf("wd-error"))
        val jobs = RecordingJobStateRepository()

        job(recovery, vendor, jobs).run()

        assertThat(vendor.lookups).containsExactly("wd-error", "wd-found")
        assertThat(recovery.recovered).containsExactly(RecoveredSubmission("wd-found", "tx-wd-found", NOW))
        assertThat(jobs.started).containsExactly(JOB_NAME to NOW)
        assertThat(jobs.succeeded).isEmpty()
    }

    private fun job(
        recovery: PendingSubmissionRecoveryRepository,
        vendor: VendorTransactionPort,
        jobs: JobStateRepository,
    ) = SubmissionRecoveryJob(
        recovery,
        vendor,
        jobs,
        Clock.fixed(Instant.parse("2026-08-07T03:00:00Z"), ZoneId.of("Asia/Seoul")),
        SubmissionRecoveryProperties(
            enabled = true,
            staleAfterSeconds = 300,
            retryAfterSeconds = 600,
            batchSize = 100,
        ),
    )

    private companion object {
        const val NOW = "20260807120000"
        const val JOB_NAME = "submission-recovery"
    }
}

private data class RecoveredSubmission(
    val externalTransactionId: String,
    val vendorTransactionId: String,
    val respondedAt: String,
)

private class RecordingRecoveryRepository(
    private val candidates: MutableList<PendingSubmissionCheck>,
) : PendingSubmissionRecoveryRepository {
    val recovered = mutableListOf<RecoveredSubmission>()

    override fun reserveRequestedForRecovery(
        now: String,
        requestedBefore: String,
        checkedBefore: String,
        limit: Int,
    ): List<PendingSubmissionCheck> = candidates.take(limit)

    override fun markRecoveredSubmitted(
        externalTransactionId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ) {
        recovered += RecoveredSubmission(externalTransactionId, vendorTransactionId, respondedAt)
    }
}

private class RecordingJobStateRepository : JobStateRepository {
    val started = mutableListOf<Pair<String, String>>()
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

    override fun find(jobName: String): JobState? = null
}

private class RecordingVendor(
    private val foundIds: Set<String>,
    private val failingIds: Set<String> = emptySet(),
) : VendorTransactionPort {
    val lookups = mutableListOf<String>()
    val submissions = mutableListOf<VendorTransactionRequest>()

    override fun transactionByExternalTransactionId(externalTransactionId: String): VendorTransaction? {
        lookups += externalTransactionId
        if (externalTransactionId in failingIds) error("simulated lookup failure")
        return if (externalTransactionId in foundIds) transactionFixture(externalTransactionId) else null
    }

    override fun submitTransaction(request: VendorTransactionRequest): VendorTransactionSubmission {
        submissions += request
        return VendorTransactionSubmission.Accepted("unexpected")
    }

    override fun transaction(transactionId: String): VendorTransaction? = null

    override fun transactions(request: VendorTransactionPageRequest): VendorPage<VendorTransaction> = VendorPage(emptyList(), null)

    private fun transactionFixture(externalTransactionId: String) =
        VendorTransaction(
            transactionId = "tx-$externalTransactionId",
            externalTransactionId = externalTransactionId,
            vendorAssetId = "USDC_ERC20",
            rawStatus = "SUBMITTED",
            subStatus = null,
            transactionHash = null,
            source = VendorTransactionPeer("VAULT_ACCOUNT", "1"),
            destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
            sourceAddress = null,
            destinationAddress = null,
            amount = "1",
            confirmationCount = 0,
            createdAtEpochMillis = 0,
            lastUpdatedEpochMillis = 0,
        )
}
