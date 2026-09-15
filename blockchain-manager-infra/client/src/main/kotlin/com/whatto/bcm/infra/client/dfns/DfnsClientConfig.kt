package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletProvisioningPort
import com.whatto.bcm.infra.client.config.ConditionalOnDfnsProtocol
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Dfns 조립 — `bcm.dfns.*` 바인딩, Key credential 서명기, 지갑 HTTP 어댑터. `BCM_PROVIDER=dfns`에서만 만들어진다.
 * 설정 검증(`requireConnection`)은 빈 생성 시점에 수행하므로 자격 누락은 첫 호출이 아니라 조립에서 실패한다.
 * ProviderOrigin 빈은 실행 모듈이 공유하는 `ProviderOriginConfiguration`이 DB 대조 후 제공한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDfnsProtocol
@EnableConfigurationProperties(DfnsProperties::class)
class DfnsClientConfig {
    @Bean
    fun dfnsCredentialSigner(properties: DfnsProperties): DfnsCredentialSigner {
        properties.requireConnection()
        return DfnsCredentialSigner(properties.credentialId, properties.resolveCredentialPrivateKeyPem())
    }

    @Bean
    fun dfnsRestClientFactory(): DfnsRestClientFactory = PooledDfnsRestClientFactory()

    @Bean
    fun dfnsNetworkWalletClient(
        restClientBuilder: RestClient.Builder,
        properties: DfnsProperties,
        origin: ProviderOrigin,
        signer: DfnsCredentialSigner,
        metrics: OperationalMetricsPort,
        factory: DfnsRestClientFactory,
    ): NetworkWalletProvisioningPort = DfnsNetworkWalletClient(restClientBuilder, properties, origin, signer, metrics, factory)
}
