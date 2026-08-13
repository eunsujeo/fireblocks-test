package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 운영 알림 채널이 확정되기 전의 기본 sweep 실행 경보 어댑터. */
@Component
class LoggingSweepExecutionAlertAdapter : SweepExecutionAlertPort {
    override fun alert(alert: SweepExecutionAlert) {
        logger.error(
            "sweep 실행 실패 stage={} accountId={} network={} symbol={}",
            alert.stage,
            alert.target.accountId,
            alert.target.network,
            alert.target.symbol,
            alert.cause,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(LoggingSweepExecutionAlertAdapter::class.java)
    }
}
