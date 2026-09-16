package com.whatto.bcm.domain.asset

import java.math.BigDecimal
import java.math.BigInteger

/**
 * 자산 정밀도와 최소 단위 금액의 공통 규칙(03 V27·07).
 *
 * 정밀도는 등록 시점에 확정한다 — 벤더가 사건마다 주는 값에 의존하면 벤더가 인덱싱 값을 바꾸는 순간
 * 같은 자산의 과거·미래 금액 해석이 달라진다.
 */
object AssetDecimals {
    /**
     * 정밀도 상한 — 모델링한 자산(EVM ERC-20 `decimals()` uint8, Solana SPL mint decimals u8)의 값은 255를 넘을 수 없다.
     * 그 밖의 값은 등록·관찰 모두에서 형식 오류로 본다.
     */
    const val MAX = 255

    fun isValid(decimals: Int): Boolean = decimals in 0..MAX

    /**
     * 최소 단위 정수 문자열을 사람 단위 금액 문자열로 옮긴다. 02의 이벤트 금액이 제공자와 무관하게 같은 단위여야 하므로
     * 최소 단위만 관찰되는 경로는 이 함수로만 환산한다. 부동소수를 거치지 않고 표기는 지수 없이 고정한다.
     */
    fun amountOf(
        baseUnits: String,
        decimals: Int,
    ): String {
        require(isValid(decimals)) { "Invalid asset decimals" }
        require(BASE_UNITS.matches(baseUnits)) { "Invalid asset base units" }
        return BigDecimal(BigInteger(baseUnits), decimals).stripTrailingZeros().toPlainString()
    }

    /** 최소 단위 정수 표기 — 선행 0을 금지해 같은 금액의 표기를 하나로 고정한다. */
    val BASE_UNITS: Regex = Regex("0|[1-9][0-9]*")
}
