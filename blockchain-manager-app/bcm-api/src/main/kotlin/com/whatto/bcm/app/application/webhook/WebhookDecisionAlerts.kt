package com.whatto.bcm.app.application.webhook

import com.whatto.bcm.domain.vendor.UnmappedVendorAssetAlert
import com.whatto.bcm.domain.vendor.UnmappedVendorAssetAlertPort
import com.whatto.bcm.domain.webhook.PoisonWebhookAlertPort
import com.whatto.bcm.domain.webhook.UnattributedDepositAlert
import com.whatto.bcm.domain.webhook.UnattributedDepositAlertPort
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlert
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlertPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Phase 9의 실제 알림 채널 바인딩 전 기본 운영 신호. 주소·금액·payload는 로그에 남기지 않는다. */
@Component
class LoggingUnattributedDepositAlertAdapter : UnattributedDepositAlertPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun alert(alert: UnattributedDepositAlert) {
        logger.error(
            "Unattributed deposit notificationId={} vendorTransactionId={} network={} symbol={}",
            alert.notificationId,
            alert.vendorTransactionId,
            alert.network,
            alert.symbol,
        )
    }
}

@Component
class LoggingUnregisteredVaultTransferAlertAdapter : UnregisteredVaultTransferAlertPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun alert(alert: UnregisteredVaultTransferAlert) {
        logger.error(
            "Unregistered vault transfer notificationId={} vendorTransactionId={} externalTransactionId={}",
            alert.notificationId,
            alert.vendorTransactionId,
            alert.externalTransactionId,
        )
    }
}

@Component
class LoggingUnmappedVendorAssetAlertAdapter : UnmappedVendorAssetAlertPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun alert(alert: UnmappedVendorAssetAlert) {
        logger.error(
            "Unmapped vendor asset hid transactions accountId={} vendorAssetId={} vendorTransactionIds={}",
            alert.accountId,
            alert.vendorAssetId,
            alert.vendorTransactionIds,
        )
    }
}

@Component
class LoggingPoisonWebhookAlertAdapter : PoisonWebhookAlertPort {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun alert(
        notificationId: String,
        retryCount: Int,
    ) {
        logger.error("Poison webhook quarantined notificationId={} retryCount={}", notificationId, retryCount)
    }
}
