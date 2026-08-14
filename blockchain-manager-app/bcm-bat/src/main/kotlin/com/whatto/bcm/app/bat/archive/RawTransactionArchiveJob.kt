package com.whatto.bcm.app.bat.archive

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.archive.RawTransactionArchiveRepository
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.support.time.CoreDateTimes
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

@Component
@ConditionalOnProperty(prefix = "bcm.raw-transaction-archive", name = ["enabled"], havingValue = "true")
class RawTransactionArchiveJob(
    private val archives: RawTransactionArchiveRepository,
    private val jobs: JobStateRepository,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val properties: RawTransactionArchiveProperties,
) {
    @Scheduled(fixedDelayString = "\${bcm.raw-transaction-archive.fixed-delay-millis:86400000}")
    fun run() {
        val current = LocalDateTime.now(clock)
        val now = CoreDateTimes.format(current)
        val baseDate = now.take(8)
        val cleanupCutoff = CoreDateTimes.format(current.minusDays(properties.requiredRetentionDays))
        jobs.markStarted(JOB_NAME, now)

        var archivedCount = 0
        var drained = false
        var batchCount = 0
        while (batchCount < properties.maxBatchesPerRun && !drained) {
            batchCount += 1
            val batch =
                transactionRunner.run {
                    archives.archiveCompletedBatch(baseDate, now, properties.batchSize)
                }
            archivedCount += batch.archivedCount
            if (batch.candidateCount < properties.batchSize) {
                drained = true
            }
        }
        check(drained) {
            "raw transaction archive backlog remains after max batches: maxBatchesPerRun=${properties.maxBatchesPerRun}"
        }
        val result =
            transactionRunner.run {
                val deletedCount = archives.deleteProcessedAtOrBefore(cleanupCutoff)
                jobs.markSucceeded(JOB_NAME, now)
                ArchiveRunResult(archivedCount, deletedCount)
            }
        logger.info(
            "원본 보관 완료 to={} baseDate={} archived={} deletedInbox={}",
            now,
            baseDate,
            result.archivedCount,
            result.deletedInboxCount,
        )
    }

    internal companion object {
        const val JOB_NAME = "raw-transaction-archive"
        val logger = LoggerFactory.getLogger(RawTransactionArchiveJob::class.java)
    }
}

internal data class ArchiveRunResult(
    val archivedCount: Int,
    val deletedInboxCount: Int,
)

@ConfigurationProperties("bcm.raw-transaction-archive")
data class RawTransactionArchiveProperties(
    val enabled: Boolean = false,
    val fixedDelayMillis: Long = 86_400_000,
    val retentionDays: Long? = null,
    val batchSize: Int = 500,
    val maxBatchesPerRun: Int = 20,
) {
    init {
        require(fixedDelayMillis > 0) { "fixedDelayMillis must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxBatchesPerRun > 0) { "maxBatchesPerRun must be positive" }
        require(retentionDays == null || retentionDays > 0) { "retentionDays must be positive" }
        require(!enabled || retentionDays != null) { "retentionDays is required when raw transaction archive is enabled" }
    }

    val requiredRetentionDays: Long
        get() = checkNotNull(retentionDays) { "retentionDays is required when raw transaction archive is enabled" }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RawTransactionArchiveProperties::class)
class RawTransactionArchiveConfig
