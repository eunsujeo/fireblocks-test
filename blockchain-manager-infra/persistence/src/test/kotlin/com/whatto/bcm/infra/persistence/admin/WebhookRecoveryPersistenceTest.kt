package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.WebhookRecoveryCallType
import com.whatto.bcm.domain.admin.WebhookRecoveryEvent
import com.whatto.bcm.domain.admin.WebhookRecoveryEventStatus
import com.whatto.bcm.domain.admin.WebhookRecoveryRepository
import com.whatto.bcm.domain.admin.WebhookRecoveryRequest
import com.whatto.bcm.domain.admin.WebhookRecoveryScope
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

@DataJdbcTest
@Import(WebhookRecoveryJdbcAdapter::class)
class WebhookRecoveryPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var recoveries: WebhookRecoveryRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `요청과 상태조회 및 재전송 결과를 append-only로 왕복한다`() {
        recoveries.insertRequest(request())
        recoveries.appendEvent(event(1, WebhookRecoveryEventStatus.STATUS_INTENT, WebhookRecoveryCallType.STATUS_QUERY))
        recoveries.appendEvent(
            event(
                2,
                WebhookRecoveryEventStatus.STATUS_OBSERVED,
                WebhookRecoveryCallType.STATUS_QUERY,
                result = true,
                webhookStatus = "ENABLED",
                observedEventsPayload = EVENTS,
                responsePayload = SUBSCRIPTION_RESPONSE,
            ),
        )
        recoveries.appendEvent(
            event(
                3,
                WebhookRecoveryEventStatus.RESEND_INTENT,
                WebhookRecoveryCallType.RESEND_FAILED,
                scope = true,
            ),
        )
        recoveries.appendEvent(
            event(
                4,
                WebhookRecoveryEventStatus.RESEND_ACCEPTED,
                WebhookRecoveryCallType.RESEND_FAILED,
                result = true,
                scope = true,
                scheduledCount = 2,
                responsePayload = RESEND_RESPONSE,
            ),
        )

        val saved = requireNotNull(recoveries.find("request-1"))

