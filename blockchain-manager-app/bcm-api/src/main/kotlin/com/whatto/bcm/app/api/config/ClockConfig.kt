package com.whatto.bcm.app.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 앱 전역 시각 원천. `_dttm`과 `_dt`를 모두 UTC로 산정한다.
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
