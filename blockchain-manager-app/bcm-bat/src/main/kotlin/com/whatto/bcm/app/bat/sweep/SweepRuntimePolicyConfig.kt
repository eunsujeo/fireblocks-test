package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.admin.SweepPolicyHardCeiling
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

@ConfigurationProperties("bcm.admin-policy.hard-ceiling")
data class SweepRuntimeHardCeilingProperties(
    val executionEnabled: Boolean = false,
    val maximumBatchSize: Int = 0,
    val maximumAllowance: BigDecimal = BigDecimal.ZERO,
    val maximumItemAmount: BigDecimal = BigDecimal.ZERO,
    val maximumBatchAmount: BigDecimal = BigDecimal.ZERO,
    val maximumBoostAttempts: Int = 0,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SweepRuntimeHardCeilingProperties::class)
class SweepRuntimePolicyConfig {
    @Bean
    fun sweepRuntimeHardCeiling(properties: SweepRuntimeHardCeilingProperties) =
        SweepPolicyHardCeiling(
            properties.executionEnabled,
            properties.maximumBatchSize,
            properties.maximumAllowance,
            properties.maximumItemAmount,
            properties.maximumBatchAmount,
            properties.maximumBoostAttempts,
        )
}
