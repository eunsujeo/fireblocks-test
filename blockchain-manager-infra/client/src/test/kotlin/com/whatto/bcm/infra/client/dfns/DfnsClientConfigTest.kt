package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.ChainAssetResolver
import com.whatto.bcm.domain.vendor.NetworkWalletAssetPort
import com.whatto.bcm.domain.vendor.NetworkWalletProvisioningPort
import com.whatto.bcm.domain.webhook.WebhookProtocol
import com.whatto.bcm.domain.webhook.WebhookSignatureVerifier
import com.whatto.bcm.infra.client.dfns.fixture.DfnsTestKeyFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * Dfns 조립부 계약 — 웹훅 envelope 해석은 모든 앱에서, HMAC 검증기는 웹훅 수신 앱(`bcm.webhook.ingestion.enabled=true`)에서만 만들고
 * secret 누락은 첫 수신이 아니라 조립에서 실패한다. `BCM_PROVIDER=dfns` 전체 기동 차단(ProviderConfiguration)은 여기서 검사하지 않는다.
 */
class DfnsClientConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(DfnsClientConfig::class.java)
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withBean(RestClient.Builder::class.java, { RestClient.builder() })
            .withBean(OperationalMetricsPort::class.java, { NoOpOperationalMetricsPort })
            .withBean(ProviderOrigin::class.java, { ProviderOrigin("o", "dfns", "dfns", "p", "org", "TESTNET") })
            .withPropertyValues(
                "bcm.provider=dfns",
                "bcm.dfns.base-url=https://baseline.dfns.internal.test",
                "bcm.dfns.auth-token=test-token",
                "bcm.dfns.credential-id=${DfnsTestKeyFixture.CREDENTIAL_ID}",
                "bcm.dfns.credential-private-key-pem=${DfnsTestKeyFixture.pem(DfnsTestKeyFixture.ecKeyPair())}",
                "bcm.dfns.networks.ETHEREUM_SEPOLIA=EthereumSepolia",
            )

    @Test
    fun `API 조립은 지갑 어댑터·관문·웹훅 envelope 해석을 만들고 HMAC 검증기는 만들지 않는다`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(NetworkWalletProvisioningPort::class.java)
            assertThat(context).hasSingleBean(NetworkWalletAssetPort::class.java)
            assertThat(context).hasSingleBean(ChainAssetResolver::class.java)
            assertThat(context.getBean(WebhookProtocol::class.java)).isInstanceOf(DfnsWebhookProtocol::class.java)
            assertThat(context).doesNotHaveBean(WebhookSignatureVerifier::class.java)
        }
    }

    @Test
    fun `웹훅 수신 조립은 secret이 있어야 HMAC 검증기를 만들고 없으면 조립에서 실패한다`() {
        runner
            .withPropertyValues("bcm.webhook.ingestion.enabled=true", "bcm.dfns.webhook-secrets=test-secret-1,test-secret-2")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(WebhookSignatureVerifier::class.java)).isInstanceOf(DfnsWebhookSignatureVerifier::class.java)
            }
        runner.withPropertyValues("bcm.webhook.ingestion.enabled=true").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("bcm.dfns.webhook-secrets is required")
        }
    }
}
