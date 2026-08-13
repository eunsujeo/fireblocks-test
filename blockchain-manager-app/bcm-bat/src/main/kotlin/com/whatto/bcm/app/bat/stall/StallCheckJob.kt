package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.tx.StallAlert
import com.whatto.bcm.domain.tx.StallAlertPort
import com.whatto.bcm.domain.tx.StallAlertReason
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.StallCandidateRepository
import com.whatto.bcm.domain.tx.StallDecision
import com.whatto.bcm.domain.tx.StallDecisionPolicy
import com.whatto.bcm.domain.tx.StallLatestObservation
import com.whatto.bcm.domain.vendor.VendorTransaction
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
@ConditionalOnProperty(prefix = "bcm.stall-check", name = ["enabled"], havingValue = "true")
class StallCheckJob(
    private val candidates: StallCandidateRepository,
    private val vendor: VendorTransactionPort,
    private val alerts: StallAlertPort,
    private val boostSubmitter: BoostSubmitter,
    private val jobs: JobStateRepository,
    private val clock: Clock,
    private val properties: StallCheckProperties,
) {
    @Scheduled(fixedDelayString = "\${bcm.stall-check.fixed-delay-millis:300000}")
    fun run() {
        val current = LocalDateTime.now(clock)
        val now = CoreDateTimes.format(current)
        jobs.markStarted(JOB_NAME, now)
        val stallCandidates =
            candidates.findStallCandidates(
                changedBefore = CoreDateTimes.format(current.minusSeconds(properties.staleAfterSeconds)),
                limit = properties.batchSize,
            )
        var failures = 0
        stallCandidates.forEach { candidate ->
            try {
                inspect(candidate, now)
            } catch (exception: RuntimeException) {
                failures += 1
                logger.warn(
                    "막힘 후보 최신 관찰 실패 rootVendorTransactionId={} activeVendorTransactionId={}",
                    candidate.record.vendorTxId,
                    candidate.record.activeVendorTxId,
                    exception,
                )
            }
        }
        if (failures == 0) jobs.markSucceeded(JOB_NAME, CoreDateTimes.now(clock))
        logger.info("막힘 점검 완료 candidates={} failures={}", stallCandidates.size, failures)
    }

    private fun inspect(
        candidate: StallCandidate,
        observedAt: String,
    ) {
        val latest = vendor.transaction(candidate.record.activeVendorTxId)
        val decision = latest?.decision(candidate) ?: StallDecision.Alert(StallAlertReason.VENDOR_TRANSACTION_NOT_FOUND)
        val boostResult =
            when (decision) {
                is StallDecision.Alert -> BoostSubmissionResult.Alert(decision.reason)
                is StallDecision.BoostEligible -> {
                    if (candidate.record.network in properties.automaticBoostEnabledNetworks) {
                        boostSubmitter.submit(candidate, decision.transactionHash)
                    } else {
                        BoostSubmissionResult.Alert(StallAlertReason.AUTOMATIC_BOOST_DISABLED)
                    }
                }
            }
        val alertReason =
            when (boostResult) {
                is BoostSubmissionResult.Submitted,
                BoostSubmissionResult.InProgress,
                BoostSubmissionResult.StaleCandidate,
                -> return

                is BoostSubmissionResult.Alert -> boostResult.reason
            }
        if (!candidates.markStallAlertedIfAbsent(candidate.record, observedAt)) return
        alerts.alert(
            StallAlert(
                rootVendorTransactionId = candidate.record.vendorTxId,
                activeVendorTransactionId = candidate.record.activeVendorTxId,
                reason = alertReason,
                observedTransactionHash = latest?.transactionHash,
            ),
        )
    }

    private fun VendorTransaction.decision(candidate: StallCandidate): StallDecision =
        StallDecisionPolicy.decide(
            record = candidate.record,
            submissionType = candidate.submissionType,
            observation =
                StallLatestObservation(
                    vendorTransactionId = transactionId,
                    lifecycleStage = lifecycleStage,
                    transactionHash = transactionHash,
                    confirmationCount = confirmationCount,
                ),
        )

    private companion object {
        const val JOB_NAME = "stall-check"
        val logger = LoggerFactory.getLogger(StallCheckJob::class.java)
    }
}

@ConfigurationProperties("bcm.stall-check")
data class StallCheckProperties(
    val enabled: Boolean = false,
    val fixedDelayMillis: Long = 300_000,
    val staleAfterSeconds: Long = 900,
    val batchSize: Int = 100,
    val automaticBoostEnabledNetworks: Set<String> = emptySet(),
    val maximumBoostAttempts: Int = 3,
    val boostClaimTtlSeconds: Long = 180,
) {
    init {
        require(fixedDelayMillis > 0) { "fixedDelayMillis must be positive" }
        require(staleAfterSeconds > 0) { "staleAfterSeconds must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(maximumBoostAttempts > 0) { "maximumBoostAttempts must be positive" }
        require(boostClaimTtlSeconds > 0) { "boostClaimTtlSeconds must be positive" }
    }

    val boostClaimTtlMillis: Long
        get() = Math.multiplyExact(boostClaimTtlSeconds, 1_000)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StallCheckProperties::class)
class StallCheckConfig

@Configuration(proxyBeanMethods = false)
class StallCheckSafetyConfig(
    properties: StallCheckProperties,
    fireblocksProperties: FireblocksProperties,
) {
    init {
        val maximumRecoveryAndSubmissionMillis =
            Math.addExact(
                fireblocksProperties.maximumCallMillis,
                fireblocksProperties.maximumSubmissionFlowMillis,
            )
        require(properties.boostClaimTtlMillis > maximumRecoveryAndSubmissionMillis) {
            "boost claim TTL must be longer than the maximum Fireblocks recovery and submission flow"
        }
    }
}

@Component
class LoggingStallAlertAdapter : StallAlertPort {
    override fun alert(alert: StallAlert) {
        logger.error(
            "거래 막힘 경보 rootVendorTransactionId={} activeVendorTransactionId={} reason={} observedTransactionHash={}",
            alert.rootVendorTransactionId,
            alert.activeVendorTransactionId,
            alert.reason,
            alert.observedTransactionHash,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(LoggingStallAlertAdapter::class.java)
    }
}
