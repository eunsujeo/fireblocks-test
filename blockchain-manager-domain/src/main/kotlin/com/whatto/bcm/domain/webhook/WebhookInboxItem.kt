package com.whatto.bcm.domain.webhook

/** 판단 워커가 잠가 집은 웹훅 인박스 원본. */
data class WebhookInboxItem(
    val notificationId: String,
    val eventType: String,
    val vendorTransactionId: String?,
    val payload: String,
    val receivedAt: String,
    val retryCount: Int,
)

data class WebhookFailureResult(
    val retryCount: Int,
    val quarantined: Boolean,
)
