package com.whatto.bcm.app.application.webhook

import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import com.whatto.bcm.domain.webhook.PoisonWebhookAlertPort
import com.whatto.bcm.domain.webhook.UnattributedDepositAlert
import com.whatto.bcm.domain.webhook.UnattributedDepositAlertPort
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlert
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlertPort
import org.springframework.stereotype.Component

@Component
class OperationalUnattributedDepositAlertAdapter(
    private val channel: OperationalAlertChannel,
) : UnattributedDepositAlertPort {
    override fun alert(alert: UnattributedDepositAlert) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.WEBHOOK,
                type = "webhook.alert.unattributed-deposit",
                identifiers =
                    mapOf(
                        "notificationId" to alert.notificationId,
                        "vendorTransactionId" to alert.vendorTransactionId,
                    ),
                context = mapOf("network" to alert.network, "symbol" to alert.symbol),
            ),
        )
    }
}

@Component
class OperationalUnregisteredVaultTransferAlertAdapter(
    private val channel: OperationalAlertChannel,
) : UnregisteredVaultTransferAlertPort {
    override fun alert(alert: UnregisteredVaultTransferAlert) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.WEBHOOK,
                type = "webhook.alert.unregistered-vault-transfer",
                identifiers =
                    buildMap {
                        put("notificationId", alert.notificationId)
                        put("vendorTransactionId", alert.vendorTransactionId)
                        alert.externalTransactionId?.let { put("externalTransactionId", it) }
                    },
            ),
        )
    }
}

@Component
class OperationalPoisonWebhookAlertAdapter(
    private val channel: OperationalAlertChannel,
) : PoisonWebhookAlertPort {
    override fun alert(
        notificationId: String,
        retryCount: Int,
    ) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.WEBHOOK,
                type = "webhook.alert.poison",
                identifiers = mapOf("notificationId" to notificationId),
                context = mapOf("retryCount" to retryCount.toString()),
            ),
        )
    }
}
