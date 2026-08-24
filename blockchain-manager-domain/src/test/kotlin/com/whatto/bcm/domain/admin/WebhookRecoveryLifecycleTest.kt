package com.whatto.bcm.domain.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class WebhookRecoveryLifecycleTest {
    @Test
    fun `요청만 기록된 복구는 ACCEPTED다`() {
        assertThat(WebhookRecoveryLifecycle.state(request(), emptyList(), NOW, TIMEOUT))
            .isEqualTo(WebhookRecoveryState.ACCEPTED)
    }

    @Test
    fun `결과 없는 intent는 기한 전 IN_PROGRESS이고 기한 뒤 AMBIGUOUS다`() {
        val intent = event(WebhookRecoveryEventStatus.RESEND_INTENT, NOW)

        assertThat(WebhookRecoveryLifecycle.state(request(), listOf(intent), NOW.plusSeconds(59), TIMEOUT))
            .isEqualTo(WebhookRecoveryState.IN_PROGRESS)
        assertThat(WebhookRecoveryLifecycle.state(request(), listOf(intent), NOW.plusSeconds(60), TIMEOUT))
            .isEqualTo(WebhookRecoveryState.AMBIGUOUS)
    }

    @Test
    fun `재전송 접수와 실패 결과는 각각 COMPLETED와 FAILED다`() {
        assertThat(
            WebhookRecoveryLifecycle.state(
                request(),
                listOf(event(WebhookRecoveryEventStatus.RESEND_ACCEPTED, NOW)),
                NOW,
                TIMEOUT,
            ),
        ).isEqualTo(WebhookRecoveryState.COMPLETED)
        assertThat(
            WebhookRecoveryLifecycle.state(
                request(),
                listOf(event(WebhookRecoveryEventStatus.FAILED, NOW)),
                NOW,
                TIMEOUT,
            ),
        ).isEqualTo(WebhookRecoveryState.FAILED)
    }

    @Test
    fun `Admin 요약은 재시도 가능 여부와 다음 조건 및 상태 조회 경로를 제공한다`() {
        val accepted = WebhookRecoveryView(request(), emptyList()).toAdminSummary(NOW, TIMEOUT)
        val ambiguous =
            WebhookRecoveryView(
                request(),
                listOf(event(WebhookRecoveryEventStatus.RESEND_INTENT, NOW.minusSeconds(60))),
            ).toAdminSummary(NOW, TIMEOUT)

        assertThat(accepted.retryable).isTrue()
        assertThat(accepted.retryCondition).isEqualTo("EXECUTION_READY")
        assertThat(accepted.statusPath).isEqualTo("/admin/execution-gates")
        assertThat(ambiguous.retryable).isFalse()
        assertThat(ambiguous.retryCondition).isEqualTo("STATUS_RECONCILIATION_REQUIRED")
    }

    private fun request() =
        WebhookRecoveryRequest(
            requestId = "request-1",
            webhookId = "webhook-1",
            scope = WebhookRecoveryScope.FAILED_LAST_24H,
            requiredEvents = setOf("transaction.created"),
            requiredEventsPayload = "[\"transaction.created\"]",
            requiredEventsHash = HASH,
            idempotencyKey = "recovery-1",
            reason = "수신 공백 복구",
            workTicket = "INC-100",
            requestedAt = NOW,
            requestedBy = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR)),
            approvedAt = NOW,
            approvedBy = AdminActor("830002", "0001", setOf(AdminRole.BCM_APPROVER)),
        )

    private fun event(
        status: WebhookRecoveryEventStatus,
        occurredAt: Instant,
    ) = WebhookRecoveryEvent(
        requestId = "request-1",
        sequence = 1,
        status = status,
        callType = WebhookRecoveryCallType.RESEND_FAILED,
        calledAt = occurredAt,
        resultAt = if (status.isIntent) null else occurredAt,
        webhookStatus = null,
        observedEvents = emptySet(),
        observedEventsPayload = null,
        observedEventsHash = null,
        scopeFrom = null,
        scopeTo = null,
        scheduledNotificationCount = null,
        responsePayload = null,
        responseHash = null,
        errorCode = if (status == WebhookRecoveryEventStatus.FAILED) "VENDOR_400" else null,
        occurredAt = occurredAt,
        actor = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR)),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-19T01:00:00Z")
        val TIMEOUT: Duration = Duration.ofSeconds(60)
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
