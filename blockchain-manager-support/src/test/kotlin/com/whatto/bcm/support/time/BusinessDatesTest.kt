package com.whatto.bcm.support.time

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BusinessDatesTest {
    @Test
    fun `업무 일자는 UTC 날짜가 아니라 KST 날짜로 산정한다`() {
        val clock = Clock.fixed(Instant.parse("2026-08-13T15:30:00Z"), ZoneOffset.UTC)

        assertThat(BusinessDates.now(clock)).isEqualTo("20260814")
    }
}
