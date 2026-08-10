package com.whatto.bcm.support.amount

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 문자열 decimal 합산 계약 — 금액은 문자열로 다루고(CLAUDE.md 3절) 합산도 정밀도를 잃지 않는다.
 * 스펙 Balance.locked = frozen + lockedAmount 합산이 첫 사용처.
 */
class CoreAmountsTest {
    @Test
    fun `이진 부동소수점이 깨뜨리는 합을 정확히 더한다`() {
        assertThat(CoreAmounts.plus("0.1", "0.2")).isEqualTo("0.3")
    }

    @Test
    fun `double 정밀도를 넘는 큰 금액도 자릿수를 잃지 않는다`() {
        assertThat(CoreAmounts.plus("12345678901234567890.123456789", "0.000000001"))
            .isEqualTo("12345678901234567890.123456790")
    }

    @Test
    fun `0 과의 합은 원 금액을 보존한다`() {
        assertThat(CoreAmounts.plus("10.5", "0")).isEqualTo("10.5")
    }

    @Test
    fun `아주 작은 금액도 지수 표기 없이 평문으로 돌려준다`() {
        assertThat(CoreAmounts.plus("0.0000001", "0")).isEqualTo("0.0000001")
    }

    @Test
    fun `숫자가 아닌 입력은 예외 — 조용히 0 으로 삼키지 않는다`() {
        assertThatThrownBy { CoreAmounts.plus("abc", "1") }
            .isInstanceOf(NumberFormatException::class.java)
    }
}
