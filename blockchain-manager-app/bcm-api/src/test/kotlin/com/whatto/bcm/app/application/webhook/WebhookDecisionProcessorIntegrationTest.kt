package com.whatto.bcm.app.application.webhook

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.webhook.PoisonWebhookAlertPort
import com.whatto.bcm.domain.webhook.UnattributedDepositAlertPort
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlertPort
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookNotification
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    classes = [BcmApiApplication::class],
    properties = [
        "bcm.webhook-worker.enabled=false",
        "bcm.webhook-worker.max-attempts=2",
        "bcm.webhook-worker.outbox-max-attempts=5",
    ],
)
class WebhookDecisionProcessorIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var processor: WebhookDecisionProcessor

    @Autowired
    lateinit var inbox: WebhookInboxRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var unattributedAlert: UnattributedDepositAlertPort

    @MockkBean
    lateinit var poisonAlert: PoisonWebhookAlertPort

    @MockkBean
    lateinit var unregisteredVaultTransferAlert: UnregisteredVaultTransferAlertPort

    @MockkBean
    lateinit var eventIdGenerator: EventIdGenerator

    private val eventSequence = AtomicInteger()

    @BeforeEach
    fun setUp() {
        every { unattributedAlert.alert(any()) } just Runs
        every { poisonAlert.alert(any(), any()) } just Runs
        every { unregisteredVaultTransferAlert.alert(any()) } just Runs
        eventSequence.set(0)
        every { eventIdGenerator.nextId() } answers {
            "0198c0de-0000-7000-8000-${eventSequence.incrementAndGet().toString().padStart(12, '0')}"
        }
        clearTables()
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('ethereum-id', 'ETHEREUM', 1, 'Ethereum', 'N', 'N', '20260807120000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_vndr_ast_m
              (ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('ETHEREUM', 'USDC', 'KBKRW_ETH_TEST5_6KCC', '0xToken', '20260807120000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    @AfterEach
    fun tearDown() {
        clearTables()
    }

    @Test
    fun `실물 CONFIRMING 입금은 tx와 TXCK outbox를 만들고 인박스를 S로 끝낸다`() {
        insertAddress()
        inbox.insertIfAbsent(notification("noti-confirming", realPayload("noti-confirming")))

        processor.processNext()

        val tx = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)
        val outbox = jdbc.queryForMap("SELECT * FROM bcm_outbox_l")
        val payload = objectMapper.readTree(outbox.getValue("payload").toString())
        assertThat(tx["last_pub_stcd"]).isEqualTo("CONFIRMED")
        assertThat(tx["acnt_id"]).isEqualTo("acct-deposit")
        assertThat(outbox["evt_typ_dvcd"]).isEqualTo("TXCK")
        assertThat(outbox["evnt_stcd"]).isEqualTo("P")
        assertThat(payload.path("amount").asString()).isEqualTo("100")
        assertThat(payload.path("from").asString()).isEqualTo("0xC05A705eFE3f89b3a7a6Ceb6D79107529Ce20f7C")
        assertThat(inboxRow("noti-confirming")["prcs_stcd"]).isEqualTo("S")
    }

    @Test
    fun `기록 없이 FINALIZED가 먼저 오면 CONFIRMED 감지와 FINALIZED를 UUID 순서로 함께 적재한다`() {
        insertAddress()
        val completed = mutatedPayload("noti-finalized", status = "COMPLETED", confirmations = 1)
        inbox.insertIfAbsent(notification("noti-finalized", completed))

        processor.processNext()

        val rows = jdbc.queryForList("SELECT * FROM bcm_outbox_l ORDER BY evnt_id")
        val statuses = rows.map { objectMapper.readTree(it.getValue("payload").toString()).path("status").asString() }
        assertThat(statuses).containsExactly("CONFIRMED", "FINALIZED")
        assertThat(rows.map { it["evt_typ_dvcd"] }).containsExactly("TXCK", "TXCF")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)["last_pub_stcd"])
            .isEqualTo("FINALIZED")
        assertThat(inboxRow("noti-finalized")["prcs_stcd"]).isEqualTo("S")
    }

    @Test
    fun `매핑되지 않은 목적지 입금은 큐 예약 없이 알림 포트로 보내고 S 처리한다`() {
        inbox.insertIfAbsent(notification("noti-unattributed", realPayload("noti-unattributed")))

        processor.processNext()

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(inboxRow("noti-unattributed")["prcs_stcd"]).isEqualTo("S")
        verify(exactly = 1) {
            unattributedAlert.alert(
                match {
                    it.notificationId == "noti-unattributed" &&
                        it.vendorTransactionId == VENDOR_TX_ID &&
                        it.network == "ETHEREUM" &&
                        it.symbol == "USDC"
                },
            )
        }
    }

    @Test
    fun `필수 필드가 없는 poison은 재시도 뒤 F 격리하고 정상 tx와 outbox를 만들지 않는다`() {
        val poison =
            objectMapper
                .readTree(realPayload("noti-poison"))
                .also { (it.path("data") as ObjectNode).remove("assetId") }
                .toString()
        inbox.insertIfAbsent(notification("noti-poison", poison))

        processor.processNext()
        assertThat(inboxRow("noti-poison")["prcs_stcd"]).isEqualTo("P")
        assertThat(inboxRow("noti-poison")["rtry_cnt"]).isEqualTo(1)
        processor.processNext()

        assertThat(inboxRow("noti-poison")["prcs_stcd"]).isEqualTo("F")
        assertThat(inboxRow("noti-poison")["rtry_cnt"]).isEqualTo(2)
        assertThat(inboxRow("noti-poison")["err_msg"]).isEqualTo("missing data.assetId")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        verify(exactly = 1) { poisonAlert.alert("noti-poison", 2) }
    }

    @Test
    fun `예상 밖의 outbox 적재 실패도 tx를 롤백하고 별도 트랜잭션에서 재시도 후 격리한다`() {
        insertAddress()
        every { eventIdGenerator.nextId() } returns "x".repeat(37)
        inbox.insertIfAbsent(notification("noti-rollback", realPayload("noti-rollback")))

        assertThat(processor.processNext()).isEqualTo(WebhookDecisionOutcome.Retrying("noti-rollback", 1))

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(inboxRow("noti-rollback")["prcs_stcd"]).isEqualTo("P")
        assertThat(inboxRow("noti-rollback")["rtry_cnt"]).isEqualTo(1)
        assertThat(inboxRow("noti-rollback")["err_msg"]).isEqualTo("decision processing failed")

        assertThat(processor.processNext()).isEqualTo(WebhookDecisionOutcome.Quarantined("noti-rollback", 2))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(inboxRow("noti-rollback")["prcs_stcd"]).isEqualTo("F")
        verify(exactly = 1) { poisonAlert.alert("noti-rollback", 2) }
    }

    @Test
    fun `같은 신규 tx 알림 두 건의 동시 insert 경합은 이긴 tx를 재조회해 outbox 하나만 만든다`() {
        insertAddress()
        inbox.insertIfAbsent(notification("noti-race-1", realPayload("noti-race-1")))
        inbox.insertIfAbsent(notification("noti-race-2", realPayload("noti-race-2")))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures =
                List(2) {
                    executor.submit {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        processor.processNext()
                    }
                }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForList("SELECT prcs_stcd FROM bcm_whk_l ORDER BY noti_id", String::class.java))
            .containsExactly("S", "S")
    }

    @Test
    fun `WITHDRAWAL vault 발신은 제출 원장으로 분류하고 선도착 웹훅이 vendor txId를 채운다`() {
        insertSubmission("wd-1", "WITHDRAWAL")
        inbox.insertIfAbsent(notification("noti-withdrawal", managedVaultPayload("noti-withdrawal", "wd-1")))

        processor.processNext()

        val submission = jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = 'wd-1'")
        val tx = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)
        val outbox = jdbc.queryForMap("SELECT * FROM bcm_outbox_l")
        val payload = objectMapper.readTree(outbox.getValue("payload").toString())
        assertThat(submission["sbmt_stcd"]).isEqualTo("SUBMITTED")
        assertThat(submission["vndr_tx_id"]).isEqualTo(VENDOR_TX_ID)
        assertThat(tx["acnt_id"]).isEqualTo("acct-pool")
        assertThat(tx["ext_tx_id"]).isEqualTo("wd-1")
        assertThat(outbox["topic"]).isEqualTo("withdrawal-events")
        assertThat(payload.path("type").asString()).isEqualTo("WITHDRAWAL")
        assertThat(payload.path("externalTxId").asString()).isEqualTo("wd-1")
        assertThat(inboxRow("noti-withdrawal")["prcs_stcd"]).isEqualTo("S")
    }

    @Test
    fun `FAILED 제출에 서명 검증된 웹훅이 오면 SUBMITTED로 회수한다`() {
        insertSubmission("wd-recovered", "WITHDRAWAL", status = "FAILED")
        inbox.insertIfAbsent(notification("noti-recovered", managedVaultPayload("noti-recovered", "wd-recovered")))

        processor.processNext()

        val submission = jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = 'wd-recovered'")
        assertThat(submission["sbmt_stcd"]).isEqualTo("SUBMITTED")
        assertThat(submission["vndr_tx_id"]).isEqualTo(VENDOR_TX_ID)
        assertThat(inboxRow("noti-recovered")["prcs_stcd"]).isEqualTo("S")
    }

    @Test
    fun `INTERNAL vault 발신은 internal-events로 분류한다`() {
        insertSubmission("internal-1", "INTERNAL")
        inbox.insertIfAbsent(notification("noti-internal", managedVaultPayload("noti-internal", "internal-1")))

        processor.processNext()

        val outbox = jdbc.queryForMap("SELECT * FROM bcm_outbox_l")
        val payload = objectMapper.readTree(outbox.getValue("payload").toString())
        assertThat(outbox["topic"]).isEqualTo("internal-events")
        assertThat(payload.path("type").asString()).isEqualTo("INTERNAL")
        assertThat(payload.path("accountId").asString()).isEqualTo("acct-pool")
    }

    @Test
    fun `SWEEP vault 발신은 운영 tx 상태만 남기고 고객 outbox를 만들지 않는다`() {
        insertSubmission("swp-1", "SWEEP")
        inbox.insertIfAbsent(notification("noti-sweep", managedVaultPayload("noti-sweep", "swp-1")))

        processor.processNext()

        val submission = jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = 'swp-1'")
        val tx = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)
        assertThat(submission["vndr_tx_id"]).isEqualTo(VENDOR_TX_ID)
        assertThat(tx["acnt_id"]).isEqualTo("acct-pool")
        assertThat(tx["ext_tx_id"]).isEqualTo("swp-1")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(inboxRow("noti-sweep")["prcs_stcd"]).isEqualTo("S")
    }

    @Test
    fun `원장의 vendor txId와 다른 vault 웹훅은 재시도 없이 즉시 격리한다`() {
        insertSubmission("wd-mismatch", "WITHDRAWAL", status = "SUBMITTED", vendorTransactionId = "tx-other")
        inbox.insertIfAbsent(notification("noti-mismatch", managedVaultPayload("noti-mismatch", "wd-mismatch")))

        assertThat(processor.processNext()).isEqualTo(WebhookDecisionOutcome.Quarantined("noti-mismatch", 1))

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(inboxRow("noti-mismatch")["prcs_stcd"]).isEqualTo("F")
        assertThat(inboxRow("noti-mismatch")["err_msg"])
            .isEqualTo("vendor transaction id conflict: recorded=tx-other observed=$VENDOR_TX_ID")
        verify(exactly = 1) { poisonAlert.alert("noti-mismatch", 1) }
    }

    @Test
    fun `vault 발신은 destinationAddress가 아직 없어도 제출 원장으로 처리한다`() {
        insertSubmission("wd-no-address", "WITHDRAWAL")
        val payload =
            objectMapper
                .readTree(managedVaultPayload("noti-no-address", "wd-no-address"))
                .also { (it.path("data") as ObjectNode).remove("destinationAddress") }
                .toString()
        inbox.insertIfAbsent(notification("noti-no-address", payload))

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Processed("noti-no-address", 1))
        assertThat(inboxRow("noti-no-address")["prcs_stcd"]).isEqualTo("S")
    }

    @Test
    fun `제출 원장에 없는 vault 발신은 발행 없이 별도 알림으로 보낸다`() {
        inbox.insertIfAbsent(notification("noti-unregistered", managedVaultPayload("noti-unregistered", "manual-1")))

        processor.processNext()

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(inboxRow("noti-unregistered")["prcs_stcd"]).isEqualTo("S")
        verify(exactly = 1) {
            unregisteredVaultTransferAlert.alert(
                match {
                    it.notificationId == "noti-unregistered" &&
                        it.vendorTransactionId == VENDOR_TX_ID &&
                        it.externalTransactionId == "manual-1"
                },
            )
        }
    }

    private fun insertAddress() {
        jdbc.update(
            """
            INSERT INTO bcm_addr_m
              (acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('acct-deposit', 'ETHEREUM', 'USDC', ?, '20260807120000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            DESTINATION_ADDRESS,
        )
    }

    private fun insertSubmission(
        externalTransactionId: String,
        transactionType: String,
        status: String = "REQUESTED",
        vendorTransactionId: String? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_sbmt_l
              (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, tx_dvcd, vndr_tx_id,
               snd_acnt_id, rcv_dvcd, rcv_vl, ntwk_cd, tkn_smbl, trsf_amt,
               req_dttm, rsp_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 'v1', ?, ?, ?,
                    'acct-pool', 'ADDRESS', '0x9fE2', 'ETHEREUM', 'USDC', 100,
                    '20260807115900', ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            externalTransactionId,
            "a".repeat(64),
            status,
            transactionType,
            vendorTransactionId,
            vendorTransactionId?.let { "20260807115901" },
        )
    }

    private fun notification(
        id: String,
        payload: String,
    ) = WebhookNotification(
        notificationId = id,
        eventType = "transaction.created",
        vendorTransactionId = VENDOR_TX_ID,
        payload = payload,
        payloadHash = "a".repeat(64),
        signature = "verified-signature",
        receivedAt = "20260807120000",
    )

    private fun realPayload(notificationId: String): String =
        objectMapper
            .readTree(
                checkNotNull(javaClass.getResourceAsStream("/payload/transaction.created.json")),
            ).also { (it as ObjectNode).put("id", notificationId) }
            .toString()

    private fun managedVaultPayload(
        notificationId: String,
        externalTransactionId: String,
    ): String =
        objectMapper
            .readTree(realPayload(notificationId))
            .also {
                (it.path("data") as ObjectNode).apply {
                    (path("source") as ObjectNode).apply {
                        put("type", "VAULT_ACCOUNT")
                        put("id", "71")
                    }
                    put("externalTxId", externalTransactionId)
                }
            }.toString()

    private fun mutatedPayload(
        notificationId: String,
        status: String,
        confirmations: Int,
    ): String =
        objectMapper
            .readTree(realPayload(notificationId))
            .also {
                (it.path("data") as ObjectNode).apply {
                    put("status", status)
                    put("subStatus", "CONFIRMED")
                    put("numOfConfirmations", confirmations)
                }
            }.toString()

    private fun inboxRow(id: String): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM bcm_whk_l WHERE noti_id = ?", id)

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_tx_l")
        jdbc.update("DELETE FROM bcm_sbmt_l")
        jdbc.update("DELETE FROM bcm_whk_l")
        jdbc.update("DELETE FROM bcm_addr_m")
        jdbc.update("DELETE FROM bcm_vndr_ast_m")
        jdbc.update("DELETE FROM bcm_blkc_m")
    }

    private companion object {
        const val VENDOR_TX_ID = "f3339e5d-428e-4add-8018-631b972f3195"
        const val DESTINATION_ADDRESS = "0x628501678d302023ca4555B678581917dF8D7636"
    }
}
