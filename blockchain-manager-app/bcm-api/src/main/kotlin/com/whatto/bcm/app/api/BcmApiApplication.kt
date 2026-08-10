package com.whatto.bcm.app.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 조립 지점 — app 모듈이 domain·infra·support 를 배선한다 (architecture.md).
 * 스캔 범위는 base 패키지 전체 — infra 의 @Configuration(PersistenceConfig 등)이 여기서 실린다.
 */
@SpringBootApplication(scanBasePackages = ["com.whatto.bcm"])
@EnableScheduling
class BcmApiApplication

fun main(args: Array<String>) {
    runApplication<BcmApiApplication>(*args)
}
