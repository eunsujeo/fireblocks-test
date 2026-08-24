package com.whatto.bcm.app.bat.webhook

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.WebhookRecoveryEvent
import com.whatto.bcm.domain.admin.WebhookRecoveryEventStatus
import com.whatto.bcm.domain.admin.WebhookRecoveryRepository
import com.whatto.bcm.domain.admin.WebhookRecoveryRequest
import com.whatto.bcm.domain.admin.WebhookRecoveryState
import com.whatto.bcm.domain.admin.WebhookRecoveryView
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.VendorWebhookRecoveryPort
import com.whatto.bcm.domain.vendor.VendorWebhookResendReceipt
import com.whatto.bcm.domain.vendor.VendorWebhookStatus
import com.whatto.bcm.domain.vendor.VendorWebhookSubscription
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class WebhookRecoveryServiceTest {
    @Test
    fun `복구 요청은 벤더 호출 없이 ACCEPTED로 먼저 기록되고 같은 내용 재요청은 같은 ID를 반환한다`() {
        val fixture = fixture(VendorWebhookStatus.ENABLED)

        val first = fixture.service.request(requestCommand())
        val second = fixture.service.request(requestCommand())

        assertThat(first.state).isEqualTo(WebhookRecoveryState.ACCEPTED)
        assertThat(second.requestId).isEqualTo(first.requestId)
        assertThat(fixture.vendor.calls).isEmpty()
        assertThat(
            fixture.repository
                .find(first.requestId)
                ?.request
                ?.requiredEvents,
        ).containsExactlyInAnyOrderElementsOf(WebhookRecoveryService.REQUIRED_EVENTS)
        assertThat(
            fixture.repository
                .find(first.requestId)
                ?.request
                ?.approvedBy
                ?.employeeNo,
        ).isEqualTo("830002")
    }

    @Test
    fun `중단된 구독은 상태 조회와 활성화 intent 및 결과 뒤에 최근 24시간 실패 알림을 접수한다`() {
        val fixture = fixture(VendorWebhookStatus.SUSPENDED, scheduledCount = 2)
        val accepted = fixture.service.request(requestCommand())

        val result = fixture.service.execute(executeCommand(accepted.requestId))

        assertThat(fixture.vendor.calls).containsExactly("get", "activate", "resend")
        assertThat(result.state).isEqualTo(WebhookRecoveryState.COMPLETED)
        assertThat(result.previousStatus).isEqualTo("SUSPENDED")
        assertThat(result.currentStatus).isEqualTo("ENABLED")
        assertThat(result.scheduledNotificationCount).isEqualTo(2)
        assertThat(result.scopeFrom).isEqualTo(NOW.minusSeconds(86_400))
        assertThat(result.scopeTo).isEqualTo(NOW)
        assertThat(
            fixture.repository
                .find(accepted.requestId)
                ?.events
                ?.map { it.status },
        ).containsExactly(
            WebhookRecoveryEventStatus.STATUS_INTENT,
            WebhookRecoveryEventStatus.STATUS_OBSERVED,
            WebhookRecoveryEventStatus.ACTIVATE_INTENT,
            WebhookRecoveryEventStatus.ACTIVATED,
            WebhookRecoveryEventStatus.RESEND_INTENT,
            WebhookRecoveryEventStatus.RESEND_ACCEPTED,
        )
    }

    @Test
    fun `필수 이벤트가 빠지면 구독 범위를 바꾸거나 활성화 및 재전송하지 않고 실패한다`() {
        val fixture =
            fixture(
                VendorWebhookStatus.SUSPENDED,
                events = WebhookRecoveryService.REQUIRED_EVENTS - "transaction.status.updated",
            )
        val accepted = fixture.service.request(requestCommand())

        val result = fixture.service.execute(executeCommand(accepted.requestId))

        assertThat(fixture.vendor.calls).containsExactly("get")
        assertThat(result.state).isEqualTo(WebhookRecoveryState.FAILED)
        assertThat(result.errorCode).isEqualTo("REQUIRED_EVENTS_MISSING")
        assertThat(result.missingRequiredEvents).containsExactly("transaction.status.updated")
    }

    @Test
    fun `5xx나 응답 유실은 intent를 남기고 자동 재호출하지 않으며 기한 뒤 AMBIGUOUS다`() {
        val fixture = fixture(VendorWebhookStatus.ENABLED, failure = VendorApiException("webhook", 503))
        val accepted = fixture.service.request(requestCommand())

        assertThatThrownBy { fixture.service.execute(executeCommand(accepted.requestId)) }
            .isInstanceOf(VendorApiException::class.java)
        fixture.clock.advanceSeconds(60)
        val result = fixture.service.execute(executeCommand(accepted.requestId))

        assertThat(result.state).isEqualTo(WebhookRecoveryState.AMBIGUOUS)
        assertThat(fixture.vendor.calls).containsExactly("get")
        assertThat(
            fixture.repository
                .find(accepted.requestId)
                ?.events
                ?.single()
                ?.status,
        ).isEqualTo(WebhookRecoveryEventStatus.STATUS_INTENT)
    }

    @Test
    fun `확정 4xx는 안전한 오류 코드만 결과 원장에 남긴다`() {
        val fixture = fixture(VendorWebhookStatus.ENABLED, failure = VendorApiException("webhook", 400))
        val accepted = fixture.service.request(requestCommand())

        val result = fixture.service.execute(executeCommand(accepted.requestId))

        assertThat(result.state).isEqualTo(WebhookRecoveryState.FAILED)
        assertThat(result.errorCode).isEqualTo("VENDOR_400")
        assertThat(
            fixture.repository
                .find(accepted.requestId)
                ?.events
                ?.last()
                ?.responsePayload,
        ).isNull()
    }

    @Test
    fun `같은 멱등 키의 다른 요청 내용은 충돌한다`() {
        val fixture = fixture(VendorWebhookStatus.ENABLED)
        fixture.service.request(requestCommand())

        assertThatThrownBy { fixture.service.request(requestCommand().copy(reason = "다른 사유")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `요청자와 승인자는 서로 달라야 한다`() {
        val fixture = fixture(VendorWebhookStatus.ENABLED)

        val sameActor = ACTOR.copy(roles = setOf(AdminRole.BCM_OPERATOR, AdminRole.BCM_APPROVER))
        assertThatThrownBy { fixture.service.request(requestCommand().copy(approver = sameActor)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fixture.repository.findRecent(10)).isEmpty()
    }

    private fun fixture(
        status: VendorWebhookStatus,
        events: Set<String> = WebhookRecoveryService.REQUIRED_EVENTS,
        scheduledCount: Int = 0,
        failure: VendorApiException? = null,
    ): Fixture {
        val clock = MutableClock(NOW)
        val repository = InMemoryWebhookRecoveryRepository()
        val vendor = RecordingWebhookRecoveryPort(status, events, scheduledCount, failure)
        return Fixture(
            WebhookRecoveryService(
                vendor,
                repository,
                DirectTransactionRunner,
                WebhookRecoveryIdGenerator { "request-1" },
                WebhookRecoveryProperties(webhookId = WEBHOOK_ID, intentTimeoutSeconds = 60),
                clock,
            ),
            repository,
            vendor,
            clock,
        )
    }

    private fun requestCommand() = RequestWebhookRecoveryCommand("recovery-1", "수신 공백 복구", "INC-100", ACTOR, APPROVER)

    private fun executeCommand(requestId: String) = ExecuteWebhookRecoveryCommand(requestId, ACTOR)

    private data class Fixture(
        val service: WebhookRecoveryService,
        val repository: InMemoryWebhookRecoveryRepository,
        val vendor: RecordingWebhookRecoveryPort,
        val clock: MutableClock,
    )

    private class InMemoryWebhookRecoveryRepository : WebhookRecoveryRepository {
        private val requests = linkedMapOf<String, WebhookRecoveryRequest>()
        private val events = linkedMapOf<String, MutableList<WebhookRecoveryEvent>>()

        override fun findRecent(limit: Int): List<WebhookRecoveryView> =
            requests.values
                .toList()
                .takeLast(limit)
                .reversed()
                .mapNotNull { find(it.requestId) }

        override fun find(requestId: String): WebhookRecoveryView? =
            requests[requestId]?.let { request ->
                WebhookRecoveryView(
                    request.copy(
                        requestedBy = request.requestedBy.copy(roles = emptySet()),
                        approvedBy = request.approvedBy.copy(roles = emptySet()),
                    ),
                    events[requestId].orEmpty().map { it.copy(actor = it.actor.copy(roles = emptySet())) },
                )
            }

        override fun findForUpdate(requestId: String): WebhookRecoveryView? = find(requestId)

        override fun findByIdempotency(
            employeeNo: String,
            idempotencyKey: String,
        ): WebhookRecoveryView? =
            requests.values.firstOrNull { it.requestedBy.employeeNo == employeeNo && it.idempotencyKey == idempotencyKey }?.let {
                find(it.requestId)
            }

        override fun insertRequest(request: WebhookRecoveryRequest): WebhookRecoveryRequest {
            check(requests.putIfAbsent(request.requestId, request) == null)
            events[request.requestId] = mutableListOf()
            return request
        }

        override fun appendEvent(event: WebhookRecoveryEvent): WebhookRecoveryEvent {
            events.getValue(event.requestId) += event
            return event
        }
    }

    private class RecordingWebhookRecoveryPort(
        status: VendorWebhookStatus,
        private val events: Set<String>,
        private val scheduledCount: Int,
        private val failure: VendorApiException?,
    ) : VendorWebhookRecoveryPort {
        private var currentStatus = status
        val calls = mutableListOf<String>()

        override fun webhook(webhookId: String): VendorWebhookSubscription {
            calls += "get"
            failure?.let { throw it }
            return VendorWebhookSubscription(webhookId, currentStatus, events)
        }

        override fun activateWebhook(webhookId: String): VendorWebhookSubscription {
            calls += "activate"
            currentStatus = VendorWebhookStatus.ENABLED
            return VendorWebhookSubscription(webhookId, currentStatus, events)
        }

        override fun resendFailedWebhookNotifications(webhookId: String): VendorWebhookResendReceipt {
            calls += "resend"
            return VendorWebhookResendReceipt(scheduledCount)
        }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    private object DirectTransactionRunner : TransactionRunner {
        override fun <T> run(block: () -> T): T = block()
    }

    private companion object {
        const val WEBHOOK_ID = "44fcead0-7053-4831-a53a-df7fb90d440f"
        val NOW: Instant = Instant.parse("2026-08-19T01:00:00Z")
        val ACTOR = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR))
        val APPROVER = AdminActor("830002", "0001", setOf(AdminRole.BCM_APPROVER))
    }
}
