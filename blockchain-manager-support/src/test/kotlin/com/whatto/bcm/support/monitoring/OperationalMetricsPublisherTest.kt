package com.whatto.bcm.support.monitoring

import com.whatto.bcm.domain.monitoring.JobHeartbeat
import com.whatto.bcm.domain.monitoring.OperationalBacklog
import com.whatto.bcm.domain.monitoring.OperationalSignalRepository
import com.whatto.bcm.domain.monitoring.VendorCallMetricOutcome
import com.whatto.bcm.domain.monitoring.WebhookIngestionMetricOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OperationalMetricsPublisherTest {
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-08-17T01:10:00Z"), ZoneOffset.UTC)

    @Test
    fun `DB snapshot을 적체 깊이와 지연 및 작업 heartbeat gauge로 발행한다`() {
        val publisher = OperationalMetricsPublisher(registry, StubSignals, clock)

        publisher.refresh()

        assertThat(gauge("bcm.webhook.inbox.pending")).isEqualTo(2.0)
        assertThat(gauge("bcm.webhook.inbox.oldest.age.seconds")).isEqualTo(600.0)
        assertThat(gauge("bcm.outbox.pending")).isEqualTo(3.0)
        assertThat(gauge("bcm.outbox.oldest.age.seconds")).isEqualTo(300.0)
        assertThat(gauge("bcm.tx.reconciliation.stopped")).isEqualTo(4.0)
        assertThat(gauge("bcm.webhook.completed.unarchived")).isEqualTo(5.0)
        assertThat(gauge("bcm.job.last.run.timestamp.seconds", "job", "tx-reconciliation"))
            .isEqualTo(1_786_928_760.0)
        assertThat(gauge("bcm.job.last.success.timestamp.seconds", "job", "tx-reconciliation"))
            .isEqualTo(1_786_928_820.0)
    }

    @Test
    fun `수신과 벤더 호출 및 대사 누락 결과를 counter와 gauge로 발행한다`() {
        val publisher = OperationalMetricsPublisher(registry, StubSignals, clock)

        publisher.recordWebhookIngestion(WebhookIngestionMetricOutcome.ACCEPTED, "20260817010900")
        publisher.recordWebhookIngestion(WebhookIngestionMetricOutcome.INVALID_SIGNATURE, null)
        publisher.recordWebhookIngestion(WebhookIngestionMetricOutcome.ERROR, null)
        publisher.recordVendorCall("transactions", VendorCallMetricOutcome.SUCCESS)
        publisher.recordVendorCall("transactions", VendorCallMetricOutcome.RATE_LIMITED)
        publisher.recordVendorCall("transactions", VendorCallMetricOutcome.ERROR)
        publisher.recordReconciliation(2)
        publisher.recordReconciliation(0)

        assertThat(gauge("bcm.webhook.last.received.timestamp.seconds")).isEqualTo(1_786_928_940.0)
        assertThat(counter("bcm.webhook.ingestion", "outcome", "accepted")).isEqualTo(1.0)
        assertThat(counter("bcm.webhook.ingestion", "outcome", "invalid_signature")).isEqualTo(1.0)
        assertThat(counter("bcm.webhook.ingestion", "outcome", "error")).isEqualTo(1.0)
        assertThat(counter("bcm.vendor.calls", "outcome", "success", "operation", "transactions")).isEqualTo(1.0)
        assertThat(counter("bcm.vendor.calls", "outcome", "rate_limited", "operation", "transactions")).isEqualTo(1.0)
        assertThat(counter("bcm.vendor.calls", "outcome", "error", "operation", "transactions")).isEqualTo(1.0)
        assertThat(gauge("bcm.tx.reconciliation.missing")).isZero()
        assertThat(counter("bcm.tx.reconciliation.recovered")).isEqualTo(2.0)
    }

    @Test
    fun `한 DB 신호 조회가 실패해도 다른 신호를 갱신하고 실패를 노출한다`() {
        val failingSignals =
            object : OperationalSignalRepository by StubSignals {
                override fun pendingWebhookBacklog(): OperationalBacklog = error("inbox unavailable")
            }
        val publisher = OperationalMetricsPublisher(registry, failingSignals, clock)

        publisher.refresh()

        assertThat(gauge("bcm.webhook.inbox.pending")).isEqualTo(-1.0)
        assertThat(gauge("bcm.outbox.pending")).isEqualTo(3.0)
        assertThat(counter("bcm.operational.signal.refresh", "signal", "webhook-inbox", "outcome", "failed"))
            .isEqualTo(1.0)
        assertThat(counter("bcm.operational.signal.refresh", "signal", "outbox", "outcome", "success"))
            .isEqualTo(1.0)
    }

    private fun gauge(
        name: String,
        tagKey: String? = null,
        tagValue: String? = null,
    ): Double {
        val search = registry.get(name)
        return if (tagKey == null) search.gauge().value() else search.tag(tagKey, checkNotNull(tagValue)).gauge().value()
    }

    private fun counter(
        name: String,
        vararg tags: String,
    ): Double =
        registry
            .get(name)
            .tags(*tags)
            .counter()
            .count()

    private object StubSignals : OperationalSignalRepository {
        override fun pendingWebhookBacklog() = OperationalBacklog(2, "20260817010000")

        override fun pendingOutboxBacklog() = OperationalBacklog(3, "20260817010500")

        override fun stoppedReconciliationCount() = 4L

        override fun unarchivedCompletedWebhookCount() = 5L

        override fun heartbeats() =
            listOf(
                JobHeartbeat(
                    jobName = "tx-reconciliation",
                    lastRunAt = "20260817010600",
                    lastSucceededAt = "20260817010700",
                ),
            )
    }
}
