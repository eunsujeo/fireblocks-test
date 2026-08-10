package com.whatto.bcm.app.application.event

import com.whatto.bcm.domain.event.PoisonOutboxAlertPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Phase 9의 실제 알림 채널 바인딩 전 기본 운영 신호. payload는 로그에 남기지 않는다. */
@Component
class LoggingPoisonOutboxAlertAdapter : PoisonOutboxAlertPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun alert(
        eventId: String,
        retryCount: Int,
    ) {
        logger.error("Poison outbox event quarantined eventId={} retryCount={}", eventId, retryCount)
    }
}
