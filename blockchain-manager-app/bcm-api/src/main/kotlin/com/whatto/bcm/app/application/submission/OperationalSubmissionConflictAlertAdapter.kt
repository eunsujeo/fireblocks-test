package com.whatto.bcm.app.application.submission

import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import com.whatto.bcm.domain.submission.SubmissionConflictAlert
import com.whatto.bcm.domain.submission.SubmissionConflictAlertPort
import org.springframework.stereotype.Component

@Component
class OperationalSubmissionConflictAlertAdapter(
    private val channel: OperationalAlertChannel,
) : SubmissionConflictAlertPort {
    override fun alert(alert: SubmissionConflictAlert) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.TRANSACTION,
                type = "transaction.alert.submission-conflict",
                identifiers =
                    buildMap {
                        put("externalTransactionId", alert.externalTransactionId)
                        put("observedVendorTransactionId", alert.observedVendorTransactionId)
                        alert.recordedVendorTransactionId?.let { put("recordedVendorTransactionId", it) }
                    },
                context = mapOf("recordedStatus" to alert.recordedStatus.name),
            ),
        )
    }
}
