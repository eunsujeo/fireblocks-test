package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.asset.AssetDecimals
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 네트워크 지갑이 보유한 자산의 잔액 관찰 포트(계약13 "잔액 계약 — 구현").
 * 벤더 자산 식별은 자산 매핑의 `vendorAssetId`와 **같은 규칙의 키**로 정규화해 돌려준다 — 유스케이스는 키 문자열 동일성만으로 등록 자산을 찾고
 * 벤더 자산 어휘(kind·contract·mint)는 어댑터 밖으로 나오지 않는다.
 */
fun interface NetworkWalletAssetPort {
    /** 지갑 하나의 자산 목록 — 지갑이 없거나 읽을 수 없으면 `VendorApiException`(응답 바이트 포함)이며 빈 목록으로 바꾸지 않는다. */
    fun assets(
        scope: NetworkWalletScope,
        vendorWalletId: String,
    ): NetworkWalletAssetSnapshot
}

/** 지갑 하나의 자산 관찰 — network는 BCM 코드로 되돌린 값이고 어댑터가 scope와 대조한다. */
data class NetworkWalletAssetSnapshot(
    val vendorWalletId: String,
    val network: String,
    val assets: List<NetworkWalletAssetBalance>,
)

/**
 * 자산 하나의 관찰 — `baseUnits`는 벤더가 준 최소 단위 정수 문자열, `decimals`는 같은 응답의 소수 자릿수다.
 * 금액 변환은 이 두 값만 사용하며 매핑에 별도 정밀도를 보관하지 않는다(07). 잔액이 아닌 값(음수·소수·빈 문자열)은 만들 수 없다.
 */
data class NetworkWalletAssetBalance(
    val vendorAssetId: String,
    val symbol: String?,
    val decimals: Int,
    val baseUnits: String,
    val verified: Boolean?,
) {
    init {
        require(vendorAssetId.isNotBlank() && vendorAssetId == vendorAssetId.trim()) { "Invalid vendor asset id" }
        require(AssetDecimals.isValid(decimals)) { "Invalid asset decimals" }
        require(BASE_UNITS.matches(baseUnits)) { "Invalid asset base units" }
    }

    /** 소수 표기 금액 — 지수 표기 없이, 뒤따르는 0은 제거한다(0은 `"0"`). 값은 바뀌지 않는다. */
    fun amount(): String = BigDecimal(BigInteger(baseUnits), decimals).stripTrailingZeros().toPlainString()

    companion object {
        /**
         * 소수 자릿수 형식 상한 — 명세는 `decimals: number`만 두므로 BCM 정규화 한계로 둔다. 근거와 값은 [AssetDecimals.MAX]에 있고
         * 등록 정밀도와 같은 상한을 쓴다(계약13 잔액 계약).
         */
        const val MAX_DECIMALS = AssetDecimals.MAX
        private val BASE_UNITS = AssetDecimals.BASE_UNITS
    }
}
