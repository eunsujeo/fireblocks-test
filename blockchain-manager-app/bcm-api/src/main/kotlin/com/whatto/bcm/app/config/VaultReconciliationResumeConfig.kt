package com.whatto.bcm.app.config

import com.whatto.bcm.app.application.admin.AdminVaultReconciliationService
import com.whatto.bcm.app.application.admin.VaultReconciliationProperties
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled

@Configuration
class VaultReconciliationResumeConfig(
    private val service: AdminVaultReconciliationService,
) {
    @Bean
    fun vaultReconciliationResume() = ApplicationRunner { service.resume() }

    @Scheduled(fixedDelayString = "\${bcm.vault-reconciliation.resume-delay-millis:60000}")
    fun resumeExpiredVaultReconciliations() = service.resume()
}

@Configuration
@EnableConfigurationProperties(VaultReconciliationProperties::class)
class VaultReconciliationSafetyConfig(
    properties: VaultReconciliationProperties,
    fireblocks: FireblocksProperties,
) {
    init {
        val claimTtlMillis = Math.multiplyExact(properties.claimTtlSeconds, 1_000)
        require(claimTtlMillis > fireblocks.maximumCallMillis) {
            "vault reconciliation claim TTL must be longer than the maximum Fireblocks call"
        }
    }
}
