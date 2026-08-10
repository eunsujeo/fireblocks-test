package com.whatto.bcm.domain.webhook

/** 서명 검증을 통과한 웹훅 수신 원본. payload는 파싱·재직렬화 전 UTF-8 와이어 본문이다. */
data class WebhookNotification(
    val notificationId: String,
    val eventType: String,
    val vendorTransactionId: String?,
    val payload: String,
    val payloadHash: String,
    val signature: String,
    val receivedAt: String,
)

enum class WebhookInsertResult {
    INSERTED,
    DUPLICATE,
}