        assertThat(saved.request.requiredEvents).containsExactlyInAnyOrderElementsOf(REQUIRED_EVENTS)
        assertThat(saved.events.map { it.status }).containsExactly(
            WebhookRecoveryEventStatus.STATUS_INTENT,
            WebhookRecoveryEventStatus.STATUS_OBSERVED,
            WebhookRecoveryEventStatus.RESEND_INTENT,
            WebhookRecoveryEventStatus.RESEND_ACCEPTED,
        )
        assertThat(saved.events.last().scheduledNotificationCount).isEqualTo(2)
        assertThatThrownBy {
            jdbc.update("UPDATE bcm_whk_rcvr_req_l SET req_rsn = 'changed' WHERE rcvr_req_id = 'request-1'")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `복구 event는 삭제할 수 없다`() {
        recoveries.insertRequest(request())
        recoveries.appendEvent(event(1, WebhookRecoveryEventStatus.STATUS_INTENT, WebhookRecoveryCallType.STATUS_QUERY))

        assertThatThrownBy {
            jdbc.update("DELETE FROM bcm_whk_rcvr_evt_l WHERE rcvr_req_id = 'request-1'")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 작업자 멱등 키는 DB가 거절한다`() {
        recoveries.insertRequest(request())

        assertThatThrownBy { recoveries.insertRequest(request().copy(requestId = "request-2")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `요청자와 같은 승인자는 DB가 거절한다`() {
        assertThatThrownBy { recoveries.insertRequest(request().copy(approvedBy = ACTOR)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `최근 요청 조회는 요청과 event를 함께 반환한다`() {
        recoveries.insertRequest(request())
        recoveries.appendEvent(event(1, WebhookRecoveryEventStatus.STATUS_INTENT, WebhookRecoveryCallType.STATUS_QUERY))

        val saved = recoveries.findRecent(10).single()

        assertThat(saved.request.requestId).isEqualTo("request-1")
        assertThat(saved.events)
            .singleElement()
            .extracting(WebhookRecoveryEvent::status)
            .isEqualTo(WebhookRecoveryEventStatus.STATUS_INTENT)
    }

    @Test
    fun `연속되지 않은 event는 DB가 거절한다`() {
        recoveries.insertRequest(request())
        recoveries.appendEvent(event(1, WebhookRecoveryEventStatus.STATUS_INTENT, WebhookRecoveryCallType.STATUS_QUERY))
        recoveries.appendEvent(
            event(
                2,
                WebhookRecoveryEventStatus.STATUS_OBSERVED,
                WebhookRecoveryCallType.STATUS_QUERY,
                result = true,
                webhookStatus = "ENABLED",
                observedEventsPayload = "[\"transaction.created\"]",
                responsePayload = "{\"events\":[\"transaction.created\"],\"status\":\"ENABLED\",\"webhookId\":\"webhook-1\"}",
            ),
        )

        assertThatThrownBy {
            recoveries.appendEvent(
                event(4, WebhookRecoveryEventStatus.RESEND_INTENT, WebhookRecoveryCallType.RESEND_FAILED, scope = true),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `필수 이벤트 누락 뒤 재전송은 DB가 거절한다`() {
        recoveries.insertRequest(request())
        recoveries.appendEvent(event(1, WebhookRecoveryEventStatus.STATUS_INTENT, WebhookRecoveryCallType.STATUS_QUERY))
        recoveries.appendEvent(
            event(
                2,
                WebhookRecoveryEventStatus.STATUS_OBSERVED,
                WebhookRecoveryCallType.STATUS_QUERY,
                result = true,
                webhookStatus = "ENABLED",
                observedEventsPayload = "[\"transaction.created\"]",
                responsePayload = "{\"events\":[\"transaction.created\"],\"status\":\"ENABLED\",\"webhookId\":\"webhook-1\"}",
            ),
        )

        assertThatThrownBy {
            recoveries.appendEvent(
                event(3, WebhookRecoveryEventStatus.RESEND_INTENT, WebhookRecoveryCallType.RESEND_FAILED, scope = true),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun request() =
        WebhookRecoveryRequest(
            requestId = "request-1",
            webhookId = "webhook-1",
            scope = WebhookRecoveryScope.FAILED_LAST_24H,
            requiredEvents = REQUIRED_EVENTS,
            requiredEventsPayload = EVENTS,
            requiredEventsHash = HASH,
            idempotencyKey = "recovery-1",
            reason = "수신 공백 복구",
            workTicket = "INC-100",
            requestedAt = NOW,
            requestedBy = ACTOR,
            approvedAt = NOW,
            approvedBy = APPROVER,
        )

    private fun event(
        sequence: Int,
        status: WebhookRecoveryEventStatus,
        callType: WebhookRecoveryCallType,
        result: Boolean = false,
        webhookStatus: String? = null,
        observedEventsPayload: String? = null,
        responsePayload: String? = null,
        scope: Boolean = false,
        scheduledCount: Int? = null,
    ) = WebhookRecoveryEvent(
        requestId = "request-1",
        sequence = sequence,
        status = status,
        callType = callType,
        calledAt = NOW,
        resultAt = NOW.takeIf { result },
        webhookStatus = webhookStatus,
        observedEvents = if (observedEventsPayload == null) emptySet() else REQUIRED_EVENTS,
        observedEventsPayload = observedEventsPayload,
        observedEventsHash = HASH.takeIf { observedEventsPayload != null },
        scopeFrom = NOW.minusSeconds(86_400).takeIf { scope },
        scopeTo = NOW.takeIf { scope },
        scheduledNotificationCount = scheduledCount,
        responsePayload = responsePayload,
        responseHash = HASH.takeIf { responsePayload != null },
        errorCode = null,
        occurredAt = NOW,
        actor = ACTOR,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-19T01:00:00Z")
        val ACTOR = AdminActor("830001", "0001", emptySet())
        val APPROVER = AdminActor("830002", "0001", emptySet())
        val REQUIRED_EVENTS = setOf("transaction.created", "transaction.status.updated")
        const val EVENTS = "[\"transaction.created\",\"transaction.status.updated\"]"
        const val SUBSCRIPTION_RESPONSE =
            "{\"events\":[\"transaction.created\",\"transaction.status.updated\"],\"status\":\"ENABLED\",\"webhookId\":\"webhook-1\"}"
        const val RESEND_RESPONSE = "{\"total\":2}"
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
