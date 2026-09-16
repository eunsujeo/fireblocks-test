package com.whatto.bcm.domain.webhook

/**
 * 벤더가 한 번 전달한 알림의 메타 — 어떤 종류의 사건이든 같은 값을 갖는다(계약13).
 * 알림 ID는 인박스 dedup 키이며 논리 사건 ID가 아니다. 재전달은 알림 ID가 다르고 [retryOfNotificationId]로 원본을 가리킨다.
 */
data class VendorWebhookDelivery(
    val notificationId: String,
    /** 사건 발생 시각 — 벤더가 UTC ISO 8601로 정의한 원문 값. */
    val occurredAt: String,
    /** 전달 시도 번호 — 벤더가 필수·1 이상으로 정의한다. */
    val deliveryAttempt: Int,
    /** 재전달이면 원본 알림 ID. */
    val retryOfNotificationId: String?,
) {
    init {
        require(notificationId.isNotBlank()) { "notificationId must not be blank" }
        require(occurredAt.isNotBlank()) { "occurredAt must not be blank" }
        require(deliveryAttempt >= 1) { "deliveryAttempt must be positive" }
        require(retryOfNotificationId == null || retryOfNotificationId.isNotBlank()) { "retryOfNotificationId must not be blank" }
    }
}
