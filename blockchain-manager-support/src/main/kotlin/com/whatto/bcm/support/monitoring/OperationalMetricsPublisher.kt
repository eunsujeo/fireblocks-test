package com.whatto.bcm.support.monitoring

import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.monitoring.OperationalSignalRepository
import com.whatto.bcm.domain.monitoring.VendorCallMetricOutcome
import com.whatto.bcm.domain.monitoring.WebhookIngestionMetricOutcome
import com.whatto.bcm.support.time.CoreDateTimes
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class OperationalMetricsPublisher(
    registry: MeterRegistry,
    private val signals: OperationalSignalRepository,
    private val clock: Clock,
) : OperationalMetricsPort {
    private val meterRegistry = registry
    private val pendingWebhooks = AtomicLong()
    private val oldestPendingWebhookAgeSeconds = AtomicLong()
    private val pendingOutbox = AtomicLong()
    private val oldestPendingOutboxAgeSeconds = AtomicLong()
    private val lastWebhookReceivedTimestampSeconds = AtomicLong()
    private val reconciliationMissing = AtomicLong()
    private val stoppedReconciliation = AtomicLong()
    private val unarchivedCompletedWebhooks = AtomicLong()
    private val webhookIngestion =
        WebhookIngestionMetricOutcome.entries.associateWith { outcome ->
            Counter
                .builder("bcm.webhook.ingestion")
                .description("Webhook ingestion results")
                .tag("outcome", outcome.tagValue)
                .register(registry)
        }
    private val vendorCalls = ConcurrentHashMap<Pair<String, VendorCallMetricOutcome>, Counter>()
    private val reconciliationRecovered =
        Counter
            .builder("bcm.tx.reconciliation.recovered")
            .description("Webhooks recovered by transaction reconciliation")
            .register(registry)
    private val jobLastRun =
        MultiGauge
            .builder("bcm.job.last.run.timestamp.seconds")
            .description("UTC epoch seconds of the latest job execution")
            .register(registry)
    private val jobLastSuccess =
        MultiGauge
            .builder("bcm.job.last.success.timestamp.seconds")
            .description("UTC epoch seconds of the latest successful job execution")
            .register(registry)

    init {
        gauge(registry, "bcm.webhook.inbox.pending", "Pending webhook inbox depth", pendingWebhooks)
        gauge(
            registry,
            "bcm.webhook.inbox.oldest.age.seconds",
            "Age in seconds of the oldest pending webhook",
            oldestPendingWebhookAgeSeconds,
        )
        gauge(registry, "bcm.outbox.pending", "Pending outbox depth", pendingOutbox)
        gauge(
            registry,
            "bcm.outbox.oldest.age.seconds",
            "Age in seconds of the oldest pending outbox event",
            oldestPendingOutboxAgeSeconds,
        )
        gauge(
            registry,
            "bcm.webhook.last.received.timestamp.seconds",
            "UTC epoch seconds of the latest accepted webhook",
            lastWebhookReceivedTimestampSeconds,
        )
        gauge(
            registry,
            "bcm.tx.reconciliation.missing",
            "Webhooks recovered by the latest transaction reconciliation run",
            reconciliationMissing,
        )
        gauge(
            registry,
            "bcm.tx.reconciliation.stopped",
            "Non-finalized transactions whose automatic reconciliation tracking has stopped",
            stoppedReconciliation,
        )
        gauge(
            registry,
            "bcm.webhook.completed.unarchived",
            "Finalized transactions with a completed webhook not yet archived",
            unarchivedCompletedWebhooks,
        )
    }

    override fun recordWebhookIngestion(
        outcome: WebhookIngestionMetricOutcome,
        receivedAt: String?,
    ) {
        webhookIngestion.getValue(outcome).increment()
        if (outcome == WebhookIngestionMetricOutcome.ACCEPTED) {
            lastWebhookReceivedTimestampSeconds.set(CoreDateTimes.toEpochMillis(checkNotNull(receivedAt)) / 1_000)
        }
    }

    override fun recordVendorCall(
        operation: String,
        outcome: VendorCallMetricOutcome,
    ) {
        vendorCalls
            .computeIfAbsent(operation to outcome) {
                Counter
                    .builder("bcm.vendor.calls")
                    .description("Fireblocks API call results")
                    .tags("operation", operation, "outcome", outcome.tagValue)
                    .register(meterRegistry)
            }.increment()
    }

    override fun recordReconciliation(recoveredCount: Int) {
        require(recoveredCount >= 0) { "recoveredCount must not be negative" }
        reconciliationMissing.set(recoveredCount.toLong())
        reconciliationRecovered.increment(recoveredCount.toDouble())
    }

    @Scheduled(
        fixedDelayString = "\${bcm.operational-metrics.fixed-delay-millis:60000}",
        initialDelayString = "\${bcm.operational-metrics.initial-delay-millis:60000}",
    )
    fun refresh() {
        refreshSignal(
            "webhook-inbox",
            {
                pendingWebhooks.set(UNAVAILABLE)
                oldestPendingWebhookAgeSeconds.set(UNAVAILABLE)
            },
        ) {
            val backlog = signals.pendingWebhookBacklog()
            pendingWebhooks.set(backlog.count)
            oldestPendingWebhookAgeSeconds.set(ageSeconds(backlog.oldestAt))
        }
        refreshSignal(
            "outbox",
            {
                pendingOutbox.set(UNAVAILABLE)
                oldestPendingOutboxAgeSeconds.set(UNAVAILABLE)
            },
        ) {
            val backlog = signals.pendingOutboxBacklog()
            pendingOutbox.set(backlog.count)
            oldestPendingOutboxAgeSeconds.set(ageSeconds(backlog.oldestAt))
        }
        refreshSignal("reconciliation-stopped", { stoppedReconciliation.set(UNAVAILABLE) }) {
            stoppedReconciliation.set(signals.stoppedReconciliationCount())
        }
        refreshSignal("completed-unarchived", { unarchivedCompletedWebhooks.set(UNAVAILABLE) }) {
            unarchivedCompletedWebhooks.set(signals.unarchivedCompletedWebhookCount())
        }
        refreshSignal(
            "job-heartbeat",
            {
                jobLastRun.register(emptyList(), true)
                jobLastSuccess.register(emptyList(), true)
            },
        ) {
            val heartbeats = signals.heartbeats()
            jobLastRun.register(
                heartbeats.map { MultiGauge.Row.of(Tags.of("job", it.jobName), timestampSeconds(it.lastRunAt)) },
                true,
            )
            jobLastSuccess.register(
                heartbeats.mapNotNull { heartbeat ->
                    heartbeat.lastSucceededAt?.let {
                        MultiGauge.Row.of(Tags.of("job", heartbeat.jobName), timestampSeconds(it))
                    }
                },
                true,
            )
        }
    }

    private inline fun refreshSignal(
        signal: String,
        onFailure: () -> Unit,
        refresh: () -> Unit,
    ) {
        try {
            refresh()
            recordSignalRefresh(signal, "success")
        } catch (exception: Exception) {
            onFailure()
            recordSignalRefresh(signal, "failed")
            logger.error("Operational signal refresh failed signal={}", signal, exception)
        }
    }

    private fun recordSignalRefresh(
        signal: String,
        outcome: String,
    ) {
        meterRegistry
            .counter("bcm.operational.signal.refresh", "signal", signal, "outcome", outcome)
            .increment()
    }

    private fun ageSeconds(coreDateTime: String?): Long =
        coreDateTime
            ?.let { ((clock.millis() - CoreDateTimes.toEpochMillis(it)) / 1_000).coerceAtLeast(0) }
            ?: 0

    private fun timestampSeconds(coreDateTime: String): Double = CoreDateTimes.toEpochMillis(coreDateTime) / 1_000.0

    private fun gauge(
        registry: MeterRegistry,
        name: String,
        description: String,
        value: AtomicLong,
    ) {
        Gauge
            .builder(name, value) { it.get().toDouble() }
            .description(description)
            .register(registry)
    }

    private companion object {
        const val UNAVAILABLE = -1L
        val logger = LoggerFactory.getLogger(OperationalMetricsPublisher::class.java)
    }
}
