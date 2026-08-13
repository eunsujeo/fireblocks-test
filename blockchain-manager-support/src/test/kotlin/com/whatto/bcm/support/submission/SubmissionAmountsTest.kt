package com.whatto.bcm.support.submission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SubmissionAmountsTest {
    @Test
    fun `양수이면서 NUMERIC 36 18에 정확히 저장되는 금액만 허용한다`() {
        assertThat(SubmissionAmounts.isValid("0.000000000000000001")).isTrue()
        assertThat(SubmissionAmounts.isValid("999999999999999999.999999999999999999")).isTrue()
        assertThat(SubmissionAmounts.isValid("1.0000000000000000000")).isTrue()

        assertThat(SubmissionAmounts.isValid("0")).isFalse()
        assertThat(SubmissionAmounts.isValid("0.0000000000000000001")).isFalse()
        assertThat(SubmissionAmounts.isValid("1000000000000000000")).isFalse()
        assertThat(SubmissionAmounts.isValid("not-a-number")).isFalse()
    }

    @Test
    fun `contract call 의미 금액은 allowance 회수를 위한 0을 허용한다`() {
        assertThat(SubmissionAmounts.isNonNegativeAndFits("0")).isTrue()
        assertThat(SubmissionAmounts.isNonNegativeAndFits("-0.000000000000000001")).isFalse()
    }
}
