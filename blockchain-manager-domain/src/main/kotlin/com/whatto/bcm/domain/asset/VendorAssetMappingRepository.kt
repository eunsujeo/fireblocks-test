package com.whatto.bcm.domain.asset

interface VendorAssetMappingRepository {
    fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping?

    /** 수신 웹훅의 벤더 assetId를 우리 (network, symbol)로 되돌린다. */
    fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping?

    fun findAll(
        network: String? = null,
        symbol: String? = null,
    ): List<VendorAssetMapping>

    fun existsByNetwork(network: String): Boolean

    fun insert(mapping: VendorAssetMapping): VendorAssetMapping

    fun delete(
        network: String,
        symbol: String,
    )
}
