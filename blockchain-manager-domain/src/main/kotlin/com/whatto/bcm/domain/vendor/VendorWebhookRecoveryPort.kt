package com.whatto.bcm.domain.vendor

enum class VendorWebhookStatus {
    DISABLED,
    ENABLED,
    SUSPENDED,
}

data class VendorWebhookSubscription(
    val webhookId: String,
    val status: VendorWebhookStatus,
    val events: Set<String>,
)

data class VendorWebhookResendReceipt(
    val scheduledNotificationCount: Int,
) {
    init {
        require(scheduledNotificationCount >= 0) { "scheduledNotificationCount must not be negative" }
    }
}

interface VendorWebhookRecoveryPort {
    fun webhook(webhookId: String): VendorWebhookSubscription

    fun activateWebhook(webhookId: String): VendorWebhookSubscription

    fun resendFailedWebhookNotifications(webhookId: String): VendorWebhookResendReceipt
}
