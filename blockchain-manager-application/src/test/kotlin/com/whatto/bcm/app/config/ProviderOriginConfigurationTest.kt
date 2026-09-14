package com.whatto.bcm.app.config

import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.provider.ProviderOriginRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.atomic.AtomicInteger

class ProviderOriginConfigurationTest {
    private fun origin() = ProviderOrigin("test-origin", "fireblocks", "fireblocks", "test-instance", "test-org", "TESTNET")

    private fun runner(repository: ProviderOriginRepository) =
        ApplicationContextRunner()
            .withUserConfiguration(ProviderOriginConfiguration::class.java, ProbeConfiguration::class.java)
            .withBean(ProviderOriginRepository::class.java, { repository })
            .withBean(AtomicInteger::class.java, { AtomicInteger() })
            .withPropertyValues(
                "bcm.provider=fireblocks",
                "bcm.chain-mode=TESTNET",
                "bcm.origin.id=test-origin",
                "bcm.origin.platform-instance-id=test-instance",
                "bcm.origin.vendor-organization-id=test-org",
            )

    @Test
    fun `일치하는 원천 검증 이후에만 실행 빈을 생성한다`() {
        var verified = false
        runner {
            verified = true
            origin()
        }.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(verified).isTrue()
            assertThat(context.getBean(AtomicInteger::class.java).get()).isEqualTo(1)
            assertThat(context.beanFactory.getBeanDefinition("startupProbe").dependsOn).contains("verifiedProviderOrigin")
        }
    }

    @Test
    fun `누락 및 다른 원천과 조회 실패는 실행 빈 생성 전에 중단한다`() {
        val unavailable = IllegalStateException("database unavailable")
        listOf<ProviderOriginRepository>(
            ProviderOriginRepository { null },
            ProviderOriginRepository { origin().copy(vendorOrganizationId = "other-org") },
            ProviderOriginRepository { throw unavailable },
        ).forEach { repository ->
            lateinit var factory: ConfigurableListableBeanFactory
            runner(repository).withInitializer { factory = it.beanFactory }.run { context ->
                assertThat(context).hasFailed()
                assertThat(factory.containsSingleton("startupProbe")).isFalse()
            }
        }
        runner { throw unavailable }.run { context ->
            assertThat(context.startupFailure).hasRootCause(unavailable)
        }
    }

    @Test
    fun `필수 원천 설정이 없으면 DB를 조회하지 않는다`() {
        listOf("bcm.origin.id", "bcm.origin.platform-instance-id", "bcm.origin.vendor-organization-id", "bcm.chain-mode")
            .forEach { property ->
                var reads = 0
                runner {
                    reads++
                    origin()
                }.withPropertyValues("$property=").run { context ->
                    assertThat(context).hasFailed()
                    assertThat(reads).isZero()
                }
            }
    }

    @Configuration(proxyBeanMethods = false)
    class ProbeConfiguration {
        @Bean
        fun startupProbe(counter: AtomicInteger): String {
            counter.incrementAndGet()
            return "started"
        }
    }
}
