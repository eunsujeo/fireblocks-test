package com.whatto.bcm.domain.vendor

/** 벤더 자산 매핑을 안전하게 고르고 검증하기 위한 읽기 전용 카탈로그 포트. */
interface VendorAssetCatalogPort {
    fun blockchains(pageCursor: String? = null): VendorPage<VendorBlockchain>

    fun assets(
        blockchainId: String,
        symbol: String? = null,
        pageCursor: String? = null,
    ): VendorPage<VendorAsset>
}

data class VendorPage<T>(
    val data: List<T>,
    val next: String?,
)

data class VendorBlockchain(
    val id: String,
    val displayName: String,
    val deprecated: Boolean,
    val onchain: VendorBlockchainOnchain?,
)

data class VendorBlockchainOnchain(
    val protocol: String?,
    val chainId: String?,
    val test: Boolean?,
    val signingAlgo: String?,
)

data class VendorAsset(
    val id: String,
    val blockchainId: String,
    val displayName: String?,
    val displaySymbol: String,
    val decimals: Int?,
    val assetClass: String?,
    val contractAddress: String?,
)
