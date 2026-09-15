package com.whatto.bcm.app.config

import com.whatto.bcm.domain.account.WalletProvisioningPolicy
import com.whatto.bcm.domain.vendor.WalletCreationPolicy
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 선택 제공자의 생성 재시도 정책을 공통 생성 흐름에 연결한다. Fireblocks vault/asset wallet 생성 흐름 전용이라 Dfns에서는 만들지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFireblocksProtocol
class WalletProvisioningConfig {
    @Bean
    fun walletProvisioningPolicy(creationPolicy: WalletCreationPolicy): WalletProvisioningPolicy = WalletProvisioningPolicy(creationPolicy)
}
