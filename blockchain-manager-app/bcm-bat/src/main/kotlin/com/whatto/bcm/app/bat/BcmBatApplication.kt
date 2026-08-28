package com.whatto.bcm.app.bat

import com.whatto.bcm.app.application.event.OutboxEventService
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling

/** 조립 지점 — bcm-api 와 동일한 배선 규칙 (architecture.md) */
@EnableScheduling
@SpringBootApplication
@Import(OutboxEventService::class)
@ComponentScan(
    basePackages = ["com.whatto.bcm"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.whatto\\.bcm\\.testsupport\\..*"]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.whatto\\.bcm\\.app\\.application\\..*"]),
    ],
)
class BcmBatApplication

fun main(args: Array<String>) {
    runApplication<BcmBatApplication>(*args)
}
