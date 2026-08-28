package com.whatto.bcm.domain.monitoring

data class JobHeartbeat(
    val jobName: String,
    val lastRunAt: String,
    val lastSucceededAt: String?,
)

data class OperationalBacklog(
    val count: Long,
    val oldestAt: String?,
)

data class SweepOperationalSignals(
    val pendingRequestCount: Long,
    val oldestPendingRequestAt: String?,
    val blockedRequestCount: Long,
    val failedRequestCount: Long,
    val repeatedFailureTargetCount: Long,
    val pendingEventCount: Long,
    val failedEventCount: Long,
    val awaitingCompletionCount: Long,
    val oldestAwaitingCompletionAt: String?,
)

interface OperationalSignalRepository {
    fun pendingWebhookBacklog(): OperationalBacklog

    fun pendingOutboxBacklog(): OperationalBacklog

    fun stoppedReconciliationCount(): Long

    fun unarchivedCompletedWebhookCount(): Long

    fun sweepOperationalSignals(): SweepOperationalSignals

    fun heartbeats(): List<JobHeartbeat>
}
