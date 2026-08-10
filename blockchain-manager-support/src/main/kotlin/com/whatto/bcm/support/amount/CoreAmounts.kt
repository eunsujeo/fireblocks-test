package com.whatto.bcm.support.amount

import java.math.BigDecimal

/**
 * 문자열 decimal 금액 연산 단일 관리 — 금액은 문자열로 다룬다 (CLAUDE.md 3절, 숫자 변환의 정밀도 손실 방지).
 * 연산은 BigDecimal 로 정확하게, 결과는 지수 표기 없는 평문 문자열로 돌려준다.
 */
object CoreAmounts {
    /** 숫자가 아닌 입력은 NumberFormatException — 잘못된 금액을 0 으로 삼키지 않는다. */
    fun plus(
        augend: String,
        addend: String,
    ): String = BigDecimal(augend).add(BigDecimal(addend)).toPlainString()
}
