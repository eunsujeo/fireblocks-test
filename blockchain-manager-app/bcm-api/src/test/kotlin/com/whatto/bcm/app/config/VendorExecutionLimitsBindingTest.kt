package com.whatto.bcm.app.config

import com.whatto.bcm.domain.account.WalletProvisioningPolicy
import com.whatto.bcm.domain.vendor.VendorExecutionLimits
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import com.whatto.bcm.infra.client.fireblocks.FireblocksWalletCreationPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class VendorExecutionLimitsBindingTest {
    @Test
    fun `Fireblocks 설정 바인딩이 공통 시간 계약으로 주입되고 기존 산식을 유지한다`() {
        ApplicationContextRunner()
            .withUserConfiguration(
                FireblocksLimitsConfiguration::class.java,
                FireblocksWalletCreationPolicy::class.java,
                WalletProvisioningConfig::class.java,
            ).withPropertyValues(
                "bcm.provider=fireblocks",
                "bcm.fireblocks.max-attempts=2",
                "bcm.fireblocks.connect-timeout-millis=1000",
                "bcm.fireblocks.read-timeout-millis=2000",
                "bcm.fireblocks.max-backoff-millis=4000",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(VendorExecutionLimits::class.java)
                assertThat(context).hasSingleBean(WalletProvisioningPolicy::class.java)
                val limits = context.getBean(VendorExecutionLimits::class.java)
                assertThat(limits).isSameAs(context.getBean(FireblocksProperties::class.java))
                assertThat(limits.maximumCallMillis).isEqualTo(10_000L)
                assertThat(limits.maximumSubmissionFlowMillis).isEqualTo(20_000L)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FireblocksProperties::class)
    class FireblocksLimitsConfiguration
}
