package com.whatto.bcm.app.bat

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

/** 조립 지점 — bcm-api 와 동일한 배선 규칙 (architecture.md) */
@EnableScheduling
@SpringBootApplication
@ComponentScan(
    basePackages = ["com.whatto.bcm"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.whatto\\.bcm\\.testsupport\\..*"]),
    ],
)
class BcmBatApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<BcmBatApplication>(*args)
}
