package com.whatto.bcm.app.bat.support

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.postgresql.PostgreSQLContainer

/** bcm-bat 통합 테스트가 JVM 안에서 공유하는 PostgreSQL 컨테이너. */
abstract class IntegrationTestSupport {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:17-alpine")
                .apply { start() }
    }
}
