package com.whatto.bcm.app.bat.submission

import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.submission.PendingSubmissionRecoveryRepository
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
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
@ConditionalOnProperty(prefix = "bcm.submission-recovery", name = ["enabled"], havingValue = "true")
class SubmissionRecoveryJob(
    private val submissions: PendingSubmissionRecoveryRepository,
    private val vendor: VendorTransactionPort,
    private val jobs: JobStateRepository,
    private val clock: Clock,
    private val properties: SubmissionRecoveryProperties,
) {
    @Scheduled(fixedDelayString = "\${bcm.submission-recovery.fixed-delay-millis:300000}")
    fun run() {
        val current = LocalDateTime.now(clock)
        val now = CoreDateTimes.format(current)
        jobs.markStarted(JOB_NAME, now)
        val candidates =
            submissions.reserveRequestedForRecovery(
                now = now,
                requestedBefore = CoreDateTimes.format(current.minusSeconds(properties.staleAfterSeconds)),
                checkedBefore = CoreDateTimes.format(current.minusSeconds(properties.retryAfterSeconds)),
                limit = properties.batchSize,
            )
        var failures = 0
        var recovered = 0
        candidates.forEach { candidate ->
            try {
                val transaction = vendor.transactionByExternalTransactionId(candidate.externalTransactionId)
                if (transaction != null) {
                    check(transaction.externalTransactionId == candidate.externalTransactionId) {
                        "vendor lookup returned mismatched external transaction id"
                    }
                    submissions.markRecoveredSubmitted(
                        candidate.externalTransactionId,
                        transaction.transactionId,
                        now,
                    )
                    recovered += 1
                }
            } catch (exception: RuntimeException) {
                failures += 1
                logger.warn(
                    "미결 제출 조회 실패 externalTransactionId={} checkCount={}",
                    candidate.externalTransactionId,
                    candidate.checkCount,
                    exception,
                )
            }
        }
        if (failures == 0) jobs.markSucceeded(JOB_NAME, CoreDateTimes.now(clock))
        logger.info(
            "미결 제출 점검 완료 checked={} recovered={} failures={}",
            candidates.size,
            recovered,
            failures,
        )
    }

    internal companion object {
        const val JOB_NAME = "submission-recovery"
        val logger = LoggerFactory.getLogger(SubmissionRecoveryJob::class.java)
    }
}

@ConfigurationProperties("bcm.submission-recovery")
data class SubmissionRecoveryProperties(
    val enabled: Boolean = false,
    val fixedDelayMillis: Long = 300_000,
    val staleAfterSeconds: Long = 300,
    val retryAfterSeconds: Long = 300,
    val batchSize: Int = 100,
) {
    init {
        require(fixedDelayMillis > 0) { "fixedDelayMillis must be positive" }
        require(staleAfterSeconds > 0) { "staleAfterSeconds must be positive" }
        require(retryAfterSeconds > 0) { "retryAfterSeconds must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
    }

    val retryAfterMillis: Long
        get() = Math.multiplyExact(retryAfterSeconds, 1_000)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubmissionRecoveryProperties::class)
class SubmissionRecoveryConfig

@Configuration(proxyBeanMethods = false)
class SubmissionRecoverySafetyConfig(
    properties: SubmissionRecoveryProperties,
    fireblocksProperties: FireblocksProperties,
) {
    init {
        require(properties.retryAfterMillis > fireblocksProperties.maximumCallMillis) {
            "submission recovery retry interval must be longer than the maximum Fireblocks call"
        }
    }
}
