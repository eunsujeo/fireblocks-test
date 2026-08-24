package com.whatto.bcm.app.application.asset

import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import com.whatto.bcm.domain.vendor.UnmappedVendorAssetAlert
import com.whatto.bcm.domain.vendor.UnmappedVendorAssetAlertPort
import org.springframework.stereotype.Component

@Component
class OperationalUnmappedVendorAssetAlertAdapter(
    private val channel: OperationalAlertChannel,
) : UnmappedVendorAssetAlertPort {
    override fun alert(alert: UnmappedVendorAssetAlert) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.ASSET,
                type = "asset.alert.unmapped-vendor-asset",
                identifiers = mapOf("accountId" to alert.accountId, "vendorAssetId" to alert.vendorAssetId),
                context = mapOf("transactionCount" to alert.vendorTransactionIds.size.toString()),
            ),
        )
    }
}
