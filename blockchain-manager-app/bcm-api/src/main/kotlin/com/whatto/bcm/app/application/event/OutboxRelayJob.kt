package com.whatto.bcm.app.application.event

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "bcm.outbox-relay", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OutboxRelayJob(
    private val processor: OutboxRelayProcessor,
    @param:Value("\${bcm.outbox-relay.max-events-per-run:100}") private val maxEventsPerRun: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(maxEventsPerRun > 0) { "bcm.outbox-relay.max-events-per-run must be positive" }
    }

    @Scheduled(fixedDelayString = "\${bcm.outbox-relay.fixed-delay-millis:500}")
    fun relayNext() {
        try {
            repeat(maxEventsPerRun) {
                if (processor.relayNext() !is OutboxRelayOutcome.Published) return
            }
        } catch (exception: RuntimeException) {
            logger.error("Outbox relay failed", exception)
        }
    }
}
