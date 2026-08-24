package com.whatto.bcm.app.bat.monitoring

import com.whatto.bcm.app.bat.asset.OperationalVendorBlockchainCatalogAlertAdapter
import com.whatto.bcm.app.bat.reconciliation.OperationalTxReconciliationMissingWebhookAlertAdapter
import com.whatto.bcm.app.bat.reconciliation.OperationalTxReconciliationTrackingStoppedAlertAdapter
import com.whatto.bcm.app.bat.stall.OperationalStallAlertAdapter
import com.whatto.bcm.app.bat.sweep.OperationalSweepExecutionAlertAdapter
import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionStage
import com.whatto.bcm.domain.sweep.SweepTargetKey
import com.whatto.bcm.domain.tx.StallAlert
import com.whatto.bcm.domain.tx.StallAlertReason
import com.whatto.bcm.domain.tx.TxReconciliationMissingWebhookAlert
import com.whatto.bcm.domain.tx.TxReconciliationTrackingStoppedAlert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OperationalAlertAdaptersTest {
    @Test
    fun `BAT 경보 포트를 유형별 운영 route로 변환하고 예외 메시지는 노출하지 않는다`() {
        val channel = RecordingOperationalAlertChannel()

        OperationalStallAlertAdapter(channel).alert(
            StallAlert("root-1", "active-1", StallAlertReason.PRE_CHAIN_DELAY, "0xhash"),
        )
        OperationalSweepExecutionAlertAdapter(channel).alert(
            SweepExecutionAlert(
                SweepExecutionStage.RECONCILIATION,
                SweepTargetKey("account-2", "ETHEREUM", "USDC"),
                IllegalStateException("address=0xsecret amount=1000000"),
            ),
        )
        OperationalTxReconciliationMissingWebhookAlertAdapter(channel).alert(
            TxReconciliationMissingWebhookAlert("20260817010000", "20260817020000", 2),
        )
        OperationalTxReconciliationTrackingStoppedAlertAdapter(channel).alert(
            TxReconciliationTrackingStoppedAlert("20260817030000", 1),
        )
        OperationalVendorBlockchainCatalogAlertAdapter(channel).chainIdChanged("ETH", 1, 2)

        assertThat(channel.alerts.map { it.route })
            .containsExactly(
                OperationalAlertRoute.TRANSACTION,
                OperationalAlertRoute.SWEEP,
                OperationalAlertRoute.RECONCILIATION,
                OperationalAlertRoute.RECONCILIATION,
                OperationalAlertRoute.ASSET,
            )
        assertThat(channel.alerts.map { it.type })
            .containsExactly(
                "transaction.stall.detected",
                "sweep.alert.reconciliation-failed",
                "reconciliation.alert.missing-webhook",
                "reconciliation.alert.tracking-stopped",
                "asset.alert.chain-id-changed",
            )
        assertThat(channel.alerts[1].context)
            .containsEntry("causeType", "IllegalStateException")
            .doesNotContainValue("address=0xsecret amount=1000000")
    }
}

private class RecordingOperationalAlertChannel : OperationalAlertChannel {
    val alerts = mutableListOf<OperationalAlert>()

    override fun publish(alert: OperationalAlert) {
        alerts += alert
    }
}
