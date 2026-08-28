package com.whatto.bcm.app.application.asset

import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import org.springframework.stereotype.Service

/** 다른 피처가 자산 매핑 저장소와 벤더 id를 직접 다루지 않도록 조회 규칙을 캡슐화한다. */
@Service
class VendorAssetMappingQueryService(
    private val repository: VendorAssetMappingRepository,
) {
    fun requiredMapping(
        network: String,
        symbol: String,
    ): VendorAssetMapping =
        repository.find(network, symbol)
            ?: throw AssetNotSupportedException(network, symbol)

    fun requiredCurrentMapping(
        network: String,
        symbol: String,
    ): VendorAssetMapping =
        repository.findCurrent(network, symbol)?.takeIf(VendorAssetMapping::active)
            ?: throw AssetNotSupportedException(network, symbol)

    fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping? = repository.findByVendorAssetId(vendorAssetId)
}
