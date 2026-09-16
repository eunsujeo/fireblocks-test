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
    /**
     * 등록 시점에 확정한 정밀도(03 V27). 이 값이 없으면 최소 단위 관찰을 이벤트 금액으로 환산하지 않는다 —
     * 제공자마다 같은 `amount` 필드의 단위가 달라지면 조용한 금액 사고가 된다.
     * Fireblocks는 카탈로그 해소값, Dfns는 운영자가 발행사 자료와 대조해 등록한 값이다(07).
     */
    val decimals: Int? = null,
) {
    init {
        require(decimals == null || AssetDecimals.isValid(decimals)) { "Invalid asset decimals" }
    }

    companion object {
        /**
         * `bcm_vndr_ast_m.vndr_ast_id VARCHAR(128)`(03 V25) — 등록 관문(`ChainAssetResolver` 구현)이 이 길이를 넘는 Dfns 자산 키를 자르지 않고 거절한다.
         * 모델 자체는 길이를 강제하지 않는다 — 저장 길이 결함은 영속성 계층이 데이터 오류로 그대로 드러낸다(충돌로 오분류하지 않음).
         */
        const val VENDOR_ASSET_ID_MAX_LENGTH = 128
    }
}
