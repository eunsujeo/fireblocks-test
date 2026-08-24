package com.whatto.bcm.app.application.event

import com.whatto.bcm.domain.event.PoisonOutboxAlertPort
import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import org.springframework.stereotype.Component

@Component
class OperationalPoisonOutboxAlertAdapter(
    private val channel: OperationalAlertChannel,
) : PoisonOutboxAlertPort {
    override fun alert(
        eventId: String,
        retryCount: Int,
    ) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.EVENT_DELIVERY,
                type = "event-delivery.alert.poison",
                identifiers = mapOf("eventId" to eventId),
                context = mapOf("retryCount" to retryCount.toString()),
            ),
        )
    }
}
