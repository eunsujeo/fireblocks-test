package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkChainEventParser
import com.whatto.bcm.domain.vendor.NetworkTransferEventParser
import com.whatto.bcm.domain.webhook.WebhookProtocol
import com.whatto.bcm.domain.webhook.WebhookSignatureVerifier
import com.whatto.bcm.infra.client.config.ConditionalOnDfnsProtocol
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock

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

    /** 웹훅 envelope 해석 — 모든 앱에서 만들 수 있다(secret 불필요). */
    @Bean
    fun dfnsWebhookProtocol(objectMapper: ObjectMapper): WebhookProtocol = DfnsWebhookProtocol(objectMapper)

    /** 온체인 이동(입금) 사건 해석 — 판단 워커가 소비한다. */
    @Bean
    fun dfnsNetworkTransferEventParser(
        objectMapper: ObjectMapper,
        properties: DfnsProperties,
    ): NetworkTransferEventParser = DfnsNetworkTransferEventParser(objectMapper, properties)

    @Bean
    fun dfnsNetworkChainEventParser(
        objectMapper: ObjectMapper,
        properties: DfnsProperties,
    ): NetworkChainEventParser = DfnsNetworkChainEventParser(objectMapper, properties)

    /**
     * 웹훅 HMAC 검증기 — Webhook 수신 앱에서만 조립한다(`bcm.dfns.webhook-secrets` 필수, 빈 생성 시점에 검사).
     * API/BAT는 `bcm.webhook.ingestion.enabled`를 켜지 않으므로 secret 없이 기동한다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "bcm.webhook.ingestion", name = ["enabled"], havingValue = "true")
    fun dfnsWebhookSignatureVerifier(
        properties: DfnsProperties,
        clock: Clock,
        objectMapper: ObjectMapper,
    ): WebhookSignatureVerifier =
        DfnsWebhookSignatureVerifier(properties.requireWebhookSecrets(), properties.webhookReplayToleranceSeconds, clock, objectMapper)

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
