package com.whatto.bcm.support.time

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * 일시 컬럼 값 포맷 계약 고정 — `yyyyMMddHHmmss` 14자 (CLAUDE.md 3절, 2026-08-05 확정).
 * 변환은 이 유틸 단일 관리 — 코어 규약이 달리 확인되면 여기 한 곳만 바꾼다.
 */
class CoreDateTimesTest {
    @Test
    fun `일시는 yyyyMMddHHmmss 14자로 포맷한다`() {
        val formatted = CoreDateTimes.format(LocalDateTime.of(2026, 8, 5, 9, 30, 15))

        assertThat(formatted).isEqualTo("20260805093015")
        assertThat(formatted).hasSize(14)
    }

    @Test
    fun `한 자리 월·일·시·분·초는 0 으로 채운다`() {
        val formatted = CoreDateTimes.format(LocalDateTime.of(2026, 1, 2, 3, 4, 5))

        assertThat(formatted).isEqualTo("20260102030405")
    }

    @Test
    fun `14자 문자열을 일시로 파싱한다 — 포맷·파싱 왕복 보존`() {
        val original = LocalDateTime.of(2026, 12, 31, 23, 59, 59)

        val roundTripped = CoreDateTimes.parse(CoreDateTimes.format(original))

        assertThat(roundTripped).isEqualTo(original)
    }

    @Test
    fun `now 는 주입된 Clock 의 시각을 쓴다`() {
        val clock = Clock.fixed(Instant.parse("2026-08-05T00:30:15Z"), ZoneId.of("Asia/Seoul"))

        assertThat(CoreDateTimes.now(clock)).isEqualTo("20260805093015")
    }

    @Test
    fun `14자가 아니면 파싱을 거부한다`() {
        assertThatThrownBy { CoreDateTimes.parse("20260805") }
            .isInstanceOf(DateTimeParseException::class.java)

        assertThatThrownBy { CoreDateTimes.parse("2026080509301500") }
            .isInstanceOf(DateTimeParseException::class.java)
    }

    @Test
    fun `달력에 없는 일시는 거부한다 — 윤년 아닌 2월 29일`() {
        assertThatThrownBy { CoreDateTimes.parse("20260229000000") }
            .isInstanceOf(DateTimeParseException::class.java)
    }
}
