package com.whatto.bcm.app.application.event

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.EventPublisher
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.event.OutboxFailureResult
import com.whatto.bcm.domain.event.PendingOutboxEvent
import com.whatto.bcm.support.time.CoreDateTimes
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Clock

sealed interface OutboxRelayOutcome {
    data object NoWork : OutboxRelayOutcome

    data class Published(
        val eventId: String,
    ) : OutboxRelayOutcome

    data class Retrying(
        val eventId: String,
        val retryCount: Int,
    ) : OutboxRelayOutcome

    data class Quarantined(
        val eventId: String,
        val retryCount: Int,
    ) : OutboxRelayOutcome
}

@Service
class OutboxRelayTransaction(
    private val transactionRunner: TransactionRunner,
    private val outbox: OutboxEventRepository,
    private val publisher: EventPublisher,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun relayNext(): OutboxRelayOutcome = transactionRunner.run { relayNextInTransaction() }

    private fun relayNextInTransaction(): OutboxRelayOutcome {
        if (!outbox.tryAcquireRelayLock()) return OutboxRelayOutcome.NoWork
        val event = outbox.findNextPendingForUpdate() ?: return OutboxRelayOutcome.NoWork
        val partitionKey = partitionKey(event) ?: return failed(event, INVALID_PAYLOAD_REASON)
        val attemptedAt = CoreDateTimes.now(clock)
        outbox.markDispatched(event.eventId, attemptedAt)
        try {
            publisher.publish(event.topic, partitionKey, event.payload)
        } catch (exception: RuntimeException) {
            logger.error("Kafka publish failed: eventId={}, topic={}", event.eventId, event.topic, exception)
            return failed(event, PUBLISH_FAILURE_REASON, attemptedAt)
        }
        outbox.markSuccess(event.eventId)
        return OutboxRelayOutcome.Published(event.eventId)
    }

    private fun partitionKey(event: PendingOutboxEvent): String? {
        val accountId =
            try {
                objectMapper.readTree(event.payload).path("accountId").asString()
            } catch (exception: JacksonException) {
                logger.error("Invalid outbox payload: eventId={}, topic={}", event.eventId, event.topic, exception)
                return null
            }
        return accountId.takeIf { it.isNotBlank() }
    }

    private fun failed(
        event: PendingOutboxEvent,
        safeReason: String,
        attemptedAt: String = CoreDateTimes.now(clock),
    ): OutboxRelayOutcome {
        val failure = outbox.recordFailure(event.eventId, safeReason, attemptedAt)
        return failure.toOutcome(event.eventId)
    }

    private fun OutboxFailureResult.toOutcome(eventId: String): OutboxRelayOutcome =
        if (quarantined) {
            OutboxRelayOutcome.Quarantined(eventId, retryCount)
        } else {
            OutboxRelayOutcome.Retrying(eventId, retryCount)
        }

    private companion object {
        val logger = LoggerFactory.getLogger(OutboxRelayTransaction::class.java)
        const val INVALID_PAYLOAD_REASON = "Invalid outbox payload"
        const val PUBLISH_FAILURE_REASON = "Kafka publish failed"
    }
}
