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
 * 스캔 범위는 base 패키지 전체 — infra 의 @Configuration(PersistenceConfig 등)이 여기서 실린다.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = ["com.whatto.bcm"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.whatto\\.bcm\\.testsupport\\..*"]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.whatto\\.bcm\\.app\\.application\\.webhook\\..*"]),
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = [
                "com\\.whatto\\.bcm\\.app\\.application\\.event\\.(OperationalPoisonOutboxAlertAdapter|OutboxRelay.*)",
            ],
        ),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.whatto\\.bcm\\.app\\.webhook\\..*"]),
    ],
)
@EnableScheduling
class BcmApiApplication

fun main(args: Array<String>) {
    runApplication<BcmApiApplication>(*args)
}
