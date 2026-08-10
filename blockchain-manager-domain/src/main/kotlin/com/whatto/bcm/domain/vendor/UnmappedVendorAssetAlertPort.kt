package com.whatto.bcm.domain.vendor

/**
 * 매핑되지 않은 벤더 자산 때문에 거래를 응답에서 뺐다는 신호.
 *
 * 조용히 버리면 고객 거래가 목록에서 사라진 채 아무도 모른다 — 등록 누락이면 고쳐야 할 설정이다
 * (openapi `transactionsOf`). 구체 알림 채널은 Phase 9에서 바인딩한다.
 */
fun interface UnmappedVendorAssetAlertPort {
    fun alert(alert: UnmappedVendorAssetAlert)
}

data class UnmappedVendorAssetAlert(
    val accountId: String,
    val vendorAssetId: String,
    val vendorTransactionIds: List<String>,
)
