package com.whatto.bcm.infra.persistence.event

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
@Import(OutboxJdbcAdapter::class)
class OutboxPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var outbox: OutboxJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `발행 예약은 P 상태와 JSON payload로 순서대로 적재된다`() {
        val first = event("0198c0de-0000-7000-8000-000000000001", OutboxEventType.CHECKING, "CONFIRMED")
        val second = event("0198c0de-0000-7000-8000-000000000002", OutboxEventType.CONFIRMED, "FINALIZED")

        outbox.insertAll(listOf(first, second))

        val rows = jdbc.queryForList("SELECT * FROM bcm_outbox_l ORDER BY evnt_id")
        assertThat(rows.map { it["evnt_id"] }).containsExactly(first.eventId, second.eventId)
        assertThat(rows.map { it["evt_typ_dvcd"] }).containsExactly("TXCK", "TXCF")
        assertThat(rows).allSatisfy {
            assertThat(it["evnt_stcd"]).isEqualTo("P")
            assertThat(it["rtry_cnt"]).isEqualTo(0)
            assertThat(it["max_rtry_cnt"]).isEqualTo(5)
        }
        assertThat(rows.map { it["payload"].toString() })
            .allSatisfy { assertThat(it).contains("\"eventId\"") }
    }

    @Test
    fun `relay는 가장 오래된 P 이벤트를 잠그고 D에서 S로 전이한다`() {
        val first = event("0198c0de-0000-7000-8000-000000000001", OutboxEventType.CHECKING, "CONFIRMED")
        val second = event("0198c0de-0000-7000-8000-000000000002", OutboxEventType.CONFIRMED, "FINALIZED")
        outbox.insertAll(listOf(second, first))

        val pending = outbox.findNextPendingForUpdate()
        outbox.markDispatched(checkNotNull(pending).eventId, "20260807120100")
        outbox.markSuccess(pending.eventId)

        assertThat(pending.eventId).isEqualTo(first.eventId)
        assertThat(pending.topic).isEqualTo("deposit-events")
        val row = jdbc.queryForMap("SELECT * FROM bcm_outbox_l WHERE evnt_id = ?", first.eventId)
        assertThat(row["evnt_stcd"]).isEqualTo("S")
        assertThat(row["pub_dttm"]).isEqualTo("20260807120100")
        assertThat(row["rtry_cnt"]).isEqualTo(0)
    }

    @Test
    fun `발행 실패는 상한 전까지 P로 되돌리고 상한에 닿으면 F로 격리한다`() {
        val event = event("0198c0de-0000-7000-8000-000000000001", OutboxEventType.CHECKING, "CONFIRMED", maxRetryCount = 2)
        outbox.insertAll(listOf(event))

        val first = outbox.recordFailure(event.eventId, "Kafka publish failed", "20260807120100")
        val second = outbox.recordFailure(event.eventId, "Kafka publish failed", "20260807120200")

        assertThat(first.retryCount).isEqualTo(1)
        assertThat(first.quarantined).isFalse()
        assertThat(second.retryCount).isEqualTo(2)
        assertThat(second.quarantined).isTrue()
        val row = jdbc.queryForMap("SELECT * FROM bcm_outbox_l WHERE evnt_id = ?", event.eventId)
        assertThat(row["evnt_stcd"]).isEqualTo("F")
        assertThat(row["last_rtry_dttm"]).isEqualTo("20260807120200")
        assertThat(row["err_msg"]).isEqualTo("Kafka publish failed")
    }

    private fun event(
        id: String,
        type: OutboxEventType,
        status: String,
        maxRetryCount: Int = 5,
    ) = OutboxEvent(
        eventId = id,
        eventDate = "20260807",
        vendorTransactionId = "tx-91c",
        eventType = type,
        topic = "deposit-events",
        payload = """{"eventId":"$id","accountId":"acct-deposit","status":"$status"}""",
        maxRetryCount = maxRetryCount,
    )
}
