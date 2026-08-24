package com.whatto.bcm.domain.webhook

import com.whatto.bcm.domain.vendor.VendorStatusObservation

/** 벤더 Webhook 원문을 application이 소비할 중립 관찰값으로 변환하는 포트. */
fun interface WebhookTransactionParser {
    fun parse(payload: String): WebhookTransaction
}

data class WebhookTransaction(
    val vendorTransactionId: String,
    val vendorAssetId: String,
    val managedVaultSource: Boolean,
    val sourceAddress: String?,
    val destinationAddress: String?,
    val amount: String,
    val statusObservation: VendorStatusObservation,
    val networkStatus: String?,
    val transactionHash: String?,
    val externalTransactionId: String?,
    val createdAtEpochMillis: Long,
) {
    val subStatus: String?
        get() = statusObservation.subStatus

    val confirmationCount: Int
        get() = statusObservation.confirmationCount
}
