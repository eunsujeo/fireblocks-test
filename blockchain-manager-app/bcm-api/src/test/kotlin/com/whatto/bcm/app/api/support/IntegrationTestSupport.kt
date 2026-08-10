package com.whatto.bcm.app.api.support

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * 모듈 공용 PostgreSQL 컨테이너 — 싱글턴 재사용 (docs/testing.md: 테스트마다 새로 띄우지 않는다).
 * @Container 생명주기 대신 수동 start — JVM 종료까지 재사용, Testcontainers 가 정리한다.
 * PostgreSQL·Kafka 컨테이너를 JVM 동안 하나씩 재사용한다.
 */
abstract class IntegrationTestSupport {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:17-alpine")
                .apply { start() }

        @JvmStatic
        val kafka: KafkaContainer =
            KafkaContainer("apache/kafka-native:3.9.1")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun disableBackgroundWorker(registry: DynamicPropertyRegistry) {
            registry.add("bcm.webhook-worker.enabled") { "false" }
            registry.add("bcm.outbox-relay.enabled") { "false" }
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }
}
