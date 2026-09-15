package com.whatto.bcm.app.config

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.LogicalAccountService
import com.whatto.bcm.app.application.wallet.NetworkWalletProvisioningService
import com.whatto.bcm.domain.account.LogicalAccountRepository
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletProvisioningPort
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceStore
import com.whatto.bcm.domain.wallet.NetworkWalletProvisioningRepository
import com.whatto.bcm.infra.client.config.ConditionalOnDfnsProtocol
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Dfns 계정·주소 유스케이스 조립 — 논리 계정 등록과 네트워크 지갑 생성/회수 서비스를 실제 원장·증적 저장소·HTTP 어댑터에 연결한다.
 * `BCM_PROVIDER=dfns`에서만 만들어지며 Fireblocks vault 생성 정책(WalletProvisioningConfig)은 조립하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDfnsProtocol
class DfnsAccountConfig {
    @Bean
    fun logicalAccountService(
        repository: LogicalAccountRepository,
        origin: ProviderOrigin,
        clock: Clock,
    ): LogicalAccountService = LogicalAccountService(repository, origin, clock)

    @Bean
    fun networkWalletProvisioningService(
        repository: NetworkWalletProvisioningRepository,
        vendor: NetworkWalletProvisioningPort,
        evidence: NetworkWalletEvidenceStore,
        accounts: AccountQueryService,
        origin: ProviderOrigin,
        clock: Clock,
    ): NetworkWalletProvisioningService = NetworkWalletProvisioningService(repository, vendor, evidence, accounts, origin, clock)
}
