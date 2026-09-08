package com.whatto.bcm.app.api

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 조립 지점 — app 모듈이 domain·infra·support 를 배선한다 (architecture.md).
 * API 유스케이스·공용 서비스·infra 설정만 조립한다.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = [
        "com.whatto.bcm.app.api",
        "com.whatto.bcm.app.application",
        "com.whatto.bcm.app.config",
        "com.whatto.bcm.infra",
        "com.whatto.bcm.support",
    ],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
    ],
)
@EnableScheduling
class BcmApiApplication

fun main(args: Array<String>) {
    runApplication<BcmApiApplication>(*args)
}
