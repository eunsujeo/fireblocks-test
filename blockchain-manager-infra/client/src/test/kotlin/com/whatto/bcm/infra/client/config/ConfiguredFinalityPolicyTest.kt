package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.tx.FinalityPolicyConfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class ConfiguredFinalityPolicyTest {
    @Test
    fun `네트워크 finality 설정이 없거나 유효하지 않으면 payload 오류가 아닌 설정 오류다`() {
        val missing = ConfiguredFinalityPolicy(StandardEnvironment())
        val invalidEnvironment =
            StandardEnvironment().apply {
                propertySources.addFirst(MapPropertySource("test", mapOf("bcm.finality-confirmations.ethereum" to 0)))
            }

        assertThatThrownBy { missing.requiredConfirmations("ETHEREUM") }
            .isExactlyInstanceOf(FinalityPolicyConfigurationException::class.java)
        assertThatThrownBy { ConfiguredFinalityPolicy(invalidEnvironment).requiredConfirmations("ETHEREUM") }
            .isExactlyInstanceOf(FinalityPolicyConfigurationException::class.java)
    }

    @Test
    fun `양수 finality 설정은 그대로 반환한다`() {
        val environment =
            StandardEnvironment().apply {
                propertySources.addFirst(MapPropertySource("test", mapOf("bcm.finality-confirmations.ethereum" to 12)))
            }

        assertThat(ConfiguredFinalityPolicy(environment).requiredConfirmations("ETHEREUM")).isEqualTo(12)
    }
}
