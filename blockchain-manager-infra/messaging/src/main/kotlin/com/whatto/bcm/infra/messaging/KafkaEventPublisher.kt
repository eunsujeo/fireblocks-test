package com.whatto.bcm.infra.messaging

import com.whatto.bcm.domain.event.EventPublisher
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @param:Value("\${bcm.outbox-relay.send-timeout-millis:5000}") private val sendTimeoutMillis: Long,
) : EventPublisher {
    override fun publish(
        topic: String,
        partitionKey: String,
        payload: String,
    ) {
        try {
            kafkaTemplate.send(topic, partitionKey, payload).get(sendTimeoutMillis + WAIT_MARGIN_MILLIS, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KafkaEventPublishingException(exception)
        } catch (exception: Exception) {
            throw KafkaEventPublishingException(exception)
        }
    }

    private companion object {
        const val WAIT_MARGIN_MILLIS = 1_000L
    }
}

class KafkaEventPublishingException(
    cause: Throwable,
) : RuntimeException("Kafka event publishing failed", cause)
