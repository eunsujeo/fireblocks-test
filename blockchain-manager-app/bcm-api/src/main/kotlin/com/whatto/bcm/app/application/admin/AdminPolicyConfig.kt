package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.SweepPolicyHardCeiling
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

@ConfigurationProperties("bcm.admin-policy.hard-ceiling")
data class AdminPolicyHardCeilingProperties(
    val executionEnabled: Boolean = false,
    val maximumBatchSize: Int = 0,
    val maximumAllowance: BigDecimal = BigDecimal.ZERO,
    val maximumItemAmount: BigDecimal = BigDecimal.ZERO,
    val maximumBatchAmount: BigDecimal = BigDecimal.ZERO,
    val maximumBoostAttempts: Int = 0,
)

@ConfigurationProperties("bcm.sweep.security")
data class AdminSweepReleaseProperties(
    val normalApprovalEnabled: Boolean = false,
    val emergencyRevocationEnabled: Boolean = false,
    val batchSubmissionEnabled: Boolean = false,
    val tapApprovalPolicyVerified: Boolean = false,
    val tapRevocationPolicyVerified: Boolean = false,
    val tapBatchPolicyVerified: Boolean = false,
    val callbackVerified: Boolean = false,
    val universalGaslessVerified: Boolean = false,
    val sweepContractVerified: Boolean = false,
    val normalApprovalEnabledNetworks: Set<String> = emptySet(),
    val emergencyRevocationEnabledNetworks: Set<String> = emptySet(),
    val batchSubmissionEnabledNetworks: Set<String> = emptySet(),
) {
    fun normalApprovalReady(network: String): Boolean =
        normalApprovalEnabled &&
            network in normalApprovalEnabledNetworks &&
            tapApprovalPolicyVerified &&
            callbackVerified &&
            universalGaslessVerified

    fun emergencyRevocationReady(network: String): Boolean =
        emergencyRevocationEnabled &&
            network in emergencyRevocationEnabledNetworks &&
            tapRevocationPolicyVerified &&
            callbackVerified &&
            universalGaslessVerified

    fun batchSubmissionReady(network: String): Boolean =
        batchSubmissionEnabled &&
            network in batchSubmissionEnabledNetworks &&
            tapBatchPolicyVerified &&
            callbackVerified &&
            universalGaslessVerified &&
            sweepContractVerified
}

@Configuration
@EnableConfigurationProperties(AdminPolicyHardCeilingProperties::class, AdminSweepReleaseProperties::class)
class AdminPolicyConfig {
    @Bean
    fun sweepPolicyHardCeiling(properties: AdminPolicyHardCeilingProperties) =
        SweepPolicyHardCeiling(
            executionEnabled = properties.executionEnabled,
            maximumBatchSize = properties.maximumBatchSize,
            maximumAllowance = properties.maximumAllowance,
            maximumItemAmount = properties.maximumItemAmount,
            maximumBatchAmount = properties.maximumBatchAmount,
            maximumBoostAttempts = properties.maximumBoostAttempts,
        )
}
