package com.whatto.bcm.support.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * 일시 컬럼(VARCHAR(16)) 값 변환 단일 관리 — 포맷 `yyyyMMddHHmmss` 14자 (CLAUDE.md 3절, 2026-08-05 확정).
 * 코어 운영 규약이 달리 확인되면 여기 한 곳만 조정한다.
 */
object CoreDateTimes {
    private val FORMATTER =
        DateTimeFormatter
            .ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT)

    fun format(dateTime: LocalDateTime): String = dateTime.format(FORMATTER)

    fun parse(value: String): LocalDateTime = LocalDateTime.parse(value, FORMATTER)

    fun now(clock: Clock): String = format(LocalDateTime.now(clock))

    fun fromEpochMillis(
        epochMillis: Long,
        zoneId: ZoneId,
    ): String = format(Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDateTime())
}
