package com.whatto.bcm.domain.sweep

enum class SweepRequestStatus {
    ACCEPTED,
    BLOCKED,
    PROCESSING,
    COMPLETED,
    PARTIAL,
    FAILED,
}

enum class SweepRequestItemStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
}

data class SweepRequestItem(
    val sweepItemId: String,
    val sequence: Int,
    val accountId: String,
    val status: SweepRequestItemStatus,
    val sourceEventIds: List<String>,
    val lastFailureCode: String? = null,
)

data class SweepRequest(
    val sweepRequestId: String,
    val externalSweepRequestId: String,
    val requestHash: String,
    val network: String,
    val symbol: String,
    val status: SweepRequestStatus,
    val requestedAt: String,
    val finishedAt: String? = null,
    val items: List<SweepRequestItem>,
)

sealed interface SweepRequestAcceptance {
    data class Created(
        val request: SweepRequest,
    ) : SweepRequestAcceptance

    data class Existing(
        val request: SweepRequest,
    ) : SweepRequestAcceptance

    data object HashConflict : SweepRequestAcceptance

    data class SourceEventNotFound(
        val eventId: String,
    ) : SweepRequestAcceptance

    data class SourceEventInvalid(
        val eventId: String,
    ) : SweepRequestAcceptance

    data class SourceEventNotCompleted(
        val eventId: String,
    ) : SweepRequestAcceptance

    data class SourceEventConsumed(
        val eventId: String,
    ) : SweepRequestAcceptance
}

interface SweepRequestRepository {
    fun findByExternalRequestId(externalSweepRequestId: String): SweepRequest?

    fun accept(request: SweepRequest): SweepRequestAcceptance
}
