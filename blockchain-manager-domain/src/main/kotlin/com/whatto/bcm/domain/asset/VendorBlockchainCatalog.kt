package com.whatto.bcm.domain.asset

/**
 * 벤더 블록체인 카탈로그의 우리 쪽 표현.
 * candidateId는 Admin이 뜻을 해석하지 않고 채택 요청에 되돌려 주는 불투명 손잡이다.
 */
data class VendorBlockchainCatalog(
    val candidateId: String,
    val network: String?,
    val chainId: Long?,
    val displayName: String,
    val testnet: Boolean,
    val deprecated: Boolean,
    val syncedAt: String,
)
