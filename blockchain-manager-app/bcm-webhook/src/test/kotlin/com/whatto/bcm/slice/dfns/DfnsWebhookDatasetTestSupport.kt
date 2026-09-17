package com.whatto.bcm.slice.dfns

import com.whatto.bcm.testsupport.database.ProviderOriginTestDatabase
import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
import org.springframework.test.context.DynamicPropertyRegistry

/**
 * 공용 컨테이너 안의 별도 Dfns 데이터셋 — Fireblocks 데이터셋과 섞지 않는다.
 * 모듈 안의 여러 슬라이스가 같은 데이터셋을 쓰므로 JVM당 한 번만 만든다(CREATE DATABASE는 반복할 수 없다).
 */
object DfnsWebhookDatasetTestSupport {
    private val jdbcUrl: String by lazy {
        ProviderOriginTestDatabase.createDfns(
            IntegrationTestSupport.postgres.jdbcUrl,
            IntegrationTestSupport.postgres.username,
            IntegrationTestSupport.postgres.password,
        )
    }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { jdbcUrl }
        registry.add("spring.datasource.username") { IntegrationTestSupport.postgres.username }
        registry.add("spring.datasource.password") { IntegrationTestSupport.postgres.password }
        registry.add("spring.datasource.hikari.maximum-pool-size") { 3 }
        registry.add("spring.datasource.hikari.minimum-idle") { 0 }
    }
}
