package com.whatto.bcm.app.config

import com.whatto.bcm.domain.account.WalletProvisioningPolicy
import com.whatto.bcm.domain.vendor.VendorExecutionLimits
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/** 선택 벤더 호출 상한을 순수 생성 정책에 연결하는 application 조립 지점. */
@Configuration(proxyBeanMethods = false)
class WalletProvisioningConfig {
    @Bean
    fun walletProvisioningPolicy(limits: VendorExecutionLimits): WalletProvisioningPolicy =
        WalletProvisioningPolicy(Duration.ofMillis(limits.maximumCallMillis))
}
