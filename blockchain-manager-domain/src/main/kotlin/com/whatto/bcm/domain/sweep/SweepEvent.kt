package com.whatto.bcm.domain.sweep

/** DAW-CORE에 발행할 고객 sweep 항목 결과. eventId와 outbox 메타데이터는 publisher가 부여한다. */
data class SweepItemOutcomeEvent(
    val sweepRequestId: String,
    val sweepItemId: String,
    val executionId: String?,
    val txId: String?,
    val vendorTxId: String?,
    val txHash: String?,
    val accountId: String,
    val network: String,
    val symbol: String,
    val requestedAmount: String,
    val actualAmount: String?,
    val chainStatus: SweepChainStatus,
    val itemOutcome: SweepItemOutcome,
    val failureCode: String?,
)

enum class SweepChainStatus {
    NOT_SUBMITTED,
    FINALIZED,
    FAILED,
}

enum class SweepItemOutcome {
    NO_SWEEP_REQUIRED,
    SUCCEEDED,
    FAILED,
}

fun interface SweepEventPublisher {
    fun publish(events: List<SweepItemOutcomeEvent>)
}

data class SweepEventPayload(
    val eventId: String,
    val type: String = "SWEEP",
    val sweepRequestId: String,
    val sweepItemId: String,
    val executionId: String?,
    val txId: String?,
    val vendorTxId: String?,
    val txHash: String?,
    val accountId: String,
    val network: String,
    val symbol: String,
    val requestedAmount: String,
    val actualAmount: String?,
    val chainStatus: SweepChainStatus,
    val itemOutcome: SweepItemOutcome,
    val failureCode: String?,
)

fun interface SweepEventSerializer {
    fun serialize(event: SweepEventPayload): String
}
