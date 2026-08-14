package com.whatto.bcm.support.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * UTC 일시 컬럼(VARCHAR(16)) 값 변환 단일 관리 — 포맷 `yyyyMMddHHmmss` 14자.
 * 코어 운영 규약이 달리 확인되면 여기 한 곳만 조정한다.
 */
object CoreDateTimes {
    private val FORMATTER =
        DateTimeFormatter
            .ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT)

    fun format(dateTime: LocalDateTime): String = dateTime.format(FORMATTER)

    fun parse(value: String): LocalDateTime = LocalDateTime.parse(value, FORMATTER)

    fun current(clock: Clock): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    fun now(clock: Clock): String = format(current(clock))

    fun fromEpochMillis(epochMillis: Long): String = format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC))

    fun toEpochMillis(value: String): Long = parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
}
