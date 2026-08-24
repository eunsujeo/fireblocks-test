package com.whatto.bcm.domain.admin

import java.time.Duration
import java.time.Instant

enum class WebhookRecoveryScope {
    FAILED_LAST_24H,
}

enum class WebhookRecoveryCallType {
    STATUS_QUERY,
    ACTIVATE,
    RESEND_FAILED,
}

enum class WebhookRecoveryEventStatus(
    val isIntent: Boolean,
) {
    STATUS_INTENT(true),
    STATUS_OBSERVED(false),
    ACTIVATE_INTENT(true),
    ACTIVATED(false),
    RESEND_INTENT(true),
    RESEND_ACCEPTED(false),
    FAILED(false),
}

enum class WebhookRecoveryState {
    ACCEPTED,
    IN_PROGRESS,
    AMBIGUOUS,
    FAILED,
    COMPLETED,
}

data class WebhookRecoveryRequest(
    val requestId: String,
    val webhookId: String,
    val scope: WebhookRecoveryScope,
    val requiredEvents: Set<String>,
    val requiredEventsPayload: String,
    val requiredEventsHash: String,
    val idempotencyKey: String,
    val reason: String,
    val workTicket: String,
    val requestedAt: Instant,
    val requestedBy: AdminActor,
    val approvedAt: Instant,
    val approvedBy: AdminActor,
)

data class WebhookRecoveryEvent(
    val requestId: String,
    val sequence: Int,
    val status: WebhookRecoveryEventStatus,
    val callType: WebhookRecoveryCallType,
    val calledAt: Instant,
    val resultAt: Instant?,
    val webhookStatus: String?,
    val observedEvents: Set<String>,
    val observedEventsPayload: String?,
    val observedEventsHash: String?,
    val scopeFrom: Instant?,
    val scopeTo: Instant?,
    val scheduledNotificationCount: Int?,
    val responsePayload: String?,
    val responseHash: String?,
    val errorCode: String?,
    val occurredAt: Instant,
    val actor: AdminActor,
)

data class WebhookRecoveryView(
    val request: WebhookRecoveryRequest,
    val events: List<WebhookRecoveryEvent>,
) {
    fun state(
        now: Instant,
        intentTimeout: Duration,
    ): WebhookRecoveryState = WebhookRecoveryLifecycle.state(request, events, now, intentTimeout)
}

object WebhookRecoveryLifecycle {
    fun state(
        request: WebhookRecoveryRequest,
        events: List<WebhookRecoveryEvent>,
        now: Instant,
        intentTimeout: Duration,
    ): WebhookRecoveryState {
        require(intentTimeout > Duration.ZERO) { "intentTimeout must be positive" }
        require(events.all { it.requestId == request.requestId }) { "webhook recovery event request mismatch" }
        val latest = events.maxByOrNull { it.sequence } ?: return WebhookRecoveryState.ACCEPTED
        return when {
            latest.status == WebhookRecoveryEventStatus.RESEND_ACCEPTED -> WebhookRecoveryState.COMPLETED
            latest.status == WebhookRecoveryEventStatus.FAILED -> WebhookRecoveryState.FAILED
            latest.status.isIntent && !now.isBefore(latest.calledAt.plus(intentTimeout)) -> WebhookRecoveryState.AMBIGUOUS
            else -> WebhookRecoveryState.IN_PROGRESS
        }
    }
}

interface WebhookRecoveryRepository {
    fun findRecent(limit: Int): List<WebhookRecoveryView>

    fun find(requestId: String): WebhookRecoveryView?

    fun findForUpdate(requestId: String): WebhookRecoveryView?

    fun findByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): WebhookRecoveryView?

    fun insertRequest(request: WebhookRecoveryRequest): WebhookRecoveryRequest

    fun appendEvent(event: WebhookRecoveryEvent): WebhookRecoveryEvent
}
