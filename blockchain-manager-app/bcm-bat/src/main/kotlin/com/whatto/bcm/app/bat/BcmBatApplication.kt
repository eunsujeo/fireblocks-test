package com.whatto.bcm.app.bat

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

/** 조립 지점 — bcm-api 와 동일한 배선 규칙 (architecture.md) */
@EnableScheduling
@SpringBootApplication(scanBasePackages = ["com.whatto.bcm"])
class BcmBatApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<BcmBatApplication>(*args)
}
