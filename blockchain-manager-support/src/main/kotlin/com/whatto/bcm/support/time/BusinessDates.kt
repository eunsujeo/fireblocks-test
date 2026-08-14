package com.whatto.bcm.support.time

import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 업무 일자와 일 배치 파티션 경계를 UTC `yyyyMMdd`로 산정한다. */
object BusinessDates {
    private val FORMATTER = DateTimeFormatter.BASIC_ISO_DATE

    fun now(clock: Clock): String =
        clock
            .instant()
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .format(FORMATTER)
}
