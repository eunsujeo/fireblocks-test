package com.whatto.bcm.infra.messaging

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate

@Configuration(proxyBeanMethods = false)
class KafkaProducerConfig(
    @param:Value("\${spring.kafka.bootstrap-servers:}") private val bootstrapServers: String,
    @param:Value("\${bcm.outbox-relay.send-timeout-millis:5000}") private val sendTimeoutMillis: Long,
) {
    init {
        require(bootstrapServers.isNotBlank()) { "spring.kafka.bootstrap-servers must not be blank" }
        require(sendTimeoutMillis in 1..Int.MAX_VALUE) { "bcm.outbox-relay.send-timeout-millis is out of range" }
    }

    @Bean
    fun kafkaTemplate(): KafkaTemplate<String, String> =
        KafkaTemplate(
            DefaultKafkaProducerFactory(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.ACKS_CONFIG to "all",
                    ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                    // 애플리케이션 대기보다 producer 전달 시한을 먼저 끝내 뒤늦은 성공과 중복 재발행을 막는다.
                    ProducerConfig.LINGER_MS_CONFIG to 0,
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to sendTimeoutMillis.toInt(),
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to sendTimeoutMillis.toInt(),
                    ProducerConfig.MAX_BLOCK_MS_CONFIG to sendTimeoutMillis,
                ),
            ),
        )
}
