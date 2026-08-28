package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.sweep.SweepRequest
import com.whatto.bcm.domain.sweep.SweepRequestAcceptance
import com.whatto.bcm.domain.sweep.SweepRequestItem
import com.whatto.bcm.domain.sweep.SweepRequestItemStatus
import com.whatto.bcm.domain.sweep.SweepRequestStatus
import com.whatto.bcm.infra.persistence.event.EventCompletionJdbcAdapter
import com.whatto.bcm.infra.persistence.event.OutboxJdbcAdapter
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJdbcTest
@Import(SweepRequestJdbcAdapter::class, OutboxJdbcAdapter::class, EventCompletionJdbcAdapter::class)
class SweepRequestPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var requests: SweepRequestJdbcAdapter

    @Autowired
    lateinit var outbox: OutboxJdbcAdapter

    @Autowired
    lateinit var completions: EventCompletionJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun prepare() {
        insertAccount("account-a")
    }

    @Test
    fun `완료된 DEPOSIT FINALIZED event를 요청 항목과 target에 원자적으로 귀속한다`() {
        publishAndComplete(EVENT_ID)

        val accepted = requests.accept(request())

        assertThat(accepted).isInstanceOf(SweepRequestAcceptance.Created::class.java)
        assertThat(requests.findByExternalRequestId(EXTERNAL_ID)).isEqualTo(request())
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_swp_req_src_l", Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_swp_trgt", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `완료 확인 전 event와 이미 소비한 event를 구분한다`() {
        outbox.insertAll(listOf(event(EVENT_ID)))
        outbox.markDispatched(EVENT_ID, "20260827010000")
        outbox.markSuccess(EVENT_ID)

        assertThat(requests.accept(request()))
            .isEqualTo(SweepRequestAcceptance.SourceEventNotCompleted(EVENT_ID))

        publishAndComplete(EVENT_2)
        assertThat(requests.accept(request(eventId = EVENT_2)))
            .isInstanceOf(SweepRequestAcceptance.Created::class.java)
        assertThat(requests.accept(request(externalId = "other-request", requestId = OTHER_REQUEST_ID, eventId = EVENT_2)))
            .isEqualTo(SweepRequestAcceptance.SourceEventConsumed(EVENT_2))
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `서로 다른 요청이 같은 source event를 동시에 소비하면 하나만 접수하고 다른 하나는 충돌로 종결한다`() {
        val transaction = TransactionTemplate(transactionManager)
        transaction.executeWithoutResult {
            insertAccount("account-concurrent")
            publishAndComplete(CONCURRENT_EVENT_ID, accountId = "account-concurrent")
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val commands =
                listOf(
                    concurrentRequest("external-concurrent-a", CONCURRENT_REQUEST_A, CONCURRENT_ITEM_A),
                    concurrentRequest("external-concurrent-b", CONCURRENT_REQUEST_B, CONCURRENT_ITEM_B),
                )
            val futures =
                commands.map { request ->
                    executor.submit<SweepRequestAcceptance> {
                        start.await(5, TimeUnit.SECONDS)
                        checkNotNull(transaction.execute { requests.accept(request) })
                    }
                }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(results.count { it is SweepRequestAcceptance.Created }).isEqualTo(1)
            assertThat(results.count { it == SweepRequestAcceptance.SourceEventConsumed(CONCURRENT_EVENT_ID) }).isEqualTo(1)
        } finally {
            executor.shutdownNow()
            transaction.executeWithoutResult {
                jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = 'account-concurrent'")
                jdbc.update("DELETE FROM bcm_swp_req_src_l WHERE evnt_id = ?", CONCURRENT_EVENT_ID)
                jdbc.update("DELETE FROM bcm_swp_req_item_l WHERE swp_req_id IN (?, ?)", CONCURRENT_REQUEST_A, CONCURRENT_REQUEST_B)
                jdbc.update("DELETE FROM bcm_swp_req_l WHERE swp_req_id IN (?, ?)", CONCURRENT_REQUEST_A, CONCURRENT_REQUEST_B)
                jdbc.update("DELETE FROM bcm_evnt_cmpl_l WHERE evnt_id = ?", CONCURRENT_EVENT_ID)
                jdbc.update("DELETE FROM bcm_outbox_l WHERE evnt_id = ?", CONCURRENT_EVENT_ID)
                jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id = 'account-concurrent'")
                jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id = 'account-a'")
            }
        }
    }

    private fun publishAndComplete(
        eventId: String,
        accountId: String = "account-a",
    ) {
        outbox.insertAll(listOf(event(eventId, accountId)))
        outbox.markDispatched(eventId, "20260827010000")
        outbox.markSuccess(eventId)
        completions.complete(eventId, "DAW_CORE", "20260827010100")
    }

    private fun event(
        eventId: String,
        accountId: String = "account-a",
    ) = OutboxEvent(
        eventId = eventId,
        eventDate = "20260827",
        vendorTransactionId = "tx-$eventId",
        eventType = OutboxEventType.CONFIRMED,
        topic = "deposit-events",
        payload =
            """{"eventId":"$eventId","txId":"tx-$eventId","accountId":"$accountId","network":"BASE","symbol":"USDC","type":"DEPOSIT","status":"FINALIZED"}""",
        maxRetryCount = 5,
    )

    private fun request(
        externalId: String = EXTERNAL_ID,
        requestId: String = REQUEST_ID,
        eventId: String = EVENT_ID,
    ) = SweepRequest(
        sweepRequestId = requestId,
        externalSweepRequestId = externalId,
        requestHash = "a".repeat(64),
        network = "BASE",
        symbol = "USDC",
        status = SweepRequestStatus.ACCEPTED,
        requestedAt = "20260827010203",
        items =
            listOf(
                SweepRequestItem(
                    sweepItemId = if (requestId == REQUEST_ID) ITEM_ID else OTHER_ITEM_ID,
                    sequence = 1,
                    accountId = "account-a",
                    status = SweepRequestItemStatus.PENDING,
                    sourceEventIds = listOf(eventId),
                ),
            ),
    )

    private fun insertAccount(accountId: String) {
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'CU', ?, ?, '20260827000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (acnt_id) DO NOTHING
            """.trimIndent(),
            accountId,
            accountId,
            "vault-$accountId",
        )
    }

    private fun concurrentRequest(
        externalId: String,
        requestId: String,
        itemId: String,
    ) = SweepRequest(
        sweepRequestId = requestId,
        externalSweepRequestId = externalId,
        requestHash = "c".repeat(64),
        network = "BASE",
        symbol = "USDC",
        status = SweepRequestStatus.ACCEPTED,
        requestedAt = "20260827020203",
        items =
            listOf(
                SweepRequestItem(
                    sweepItemId = itemId,
                    sequence = 1,
                    accountId = "account-concurrent",
                    status = SweepRequestItemStatus.PENDING,
                    sourceEventIds = listOf(CONCURRENT_EVENT_ID),
                ),
            ),
    )

    private companion object {
        const val EXTERNAL_ID = "daw-sweep-1"
        const val REQUEST_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7801"
        const val OTHER_REQUEST_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7802"
        const val ITEM_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7811"
        const val OTHER_ITEM_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7812"
        const val EVENT_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891"
        const val EVENT_2 = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7892"
        const val CONCURRENT_EVENT_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7893"
        const val CONCURRENT_REQUEST_A = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7803"
        const val CONCURRENT_REQUEST_B = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7804"
        const val CONCURRENT_ITEM_A = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7813"
        const val CONCURRENT_ITEM_B = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7814"
    }
}
