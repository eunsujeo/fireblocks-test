package com.whatto.bcm.app.application.monitoring

import com.whatto.bcm.app.application.asset.OperationalUnmappedVendorAssetAlertAdapter
import com.whatto.bcm.app.application.submission.OperationalSubmissionConflictAlertAdapter
import com.whatto.bcm.app.webhook.application.event.OperationalPoisonOutboxAlertAdapter
import com.whatto.bcm.app.webhook.application.webhook.OperationalPoisonWebhookAlertAdapter
import com.whatto.bcm.app.webhook.application.webhook.OperationalUnattributedDepositAlertAdapter
import com.whatto.bcm.app.webhook.application.webhook.OperationalUnregisteredVaultTransferAlertAdapter
import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import com.whatto.bcm.domain.submission.SubmissionConflictAlert
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.vendor.UnmappedVendorAssetAlert
import com.whatto.bcm.domain.webhook.UnattributedDepositAlert
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OperationalAlertAdaptersTest {
    @Test
    fun `API 경보 포트를 유형별 운영 route로 변환한다`() {
        val channel = RecordingOperationalAlertChannel()

        OperationalUnattributedDepositAlertAdapter(channel).alert(
            UnattributedDepositAlert("noti-1", "vendor-tx-1", "ETHEREUM", "USDC"),
        )
        OperationalUnregisteredVaultTransferAlertAdapter(channel).alert(
            UnregisteredVaultTransferAlert("noti-2", "vendor-tx-2", "external-2"),
        )
        OperationalUnmappedVendorAssetAlertAdapter(channel).alert(
            UnmappedVendorAssetAlert("account-3", "USDC_TEST", listOf("vendor-tx-3", "vendor-tx-4")),
        )
        OperationalPoisonWebhookAlertAdapter(channel).alert("noti-4", 3)
        OperationalPoisonOutboxAlertAdapter(channel).alert("event-5", 5)
        OperationalSubmissionConflictAlertAdapter(channel).alert(
            SubmissionConflictAlert("external-6", "vendor-tx-6", "vendor-tx-old", SubmissionStatus.SUBMITTED),
        )

        assertThat(channel.alerts.map { it.route })
            .containsExactly(
                OperationalAlertRoute.WEBHOOK,
                OperationalAlertRoute.WEBHOOK,
                OperationalAlertRoute.ASSET,
                OperationalAlertRoute.WEBHOOK,
                OperationalAlertRoute.EVENT_DELIVERY,
                OperationalAlertRoute.TRANSACTION,
            )
        assertThat(channel.alerts.map { it.type })
            .containsExactly(
                "webhook.alert.unattributed-deposit",
                "webhook.alert.unregistered-vault-transfer",
                "asset.alert.unmapped-vendor-asset",
                "webhook.alert.poison",
                "event-delivery.alert.poison",
                "transaction.alert.submission-conflict",
            )
        assertThat(channel.alerts.flatMap { it.identifiers.values + it.context.values })
            .doesNotContain("0xrecipient-address", "1000000", "raw-payload")
        assertThat(channel.alerts[2].context).containsEntry("transactionCount", "2")
    }
}

private class RecordingOperationalAlertChannel : OperationalAlertChannel {
    val alerts = mutableListOf<OperationalAlert>()

    override fun publish(alert: OperationalAlert) {
        alerts += alert
    }
}
