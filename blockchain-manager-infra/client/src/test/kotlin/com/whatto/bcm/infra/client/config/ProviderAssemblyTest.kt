package com.whatto.bcm.infra.client.config

import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.domain.vendor.VendorContractCallPort
import com.whatto.bcm.domain.vendor.VendorExecutionLimits
import com.whatto.bcm.domain.vendor.VendorNetworkFeePort
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorWebhookRecoveryPort
import com.whatto.bcm.domain.vendor.WalletCreationPolicy
import com.whatto.bcm.domain.vendor.WalletVendorPort
import com.whatto.bcm.domain.webhook.WebhookProtocol
import com.whatto.bcm.domain.webhook.WebhookSignatureVerifier
import com.whatto.bcm.domain.webhook.WebhookTransactionParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.time.Clock
import java.util.Base64

class ProviderAssemblyTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(ClientScan::class.java)
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withBean(RestClient.Builder::class.java, { RestClient.builder() })
            .withBean(OperationalMetricsPort::class.java, { NoOpOperationalMetricsPort })

    @ParameterizedTest
    @ValueSource(strings = ["fireblocks", "local"])
    fun `지원 제공자는 모든 공통 포트를 하나씩 조립한다`(provider: String) {
        configured(provider).run { context ->
            assertThat(context).hasNotFailed()
            listOf(
                WalletVendorPort::class.java,
                WalletCreationPolicy::class.java,
                VendorTransactionPort::class.java,
                VendorContractCallPort::class.java,
                VendorAssetCatalogPort::class.java,
                VendorNetworkFeePort::class.java,
                VendorWebhookRecoveryPort::class.java,
                VendorExecutionLimits::class.java,
                VendorStatusTranslator::class.java,
                WebhookSignatureVerifier::class.java,
                WebhookProtocol::class.java,
                WebhookTransactionParser::class.java,
            ).forEach { port -> assertThat(context.getBeansOfType(port)).hasSize(1) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "unknown", "FIREBLOCKS", "fireblocks,dfns"])
    fun `누락되거나 잘못된 선택은 기동을 거절한다`(provider: String) {
        runner.withPropertyValues("bcm.provider=$provider").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("BCM_PROVIDER must be one of fireblocks, dfns, local")
        }
    }

    @Test
    fun `미구현 Dfns는 Fireblocks 설정을 읽거나 클라이언트를 만들기 전에 거절한다`() {
        runner
            .withPropertyValues(
                "bcm.provider=dfns",
                "bcm.fireblocks.max-attempts=not-a-number",
                "bcm.fireblocks.private-key-file=/must-not-read/provider-test.pem",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("BCM_PROVIDER=dfns is not implemented")
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["base-url", "webhook-jwks-url"])
    fun `로컬은 외부 벤더와 키 조회 주소를 거절한다`(property: String) {
        configured("local")
            .withPropertyValues("bcm.fireblocks.$property=https://example.com")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("local requires internal HTTP endpoints")
            }
    }

    @Test
    fun `로컬은 외부 RPC를 거절한다`() {
        configured("local")
            .withPropertyValues("bcm.evm-rpc.networks.ETHEREUM.url=https://example.com")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("local requires internal HTTP endpoints")
            }
    }

    @Test
    fun `로컬은 실제 벤더 자격 사용을 거절한다`() {
        configured("local").withPropertyValues("bcm.fireblocks.api-key=not-the-local-marker").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("local requires bcm-local-stub API key marker")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["api-key", "private-key-pem"])
    fun `선택된 제공자의 자격 누락은 기동을 거절한다`(property: String) {
        configured("fireblocks").withPropertyValues("bcm.fireblocks.$property=").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("bcm.fireblocks.$property")
        }
    }

    @Test
    fun `기존 실행 모드와 선택값이 다르면 거절한다`() {
        configured("local").withPropertyValues("bcm.vendor-mode=FIREBLOCKS").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("BCM_VENDOR_MODE conflicts with BCM_PROVIDER")
        }
    }

    @Test
    fun `BCM_PROVIDER 환경변수로 선택하며 비선택 Dfns 설정은 요구하지 않는다`() {
        configured("fireblocks")
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource("provider-test-env", mapOf("BCM_PROVIDER" to "local")),
                )
            }.withPropertyValues("bcm.dfns.private-key-file=/must-not-read/test.pem")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.environment.getProperty("bcm.provider")).isEqualTo("local")
                assertThat(context).hasSingleBean(WalletVendorPort::class.java)
            }
    }

    @Test
    fun `속성 자체가 없는 경우에도 기본 제공자를 추정하지 않는다`() {
        runner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("BCM_PROVIDER must be one of fireblocks, dfns, local")
        }
    }

    @Test
    fun `잘못된 선택 키는 첫 제출을 기다리지 않고 기동을 거절한다`() {
        configured("fireblocks").withPropertyValues("bcm.fireblocks.private-key-pem=invalid-key").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("fireblocksJwtSigner")
        }
    }

    @Test
    fun `로컬은 비로컬 체인 환경을 거절한다`() {
        configured("local").withPropertyValues("bcm.chain-mode=MAINNET").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("BCM_CHAIN_MODE conflicts with BCM_PROVIDER")
        }
    }

    @Test
    fun `로컬은 기존 Stub 경계와 같은 IPv6 ULA 주소를 허용한다`() {
        configured("local")
            .withPropertyValues(
                "bcm.fireblocks.base-url=http://[fd00::1]:18080",
                "bcm.fireblocks.webhook-jwks-url=http://[fc00::1]:18080/jwks",
                "bcm.evm-rpc.networks.LOCAL.url=http://[::1]:8545",
            ).run { context -> assertThat(context).hasNotFailed() }
    }

    private fun configured(provider: String): ApplicationContextRunner {
        val privateKey =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .private
        val body = Base64.getEncoder().encodeToString(privateKey.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
        return runner.withPropertyValues(
            "bcm.provider=$provider",
            "bcm.fireblocks.api-key=bcm-local-stub",
            "bcm.fireblocks.private-key-pem=$pem",
            "bcm.fireblocks.base-url=http://127.0.0.1:18080",
            "bcm.fireblocks.webhook-jwks-url=http://127.0.0.1:18080/.well-known/jwks.json",
        )
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.whatto.bcm.infra.client")
    class ClientScan
}
