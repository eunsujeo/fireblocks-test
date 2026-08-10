package com.whatto.bcm.app.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/**
 * 앱 전역 시각 원천 — 일시 14자 컬럼 값의 zone 이 이 빈에서 결정된다.
 * 오프셋 없는 코어 일시와 영업일 경계는 KST(Asia/Seoul)로 통일한다(03, PLAN #26).
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}
