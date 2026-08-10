package com.whatto.bcm.domain.webhook

/** 귀속할 주소가 없는 입금 신호. 구체 알림 채널은 Phase 9에서 바인딩한다. */
fun interface UnattributedDepositAlertPort {
    fun alert(alert: UnattributedDepositAlert)
}

data class UnattributedDepositAlert(
    val notificationId: String,
    val vendorTransactionId: String,
    val network: String,
    val symbol: String,
)

/** 제출 원장에 대응하지 않는 우리 vault 발신 신호. 콘솔 수동 조작 등 운영 사고 후보라 고객 이벤트로 내보내지 않는다. */
fun interface UnregisteredVaultTransferAlertPort {
    fun alert(alert: UnregisteredVaultTransferAlert)
}

data class UnregisteredVaultTransferAlert(
    val notificationId: String,
    val vendorTransactionId: String,
    val externalTransactionId: String?,
)

/** 재시도 상한을 넘겨 격리된 poison 웹훅 신호. */
fun interface PoisonWebhookAlertPort {
    fun alert(
        notificationId: String,
        retryCount: Int,
    )
}
