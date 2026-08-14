package com.whatto.bcm.support.time

import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 업무 일자와 일 배치 파티션 경계를 KST `yyyyMMdd`로 산정한다. */
object BusinessDates {
    private val KST = ZoneId.of("Asia/Seoul")
    private val FORMATTER = DateTimeFormatter.BASIC_ISO_DATE

    fun now(clock: Clock): String =
        clock
            .instant()
            .atZone(KST)
            .toLocalDate()
            .format(FORMATTER)
}
