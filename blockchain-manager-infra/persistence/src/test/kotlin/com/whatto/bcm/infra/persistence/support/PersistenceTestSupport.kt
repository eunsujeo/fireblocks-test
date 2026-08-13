package com.whatto.bcm.infra.persistence.support

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * 모듈 공용 PostgreSQL 컨테이너 — 싱글턴 재사용 (docs/testing.md: 테스트마다 새로 띄우지 않는다).
 * @Container 생명주기 대신 수동 start — JVM 종료까지 재사용, Testcontainers 가 정리한다.
 */
abstract class PersistenceTestSupport {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:17-alpine")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun limitConnectionPool(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.hikari.maximum-pool-size") { 4 }
        }
    }
}
