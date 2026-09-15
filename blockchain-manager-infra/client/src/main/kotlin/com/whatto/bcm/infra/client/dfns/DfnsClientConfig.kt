package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.infra.client.config.ConditionalOnDfnsProtocol
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Dfns 조립 — `bcm.dfns.*` 바인딩, Key credential 서명기, 지갑 HTTP 어댑터(생성/조회·제출 snapshot·지갑 자산 포트), 자산 등록 관문. `BCM_PROVIDER=dfns`에서만 만들어진다.
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

    /** Admin 자산 등록의 Dfns 관문 — 벤더 HTTP 호출 없이 설정·데이터셋 네트워크 행·자산 모델·주소 형식을 대조한다. */
    @Bean
    fun dfnsChainAssetResolver(properties: DfnsProperties): DfnsChainAssetResolver = DfnsChainAssetResolver(properties)

    @Bean
    fun dfnsNetworkWalletClient(
        restClientBuilder: RestClient.Builder,
        properties: DfnsProperties,
        origin: ProviderOrigin,
        signer: DfnsCredentialSigner,
        metrics: OperationalMetricsPort,
        factory: DfnsRestClientFactory,
    ): DfnsNetworkWalletClient = DfnsNetworkWalletClient(restClientBuilder, properties, origin, signer, metrics, factory)
}
