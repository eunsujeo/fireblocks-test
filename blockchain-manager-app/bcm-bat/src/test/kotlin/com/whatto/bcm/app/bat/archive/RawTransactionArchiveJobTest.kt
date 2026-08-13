package com.whatto.bcm.app.bat.archive

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.archive.RawTransactionArchiveBatch
import com.whatto.bcm.domain.archive.RawTransactionArchiveRepository
import com.whatto.bcm.domain.job.JobState
import com.whatto.bcm.domain.job.JobStateRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class RawTransactionArchiveJobTest {
    @Test
    fun `성공 커서 창을 모두 보관하고 운영 보존일이 지난 처리 인박스를 같은 실행에서 정리한다`() {
        val archives = RecordingArchives(ArrayDeque(listOf(RawTransactionArchiveBatch(2, 2), RawTransactionArchiveBatch(0, 0))))
        val jobs = RecordingJobs(JobState(JOB_NAME, "20260812120000", "20260812120000"))
        val job = job(archives, jobs)

        job.run()

        assertThat(archives.archiveRequests)
            .containsExactly(
                ArchiveRequest("20260813", "20260812120000", NOW, 500),
                ArchiveRequest("20260813", "20260812120000", NOW, 500),
            )
        assertThat(archives.cleanupCutoffs).containsExactly("20260714120000")
        assertThat(jobs.started).containsExactly(JOB_NAME to NOW)
        assertThat(jobs.succeeded).containsExactly(JOB_NAME to NOW)
    }

    @Test
    fun `파티션 누락 등 보관 실패는 정리와 성공 heartbeat를 수행하지 않는다`() {
        val archives = RecordingArchives(ArrayDeque(), archiveFailure = IllegalStateException("partition missing"))
        val jobs = RecordingJobs(null)
        val job = job(archives, jobs)

        assertThatThrownBy(job::run)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("partition")

        assertThat(archives.cleanupCutoffs).isEmpty()
        assertThat(jobs.started).containsExactly(JOB_NAME to NOW)
        assertThat(jobs.succeeded).isEmpty()
    }

    @Test
    fun `활성화한 보관 배치는 양의 운영 보존일을 필수로 요구한다`() {
        assertThatThrownBy {
            RawTransactionArchiveProperties(enabled = true, retentionDays = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("retentionDays")

        assertThatThrownBy {
            RawTransactionArchiveProperties(enabled = true, retentionDays = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("retentionDays")
    }

    private fun job(
        archives: RawTransactionArchiveRepository,
        jobs: JobStateRepository,
    ) = RawTransactionArchiveJob(
        archives = archives,
        jobs = jobs,
        transactionRunner =
            object : TransactionRunner {
                override fun <T> run(block: () -> T): T = block()
            },
        clock = CLOCK,
        properties = RawTransactionArchiveProperties(enabled = true, retentionDays = 30),
    )

    private companion object {
        const val JOB_NAME = "raw-transaction-archive"
        const val NOW = "20260813120000"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-13T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    }
}

private data class ArchiveRequest(
    val baseDate: String,
    val receivedAtOrAfter: String,
    val receivedAtOrBefore: String,
    val limit: Int,
)

private class RecordingArchives(
    private val batches: ArrayDeque<RawTransactionArchiveBatch>,
    private val archiveFailure: RuntimeException? = null,
) : RawTransactionArchiveRepository {
    val archiveRequests = mutableListOf<ArchiveRequest>()
    val cleanupCutoffs = mutableListOf<String>()

    override fun archiveCompletedWindow(
        baseDate: String,
        receivedAtOrAfter: String,
        receivedAtOrBefore: String,
        limit: Int,
    ): RawTransactionArchiveBatch {
        archiveRequests += ArchiveRequest(baseDate, receivedAtOrAfter, receivedAtOrBefore, limit)
        archiveFailure?.let { throw it }
        return batches.removeFirst()
    }

    override fun deleteProcessedAtOrBefore(processedAtOrBefore: String): Int {
        cleanupCutoffs += processedAtOrBefore
        return 3
    }
}

private class RecordingJobs(
    private val initial: JobState?,
) : JobStateRepository {
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

    override fun find(jobName: String): JobState? = initial
}
