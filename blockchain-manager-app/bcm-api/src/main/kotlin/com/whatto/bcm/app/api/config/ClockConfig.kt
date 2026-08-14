package com.whatto.bcm.app.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 앱 전역 절대시각 원천. `_dttm`은 UTC로 저장하고 업무 일자는 BusinessDates가 KST로 산정한다.
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
