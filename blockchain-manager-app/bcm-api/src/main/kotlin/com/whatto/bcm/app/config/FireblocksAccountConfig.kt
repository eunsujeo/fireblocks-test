package com.whatto.bcm.app.config

import com.whatto.bcm.app.application.account.AccountService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.account.WalletProvisioningPolicy
import com.whatto.bcm.domain.account.WalletProvisioningRepository
import com.whatto.bcm.domain.vendor.WalletVendorPort
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Fireblocks/로컬의 계정·주소 유스케이스 조립 — vault/asset wallet 모델. `fireblocks|local`에서만 만들어진다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFireblocksProtocol
class FireblocksAccountConfig {
    @Bean
    fun accountService(
        accountRepository: AccountRepository,
        depositAddressRepository: DepositAddressRepository,
        provisioningRepository: WalletProvisioningRepository,
        assetMappingQueryService: VendorAssetMappingQueryService,
        walletVendorPort: WalletVendorPort,
        provisioningPolicy: WalletProvisioningPolicy,
        clock: Clock,
    ): AccountService =
        AccountService(
            accountRepository,
            depositAddressRepository,
            provisioningRepository,
            assetMappingQueryService,
            walletVendorPort,
            provisioningPolicy,
            clock,
        )
}
