package com.whatto.bcm.app.bat

import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import com.whatto.bcm.domain.vendor.VendorExecutionLimits
import com.whatto.bcm.domain.vendor.VendorNetworkFeePort
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorWebhookRecoveryPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(
    classes = [BcmBatApplication::class],
    properties = [
        "bcm.provider=local",
        "bcm.fireblocks.base-url=http://127.0.0.1:9",
        "bcm.fireblocks.webhook-jwks-url=http://127.0.0.1:9/jwks",
        "bcm.catalog-sync.cron=-",
        "bcm.asset-catalog-sync.cron=-",
    ],
)
class LocalProviderRuntimeIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `로컬 배치도 수수료와 제출 회수 및 웹훅 복구 포트를 하나씩 조립한다`() {
        assertThat(context.environment.getProperty("bcm.provider")).isEqualTo("local")
        assertThat(context.getBeansOfType(VendorExecutionLimits::class.java)).hasSize(1)
        assertThat(context.getBeansOfType(VendorTransactionPort::class.java)).hasSize(1)
        assertThat(context.getBeansOfType(VendorNetworkFeePort::class.java)).hasSize(1)
        assertThat(context.getBeansOfType(VendorWebhookRecoveryPort::class.java)).hasSize(1)
    }
}
