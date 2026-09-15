package com.whatto.bcm.app.config

import com.whatto.bcm.app.application.asset.FireblocksChainAssetResolver
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Fireblocks/로컬의 Admin 자산 등록 관문 조립 — 카탈로그 재해소. `fireblocks|local`에서만 만들어지며 Dfns 관문은 `DfnsClientConfig`가 만든다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFireblocksProtocol
class FireblocksAssetConfig {
    @Bean
    fun fireblocksChainAssetResolver(vendorCatalog: VendorAssetCatalogPort): FireblocksChainAssetResolver =
        FireblocksChainAssetResolver(vendorCatalog)
}
