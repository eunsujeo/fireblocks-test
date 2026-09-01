package com.whatto.bcm.app.config

import com.whatto.bcm.domain.account.WalletProvisioningPolicy
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/** Fireblocks 호출 상한을 순수 생성 정책에 연결하는 application 조립 지점. */
@Configuration(proxyBeanMethods = false)
class WalletProvisioningConfig {
    @Bean
    fun walletProvisioningPolicy(properties: FireblocksProperties): WalletProvisioningPolicy =
        WalletProvisioningPolicy(Duration.ofMillis(properties.maximumCallMillis))
}
