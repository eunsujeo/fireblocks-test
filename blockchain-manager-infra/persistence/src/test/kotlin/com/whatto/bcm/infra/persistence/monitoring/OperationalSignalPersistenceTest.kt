package com.whatto.bcm.infra.persistence.monitoring

import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.monitoring.JobHeartbeat
import com.whatto.bcm.domain.monitoring.OperationalBacklog
import com.whatto.bcm.domain.monitoring.OperationalSignalRepository
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.infra.persistence.event.OutboxJdbcAdapter
import com.whatto.bcm.infra.persistence.job.JobStateJdbcAdapter
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import com.whatto.bcm.infra.persistence.webhook.WebhookInboxJdbcAdapter
import com.whatto.bcm.support.id.UuidV7Generator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DataJdbcTest
@Import(
    OperationalSignalJdbcAdapter::class,
    WebhookInboxJdbcAdapter::class,
    OutboxJdbcAdapter::class,
    JobStateJdbcAdapter::class,
)
class OperationalSignalPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var signals: OperationalSignalRepository

    @Autowired
    lateinit var inbox: WebhookInboxRepository

    @Autowired
    lateinit var outbox: OutboxEventRepository

    @Autowired
    lateinit var jobs: JobStateRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `미처리 인박스와 P outbox 및 작업 heartbeat를 읽기 전용 snapshot으로 조회한다`() {
        inbox.insertIfAbsent(webhook("notification-pending", "20260817010000"))
        inbox.insertIfAbsent(webhook("notification-success", "20260817005900"))
        inbox.markProcessed("notification-success", "20260817010100")

        val pendingEventId = eventIdAt("2026-08-17T01:02:00Z")
        val successEventId = eventIdAt("2026-08-17T01:01:00Z")
        outbox.insertAll(listOf(outbox(successEventId), outbox(pendingEventId)))
        outbox.markDispatched(successEventId, "20260817010300")
        outbox.markSuccess(successEventId)

        jobs.markStarted("tx-reconciliation", "20260817010400")
        jobs.markSucceeded("tx-reconciliation", "20260817010500")

        assertThat(
            signals.pendingWebhookBacklog(),
        ).isEqualTo(OperationalBacklog(1, "20260817010000"))
        assertThat(
            signals.pendingOutboxBacklog(),
        ).isEqualTo(OperationalBacklog(1, "20260817010200"))
        assertThat(signals.stoppedReconciliationCount()).isZero()
        assertThat(signals.unarchivedCompletedWebhookCount()).isZero()
        assertThat(signals.heartbeats()).containsExactly(
            JobHeartbeat(
                jobName = "tx-reconciliation",
                lastRunAt = "20260817010400",
                lastSucceededAt = "20260817010500",
            ),
        )
    }

    @Test
    fun `자동 추적 중단 root와 아직 보관하지 않은 FINALIZED COMPLETED 원문 잔량을 조회한다`() {
        insertTransaction("tx-stopped", "CONFIRMED", "tx-stopped", "20260801000000", "20260817000000")
        jdbc.update("UPDATE bcm_tx_l SET rcnc_stop_dttm = '20260808000000' WHERE vndr_tx_id = 'tx-stopped'")
        insertTransaction("tx-finalized", "FINALIZED", "tx-finalized", "20260816000000", "20260816010000")
        inbox.insertIfAbsent(
            webhook("completed-unarchived", "20260816010000").copy(
                vendorTransactionId = "tx-finalized",
                payload = """{"data":{"status":"COMPLETED"}}""",
            ),
        )
        inbox.markProcessed("completed-unarchived", "20260816010100")

        assertThat(signals.stoppedReconciliationCount()).isEqualTo(1)
        assertThat(signals.unarchivedCompletedWebhookCount()).isEqualTo(1)

        createPartitions("202608", 1)
        jdbc.update(
            """
            INSERT INTO bcm_raw_tx_l
              (base_dt, vndr_tx_id, ext_tx_id, tx_hash, addr, ntwk_cd, tkn_smbl, final_stcd,
               payload, payload_hash, sign_vl, rcv_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('20260817', 'tx-finalized', NULL, NULL, '0xDestination', 'ETHEREUM', 'USDC', 'FINALIZED',
               '{}', ?, 'signature', '20260816010000',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            "a".repeat(64),
        )

        assertThat(signals.unarchivedCompletedWebhookCount()).isZero()
    }

    @Test
    fun `COMPLETED 원문의 다른 필드에 null unicode escape가 있어도 보관 잔량을 조회한다`() {
        insertTransaction("tx-null-escape", "FINALIZED", "tx-null-escape", "20260816000000", "20260816010000")
        inbox.insertIfAbsent(
            webhook("completed-null-escape", "20260816010000").copy(
                vendorTransactionId = "tx-null-escape",
                payload = """{"data":{"status":"COMPLETED","note":"\\u0000"}}""",
            ),
        )
        inbox.markProcessed("completed-null-escape", "20260816010100")

        assertThat(signals.unarchivedCompletedWebhookCount()).isEqualTo(1)
    }

    private fun webhook(
        notificationId: String,
        receivedAt: String,
    ) = WebhookNotification(
        notificationId = notificationId,
        eventType = "transaction.status.updated",
        vendorTransactionId = "tx-$notificationId",
        payload = "{}",
        payloadHash = "a".repeat(64),
        signature = "signature",
        receivedAt = receivedAt,
    )

    private fun outbox(eventId: String) =
        OutboxEvent(
            eventId = eventId,
            eventDate = "20260817",
            vendorTransactionId = "tx-$eventId",
            eventType = OutboxEventType.CHECKING,
            topic = "deposit-events",
            payload = "{}",
            maxRetryCount = 5,
        )

    private fun eventIdAt(instant: String): String = UuidV7Generator(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)).nextId()

    private fun createPartitions(
        startMonth: String,
        monthCount: Int,
    ) {
        val sql =
            ClassPathResource("db/operations/create_bcm_raw_tx_partitions.sql")
                .inputStream
                .bufferedReader()
                .use { it.readText() }
                .replace(":'start_month'", "'$startMonth'")
                .replace(":'month_count'", "'$monthCount'")
        jdbc.execute(sql)
    }

    private fun insertTransaction(
        vendorTransactionId: String,
        status: String,
        activeVendorTransactionId: String,
        firstDetectedAt: String,
        lastChangedAt: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, stall_alrt_dttm,
               vndr_crt_dttm, frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, NULL, 'account-1', 'ETHEREUM', 'USDC', NULL,
                    ?, 0, NULL, NULL, NULL, ?, ?, ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            vendorTransactionId,
            activeVendorTransactionId,
            status,
            firstDetectedAt,
            firstDetectedAt,
            lastChangedAt,
        )
    }
}
