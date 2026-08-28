package com.whatto.bcm.domain.event

data class EventCompletion(
    val eventId: String,
    val consumer: String,
    val completedAt: String,
    val transactionId: String?,
    val status: String?,
    val sweepRequestId: String?,
    val sweepItemId: String?,
    val executionId: String?,
)

sealed interface EventCompletionResult {
    data class Completed(
        val completion: EventCompletion,
    ) : EventCompletionResult

    data class AlreadyCompleted(
        val completion: EventCompletion,
    ) : EventCompletionResult

    data object NotFound : EventCompletionResult

    data object NotPublished : EventCompletionResult

    data object NotConsumable : EventCompletionResult
}

interface EventCompletionRepository {
    fun complete(
        eventId: String,
        consumer: String,
        completedAt: String,
    ): EventCompletionResult
}
