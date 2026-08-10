package com.whatto.bcm.domain.event

/** transactional outbox에 적재할 발행 예약. payload는 ChainEvent의 JSON 표현이다. */
data class OutboxEvent(
    val eventId: String,
    val eventDate: String,
    val vendorTransactionId: String,
    val eventType: OutboxEventType,
    val topic: String,
    val payload: String,
    val maxRetryCount: Int,
    val originEventId: String? = null,
    val traceId: String? = null,
)

data class PendingOutboxEvent(
    val eventId: String,
    val topic: String,
    val payload: String,
)

data class OutboxFailureResult(
    val retryCount: Int,
    val quarantined: Boolean,
)

interface OutboxEventRepository {
    fun insertAll(events: List<OutboxEvent>)

    /** 여러 애플리케이션 인스턴스가 동시에 릴레이 순서를 소비하지 않도록 트랜잭션 범위 잠금을 획득한다. */
    fun tryAcquireRelayLock(): Boolean

    fun findNextPendingForUpdate(): PendingOutboxEvent?

    fun markDispatched(
        eventId: String,
        publishedAt: String,
    )

    fun markSuccess(eventId: String)

    fun recordFailure(
        eventId: String,
        safeReason: String,
        attemptedAt: String,
    ): OutboxFailureResult
}
