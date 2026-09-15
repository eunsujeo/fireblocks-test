package com.whatto.bcm.app.api.admin

import com.whatto.bcm.domain.provider.ProviderOrigin
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/** Admin 슬라이스 테스트의 데이터셋 원천 — 실행 모듈에서는 `ProviderOriginConfiguration`이 DB 대조 후 제공한다. */
@TestConfiguration(proxyBeanMethods = false)
class FireblocksOriginTestConfiguration {
    @Bean
    fun providerOrigin(): ProviderOrigin =
        ProviderOrigin("test-fireblocks-origin", "fireblocks", "fireblocks", "test-platform", "test-organization", "TESTNET")
}

@TestConfiguration(proxyBeanMethods = false)
class DfnsOriginTestConfiguration {
    @Bean
    fun providerOrigin(): ProviderOrigin =
        ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET")
}
