package com.whatto.bcm.infra.persistence.event

import com.whatto.bcm.domain.event.EventCompletionResult
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(OutboxJdbcAdapter::class, EventCompletionJdbcAdapter::class)
class EventCompletionPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var outbox: OutboxJdbcAdapter

    @Autowired
    lateinit var completions: EventCompletionJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `발행 성공 이벤트만 완료하고 재호출은 최초 완료 시각을 보존한다`() {
        outbox.insertAll(listOf(event()))
        assertThat(completions.complete(EVENT_ID, "DAW_CORE", "20260827010203"))
            .isEqualTo(EventCompletionResult.NotPublished)

        outbox.markDispatched(EVENT_ID, "20260827010000")
        outbox.markSuccess(EVENT_ID)

        val first = completions.complete(EVENT_ID, "DAW_CORE", "20260827010203")
        val repeated = completions.complete(EVENT_ID, "DAW_CORE", "20260827010500")

        assertThat(first).isInstanceOf(EventCompletionResult.Completed::class.java)
        assertThat(repeated).isInstanceOf(EventCompletionResult.AlreadyCompleted::class.java)
        assertThat((repeated as EventCompletionResult.AlreadyCompleted).completion.completedAt)
            .isEqualTo("20260827010203")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_evnt_cmpl_l", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `없는 이벤트와 DAW CORE 대상이 아닌 토픽을 거절한다`() {
        assertThat(completions.complete(EVENT_ID, "DAW_CORE", "20260827010203"))
            .isEqualTo(EventCompletionResult.NotFound)

        outbox.insertAll(listOf(event(topic = "operator-alerts")))
        outbox.markDispatched(EVENT_ID, "20260827010000")
        outbox.markSuccess(EVENT_ID)

        assertThat(completions.complete(EVENT_ID, "DAW_CORE", "20260827010203"))
            .isEqualTo(EventCompletionResult.NotConsumable)
    }

    @Test
    fun `같은 txId의 CONFIRMED FINALIZED reorg FAILED는 eventId별로 독립 완료한다`() {
        val events =
            listOf(
                event(CONFIRMED_EVENT_ID, OutboxEventType.CHECKING, "CONFIRMED"),
                event(FINALIZED_EVENT_ID, OutboxEventType.CONFIRMED, "FINALIZED"),
                event(FAILED_EVENT_ID, OutboxEventType.FAILED, "FAILED"),
            )
        outbox.insertAll(events)
        events.forEachIndexed { index, event ->
            outbox.markDispatched(event.eventId, "20260827010${index}00")
            outbox.markSuccess(event.eventId)
        }

        val finalized = completions.complete(FINALIZED_EVENT_ID, "DAW_CORE", "20260827011000")
        assertThat((finalized as EventCompletionResult.Completed).completion)
            .extracting("eventId", "transactionId", "status", "completedAt")
            .containsExactly(FINALIZED_EVENT_ID, "tx-91c", "FINALIZED", "20260827011000")
        assertThat(completedEventIds()).containsExactly(FINALIZED_EVENT_ID)

        completions.complete(CONFIRMED_EVENT_ID, "DAW_CORE", "20260827011100")
        assertThat(completedEventIds()).containsExactly(CONFIRMED_EVENT_ID, FINALIZED_EVENT_ID)

        completions.complete(FAILED_EVENT_ID, "DAW_CORE", "20260827011200")
        assertThat(completedEventIds()).containsExactly(CONFIRMED_EVENT_ID, FAILED_EVENT_ID, FINALIZED_EVENT_ID)
        assertThat(
            jdbc.queryForList(
                "SELECT cmpl_dttm FROM bcm_evnt_cmpl_l ORDER BY evnt_id",
                String::class.java,
            ),
        ).containsExactly("20260827011100", "20260827011200", "20260827011000")
    }

    private fun completedEventIds(): List<String> =
        jdbc
            .queryForList(
                "SELECT evnt_id FROM bcm_evnt_cmpl_l ORDER BY evnt_id",
                String::class.java,
            ).map(::requireNotNull)

    private fun event(
        eventId: String = EVENT_ID,
        eventType: OutboxEventType = OutboxEventType.CONFIRMED,
        status: String = "FINALIZED",
        topic: String = "deposit-events",
    ) = OutboxEvent(
        eventId = eventId,
        eventDate = "20260827",
        vendorTransactionId = "tx-91c",
        eventType = eventType,
        topic = topic,
        payload =
            """{"eventId":"$eventId","txId":"tx-91c","accountId":"acct-01","status":"$status"}""",
        maxRetryCount = 5,
    )

    private companion object {
        const val EVENT_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891"
        const val CONFIRMED_EVENT_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7892"
        const val FAILED_EVENT_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7893"
        const val FINALIZED_EVENT_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7894"
    }
}
