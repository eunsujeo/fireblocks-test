package com.whatto.bcm.app.application.submission

import com.whatto.bcm.domain.submission.SubmissionConflictAlert
import com.whatto.bcm.domain.submission.SubmissionConflictAlertPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Phase 9의 실제 알림 채널 바인딩 전 기본 운영 신호. 요청 본문은 로그에 남기지 않는다. */
@Component
class LoggingSubmissionConflictAlertAdapter : SubmissionConflictAlertPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun alert(alert: SubmissionConflictAlert) {
        logger.error(
            "Submission result was not recorded externalTransactionId={} observedVendorTransactionId={} " +
                "recordedVendorTransactionId={} recordedStatus={}",
            alert.externalTransactionId,
            alert.observedVendorTransactionId,
            alert.recordedVendorTransactionId,
            alert.recordedStatus,
        )
    }
}
