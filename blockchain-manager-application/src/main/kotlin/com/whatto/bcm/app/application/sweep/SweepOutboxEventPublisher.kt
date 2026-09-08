package com.whatto.bcm.app.application.sweep

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.sweep.SweepChainStatus
import com.whatto.bcm.domain.sweep.SweepEventPayload
import com.whatto.bcm.domain.sweep.SweepEventPublisher
import com.whatto.bcm.domain.sweep.SweepEventSerializer
import com.whatto.bcm.domain.sweep.SweepItemOutcomeEvent
import com.whatto.bcm.support.id.UuidV7Generator
import com.whatto.bcm.support.time.BusinessDates
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class SweepOutboxEventPublisher(
    private val outbox: OutboxEventService,
    private val serializer: SweepEventSerializer,
    private val clock: Clock,
    @param:Value("\${bcm.webhook-worker.outbox-max-attempts:5}") private val maxRetryCount: Int,
) : SweepEventPublisher {
    private val eventIds = UuidV7Generator(clock)

    init {
        require(maxRetryCount > 0) { "sweep outbox max retry count must be positive" }
    }

    override fun publish(events: List<SweepItemOutcomeEvent>) {
        outbox.enqueue(events.map(::outboxEvent))
    }

    private fun outboxEvent(event: SweepItemOutcomeEvent): OutboxEvent {
        val eventId = eventIds.nextId()
        val payload =
            SweepEventPayload(
                eventId = eventId,
                sweepRequestId = event.sweepRequestId,
                sweepItemId = event.sweepItemId,
                executionId = event.executionId,
                txId = event.txId,
                vendorTxId = event.vendorTxId,
                txHash = event.txHash,
                accountId = event.accountId,
                network = event.network,
                symbol = event.symbol,
                requestedAmount = event.requestedAmount,
                actualAmount = event.actualAmount,
                chainStatus = event.chainStatus,
                itemOutcome = event.itemOutcome,
                failureCode = event.failureCode,
            )
        return OutboxEvent(
            eventId = eventId,
            eventDate = BusinessDates.now(clock),
            vendorTransactionId = event.txId ?: event.sweepItemId,
            eventType =
                if (event.chainStatus == SweepChainStatus.FAILED) {
                    OutboxEventType.FAILED
                } else {
                    OutboxEventType.CONFIRMED
                },
            topic = SWEEP_TOPIC,
            payload = serializer.serialize(payload),
            maxRetryCount = maxRetryCount,
            traceId = event.executionId ?: event.sweepItemId,
        )
    }

    private companion object {
        const val SWEEP_TOPIC = "sweep-events"
    }
}
