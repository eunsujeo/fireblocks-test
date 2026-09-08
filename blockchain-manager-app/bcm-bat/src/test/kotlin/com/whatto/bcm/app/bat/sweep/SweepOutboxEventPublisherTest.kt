package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.sweep.SweepOutboxEventPublisher
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.event.OutboxFailureResult
import com.whatto.bcm.domain.event.PendingOutboxEvent
import com.whatto.bcm.domain.sweep.SweepChainStatus
import com.whatto.bcm.domain.sweep.SweepEventPayload
import com.whatto.bcm.domain.sweep.SweepEventSerializer
import com.whatto.bcm.domain.sweep.SweepItemOutcome
import com.whatto.bcm.domain.sweep.SweepItemOutcomeEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SweepOutboxEventPublisherTest {
    private val outbox = RecordingOutbox()
    private val serializer = RecordingSerializer()
    private val publisher =
        SweepOutboxEventPublisher(
            OutboxEventService(outbox),
            serializer,
            Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC),
            5,
        )

    @Test
    fun `항목별 결과는 account partition payload와 sweep-events outbox를 만든다`() {
        publisher.publish(
            listOf(
                SweepItemOutcomeEvent(
                    sweepRequestId = "request-1",
                    sweepItemId = "item-1",
                    executionId = "execution-1",
                    txId = "tx-1",
                    vendorTxId = "tx-1",
                    txHash = "0xhash",
                    accountId = "account-1",
                    network = "BASE",
                    symbol = "USDC",
                    requestedAmount = "10",
                    actualAmount = "9",
                    chainStatus = SweepChainStatus.FINALIZED,
                    itemOutcome = SweepItemOutcome.FAILED,
                    failureCode = "LEG_FAILED",
                ),
            ),
        )

        assertThat(UUID.fromString(serializer.payload.eventId).version()).isEqualTo(7)
        assertThat(serializer.payload.sweepItemId).isEqualTo("item-1")
        assertThat(serializer.payload.chainStatus).isEqualTo(SweepChainStatus.FINALIZED)
        assertThat(serializer.payload.itemOutcome).isEqualTo(SweepItemOutcome.FAILED)
        assertThat(outbox.events.single().topic).isEqualTo("sweep-events")
        assertThat(outbox.events.single().eventType).isEqualTo(OutboxEventType.CONFIRMED)
        assertThat(outbox.events.single().vendorTransactionId).isEqualTo("tx-1")
        assertThat(outbox.events.single().traceId).isEqualTo("execution-1")
        assertThat(outbox.events.single().payload).isEqualTo("serialized")
    }

    @Test
    fun `온체인 제출 없는 완료는 물리 거래 식별자 없이 성공 이벤트로 발행한다`() {
        publisher.publish(
            listOf(
                SweepItemOutcomeEvent(
                    sweepRequestId = "request-2",
                    sweepItemId = "item-2",
                    executionId = null,
                    txId = null,
                    vendorTxId = null,
                    txHash = null,
                    accountId = "account-2",
                    network = "BASE",
                    symbol = "USDC",
                    requestedAmount = "0",
                    actualAmount = "0",
                    chainStatus = SweepChainStatus.NOT_SUBMITTED,
                    itemOutcome = SweepItemOutcome.NO_SWEEP_REQUIRED,
                    failureCode = null,
                ),
            ),
        )

        assertThat(serializer.payload.executionId).isNull()
        assertThat(serializer.payload.txId).isNull()
        assertThat(serializer.payload.vendorTxId).isNull()
        assertThat(outbox.events.single().eventType).isEqualTo(OutboxEventType.CONFIRMED)
        assertThat(outbox.events.single().vendorTransactionId).isEqualTo("item-2")
        assertThat(outbox.events.single().traceId).isEqualTo("item-2")
    }

    private class RecordingSerializer : SweepEventSerializer {
        lateinit var payload: SweepEventPayload

        override fun serialize(event: SweepEventPayload): String {
            payload = event
            return "serialized"
        }
    }

    private class RecordingOutbox : OutboxEventRepository {
        var events: List<OutboxEvent> = emptyList()

        override fun insertAll(events: List<OutboxEvent>) {
            this.events = events
        }

        override fun tryAcquireRelayLock() = false

        override fun findNextPendingForUpdate(): PendingOutboxEvent? = null

        override fun markDispatched(
            eventId: String,
            publishedAt: String,
        ) = Unit

        override fun markSuccess(eventId: String) = Unit

        override fun recordFailure(
            eventId: String,
            safeReason: String,
            attemptedAt: String,
        ) = OutboxFailureResult(0, false)
    }
}
