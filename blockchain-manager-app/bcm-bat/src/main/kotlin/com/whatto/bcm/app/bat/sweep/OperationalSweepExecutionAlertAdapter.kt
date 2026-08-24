package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import org.springframework.stereotype.Component

@Component
class OperationalSweepExecutionAlertAdapter(
    private val channel: OperationalAlertChannel,
) : SweepExecutionAlertPort {
    override fun alert(alert: SweepExecutionAlert) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.SWEEP,
                type = "sweep.alert.${alert.stage.name.lowercase()}-failed",
                identifiers = mapOf("accountId" to alert.target.accountId),
                context =
                    mapOf(
                        "network" to alert.target.network,
                        "symbol" to alert.target.symbol,
                        "causeType" to alert.cause.javaClass.simpleName,
                    ),
            ),
        )
    }
}
