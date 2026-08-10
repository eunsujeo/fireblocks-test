package com.whatto.bcm.domain.vendor

/** 벤더가 같은 체인의 chainId를 바꿔 보낼 때 운영 경보로 전달하는 포트. */
fun interface VendorBlockchainCatalogAlertPort {
    fun chainIdChanged(
        candidateId: String,
        storedChainId: Long?,
        observedChainId: Long?,
    )
}
