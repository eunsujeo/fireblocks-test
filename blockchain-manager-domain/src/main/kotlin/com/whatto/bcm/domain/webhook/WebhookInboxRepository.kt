package com.whatto.bcm.domain.webhook

interface WebhookInboxRepository {
    /** noti_id 충돌은 벤더 재전달이므로 예외가 아니라 DUPLICATE다. */
    fun insertIfAbsent(notification: WebhookNotification): WebhookInsertResult

    /**
     * P 상태의 가장 오래된 한 건을 잠가 집는다. 다중 워커는 SKIP LOCKED로 서로 다른 행을 받는다. **대기 시각이 지난 행만** 집는다(03 V29) —
     * 대기 중인 행을 집으면 backoff가 무의미해지고, 그 행이 head를 막아 뒤의 건도 밀린다.
     */
    fun findNextPendingForUpdate(now: String): WebhookInboxItem?

    fun markProcessed(
        notificationId: String,
        processedAt: String,
        vendorCompleted: Boolean,
    )

    /**
     * 실패 횟수를 올리고 상한에 닿으면 F로 격리한다.
     * 다음 시도 시각은 **저장소가 이번 시도 횟수로 계산한다**([WebhookRetryBackoff]와 같은 식) —
     * 호출자가 미리 계산하면 시도 횟수를 모르는 경로(롤백 뒤 별도 기록)가 매번 첫 대기를 쓰게 된다.
     * [baseSeconds]가 `0`이면 대기 없이 즉시 재시도한다.
     */
    fun recordFailure(
        notificationId: String,
        errorMessage: String,
        maxAttempts: Int,
        now: String,
        baseSeconds: Long,
    ): WebhookFailureResult
}
