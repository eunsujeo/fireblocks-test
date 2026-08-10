package com.whatto.bcm.domain.webhook

interface WebhookInboxRepository {
    /** noti_id 충돌은 벤더 재전달이므로 예외가 아니라 DUPLICATE다. */
    fun insertIfAbsent(notification: WebhookNotification): WebhookInsertResult

    /** P 상태의 가장 오래된 한 건을 잠가 집는다. 다중 워커는 SKIP LOCKED로 서로 다른 행을 받는다. */
    fun findNextPendingForUpdate(): WebhookInboxItem?

    fun markProcessed(
        notificationId: String,
        processedAt: String,
    )

    /** 실패 횟수를 올리고 상한에 닿으면 F로 격리한다. */
    fun recordFailure(
        notificationId: String,
        errorMessage: String,
        maxAttempts: Int,
    ): WebhookFailureResult
}
