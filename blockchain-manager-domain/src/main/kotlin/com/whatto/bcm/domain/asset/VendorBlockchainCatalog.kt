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
    /** 계정·자산 모델 — Dfns 데이터셋 seed만 채운다. 벤더 동기화 행은 null이며 Dfns 등록 관문은 null을 미확정 모델로 거절한다. */
    val chainModel: ChainModel? = null,
)
