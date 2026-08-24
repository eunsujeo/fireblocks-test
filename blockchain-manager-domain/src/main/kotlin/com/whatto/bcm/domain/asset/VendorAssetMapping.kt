package com.whatto.bcm.domain.asset

/**
 * 우리 자산 (network, symbol)과 벤더 assetId의 현재 매핑.
 * vendorAssetId는 벤더 호출 경계 밖으로 노출하지 않는다.
 */
data class VendorAssetMapping(
    val network: String,
    val symbol: String,
    val vendorAssetId: String,
    val contractAddress: String?,
    val registeredAt: String,
    val registeredByEmployeeNo: String,
    val registeredByBranchCode: String,
    val active: Boolean = true,
)
