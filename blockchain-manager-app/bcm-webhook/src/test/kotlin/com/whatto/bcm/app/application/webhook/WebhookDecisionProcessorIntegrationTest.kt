package com.whatto.bcm.app.application.webhook

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.webhook.BcmWebhookApplication
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.webhook.PoisonWebhookAlertPort
import com.whatto.bcm.domain.webhook.UnattributedDepositAlertPort
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlertPort
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    classes = [BcmWebhookApplication::class],
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
        val expectedHash =
            objectMapper
                .readTree(realPayload("hash-reference"))
                .path("data")
                .path("txHash")
                .asString()
        assertThat(tx["last_pub_stcd"]).isEqualTo("CONFIRMED")
        assertThat(tx["acnt_id"]).isEqualTo("acct-deposit")
        assertThat(tx["actv_tx_id"]).isEqualTo(VENDOR_TX_ID)
        assertThat(tx["tx_hash"]).isEqualTo(expectedHash)
        assertThat(outbox["evt_typ_dvcd"]).isEqualTo("TXCK")
        assertThat(outbox["evnt_stcd"]).isEqualTo("P")
        assertThat(payload.path("amount").asString()).isEqualTo("100")
        assertThat(payload.path("txHash").asString()).isEqualTo(expectedHash)
        assertThat(payload.path("from").asString()).isEqualTo("0xC05A705eFE3f89b3a7a6Ceb6D79107529Ce20f7C")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_swp_trgt", Long::class.java)).isZero()
        assertThat(inboxRow("noti-confirming")["prcs_stcd"]).isEqualTo("S")
        assertThat(inboxRow("noti-confirming")["vndr_cmpl_yn"]).isEqualTo("N")
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_swp_trgt", Long::class.java)).isZero()
        assertThat(inboxRow("noti-finalized")["prcs_stcd"]).isEqualTo("S")
        assertThat(inboxRow("noti-finalized")["vndr_cmpl_yn"]).isEqualTo("Y")
    }

    @Test
    fun `DAW 요청 전에는 여러 FINALIZED 입금도 sweep 대상을 만들지 않는다`() {
        insertAddress()
        inbox.insertIfAbsent(notification("noti-finalized-1", finalizedPayload("noti-finalized-1")))
        processor.processNext()
        inbox.insertIfAbsent(
            notification(
                "noti-finalized-duplicate",
                finalizedPayload("noti-finalized-duplicate"),
                receivedAt = "20260807120100",
            ),
        )
        processor.processNext()
        inbox.insertIfAbsent(
            notification(
                "noti-finalized-2",
                finalizedPayload("noti-finalized-2", "vendor-tx-2"),
                "vendor-tx-2",
                "20260807120200",
            ),
        )

        processor.processNext()

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_swp_trgt", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isEqualTo(2)
    }

    @Test
    fun `FINALIZED outbox 적재 실패는 sweep 대상도 tx와 함께 롤백한다`() {
        insertAddress()
        every { eventIdGenerator.nextId() } returns "x".repeat(37)
        inbox.insertIfAbsent(notification("noti-finalized-rollback", finalizedPayload("noti-finalized-rollback")))

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Retrying("noti-finalized-rollback", 1))

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_swp_trgt", Long::class.java)).isZero()
        assertThat(inboxRow("noti-finalized-rollback")["prcs_stcd"]).isEqualTo("P")
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
    fun `진행 중 SWEEP_BATCH vault 발신은 대상을 유지하고 고객 outbox를 만들지 않는다`() {
        insertSweepFixture("swp-1")
        inbox.insertIfAbsent(notification("noti-sweep", managedVaultPayload("noti-sweep", "swp-1")))

        processor.processNext()

        val submission = jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = 'swp-1'")
        val tx = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)
        assertThat(submission["vndr_tx_id"]).isEqualTo(VENDOR_TX_ID)
        assertThat(tx["acnt_id"]).isEqualTo("acct-pool")
        assertThat(tx["ext_tx_id"]).isEqualTo("swp-1")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_trgt")["actv_swp_exec_id"]).isEqualTo(SWEEP_EXECUTION_ID)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(inboxRow("noti-sweep")["prcs_stcd"]).isEqualTo("S")
    }

    @Test
    fun `network records 처리 완료 알림은 대사를 시작하되 항목 성공은 판정하지 않는다`() {
        insertSweepFixture("swp-records")
        inbox.insertIfAbsent(
            notification(
                "noti-sweep-records",
                managedVaultPayload("noti-sweep-records", "swp-records"),
                eventType = "transaction.network_records.processing_completed",
            ),
        )

        processor.processNext()

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_exec_l")["swp_exec_stcd"]).isEqualTo("RECONCILING")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(inboxRow("noti-sweep-records")["prcs_stcd"]).isEqualTo("S")
    }

    @ParameterizedTest(name = "{0} SWEEP_BATCH 종결")
    @ValueSource(strings = ["COMPLETED", "REJECTED", "BLOCKED", "FAILED"])
    fun `SWEEP_BATCH 종결은 항목 대사 전 대상을 바꾸지 않고 고객 outbox를 만들지 않는다`(vendorStatus: String) {
        insertSweepFixture("swp-terminal")
        inbox.insertIfAbsent(
            notification(
                "noti-sweep-terminal",
                managedVaultPayload(
                    notificationId = "noti-sweep-terminal",
                    externalTransactionId = "swp-terminal",
                    status = vendorStatus,
                    confirmations = if (vendorStatus == "COMPLETED") 1 else 0,
                ),
            ),
        )

        processor.processNext()

        val target = jdbc.queryForMap("SELECT * FROM bcm_swp_trgt")
        val execution = jdbc.queryForMap("SELECT * FROM bcm_swp_exec_l")
        assertThat(target["actv_swp_exec_id"]).isEqualTo(SWEEP_EXECUTION_ID)
        assertThat(execution["swp_exec_stcd"]).isEqualTo("RECONCILING")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
        assertThat(inboxRow("noti-sweep-terminal")["prcs_stcd"]).isEqualTo("S")
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

    @Test
    fun `boost 웹훅이 응답보다 먼저 오면 대체 tx를 root에 접고 최초 식별자로만 발행한다`() {
        insertBoostFixture(status = "REQUESTED", activeVendorTransactionId = VENDOR_TX_ID)
        inbox.insertIfAbsent(
            notification(
                "noti-boost-first",
                managedVaultPayload(
                    notificationId = "noti-boost-first",
                    externalTransactionId = BOOST_EXTERNAL_TX_ID,
                    vendorTransactionId = REPLACEMENT_TX_ID,
                    transactionHash = REPLACEMENT_TX_HASH,
                ),
                vendorTransactionId = REPLACEMENT_TX_ID,
            ),
        )

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Processed("noti-boost-first", 0))

        val boost = jdbc.queryForMap("SELECT * FROM bcm_boost_l WHERE ext_tx_id = ?", BOOST_EXTERNAL_TX_ID)
        val root = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)
        assertThat(boost["bst_stcd"]).isEqualTo("SUBMITTED")
        assertThat(boost["new_tx_id"]).isEqualTo(REPLACEMENT_TX_ID)
        assertThat(root["actv_tx_id"]).isEqualTo(REPLACEMENT_TX_ID)
        assertThat(root["tx_hash"]).isEqualTo(REPLACEMENT_TX_HASH)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isOne()
        assertThat(inboxRow("noti-boost-first")["prcs_stcd"]).isEqualTo("S")
        verify(exactly = 0) { unregisteredVaultTransferAlert.alert(any()) }
    }

    @Test
    fun `대체 거래가 먼저 완료되면 root txId와 최초 externalTxId에 승자 hash를 실어 발행한다`() {
        insertBoostFixture(status = "REQUESTED", activeVendorTransactionId = VENDOR_TX_ID)
        inbox.insertIfAbsent(
            notification(
                "noti-boost-finalized",
                managedVaultPayload(
                    notificationId = "noti-boost-finalized",
                    externalTransactionId = BOOST_EXTERNAL_TX_ID,
                    status = "COMPLETED",
                    confirmations = 1,
                    vendorTransactionId = REPLACEMENT_TX_ID,
                    transactionHash = REPLACEMENT_TX_HASH,
                ),
                vendorTransactionId = REPLACEMENT_TX_ID,
            ),
        )

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Processed("noti-boost-finalized", 1))

        val root = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)
        val outbox = jdbc.queryForMap("SELECT * FROM bcm_outbox_l")
        val payload = objectMapper.readTree(outbox.getValue("payload").toString())
        assertThat(root["actv_tx_id"]).isEqualTo(REPLACEMENT_TX_ID)
        assertThat(root["last_pub_stcd"]).isEqualTo("FINALIZED")
        assertThat(payload.path("txId").asString()).isEqualTo(VENDOR_TX_ID)
        assertThat(payload.path("externalTxId").asString()).isEqualTo(ROOT_EXTERNAL_TX_ID)
        assertThat(payload.path("txHash").asString()).isEqualTo(REPLACEMENT_TX_HASH)
        assertThat(outbox["vndr_tx_id"]).isEqualTo(VENDOR_TX_ID)
    }

    @Test
    fun `대체 거래가 active면 비활성 원 거래의 FAILED를 고객 실패로 발행하지 않는다`() {
        insertBoostFixture(status = "SUBMITTED", activeVendorTransactionId = REPLACEMENT_TX_ID)
        inbox.insertIfAbsent(
            notification(
                "noti-old-failed",
                managedVaultPayload(
                    notificationId = "noti-old-failed",
                    externalTransactionId = ROOT_EXTERNAL_TX_ID,
                    status = "FAILED",
                    vendorTransactionId = VENDOR_TX_ID,
                    transactionHash = ORIGINAL_TX_HASH,
                ),
            ),
        )

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Processed("noti-old-failed", 0))

        val root = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)
        assertThat(root["actv_tx_id"]).isEqualTo(REPLACEMENT_TX_ID)
        assertThat(root["last_pub_stcd"]).isEqualTo("CONFIRMED")
        assertThat(root["tx_hash"]).isEqualTo(REPLACEMENT_TX_HASH)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
    }

    @Test
    fun `비활성 원 거래가 먼저 채굴되면 원 거래를 승자로 복귀시키고 root를 완료한다`() {
        insertBoostFixture(status = "SUBMITTED", activeVendorTransactionId = REPLACEMENT_TX_ID)
        inbox.insertIfAbsent(
            notification(
                "noti-old-winner",
                managedVaultPayload(
                    notificationId = "noti-old-winner",
                    externalTransactionId = ROOT_EXTERNAL_TX_ID,
                    status = "COMPLETED",
                    confirmations = 1,
                    vendorTransactionId = VENDOR_TX_ID,
                    transactionHash = ORIGINAL_TX_HASH,
                ),
            ),
        )

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Processed("noti-old-winner", 1))

        val root = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)
        val payload = objectMapper.readTree(jdbc.queryForMap("SELECT * FROM bcm_outbox_l").getValue("payload").toString())
        assertThat(root["actv_tx_id"]).isEqualTo(VENDOR_TX_ID)
        assertThat(root["tx_hash"]).isEqualTo(ORIGINAL_TX_HASH)
        assertThat(root["last_pub_stcd"]).isEqualTo("FINALIZED")
        assertThat(payload.path("txId").asString()).isEqualTo(VENDOR_TX_ID)
        assertThat(payload.path("externalTxId").asString()).isEqualTo(ROOT_EXTERNAL_TX_ID)
        assertThat(payload.path("txHash").asString()).isEqualTo(ORIGINAL_TX_HASH)
    }

    @Test
    fun `원 거래 승자가 FINALIZED 뒤 reorg FAILED면 boost 이력이 있어도 고객 실패를 발행한다`() {
        insertBoostFixture(status = "SUBMITTED", activeVendorTransactionId = REPLACEMENT_TX_ID)
        jdbc.update(
            """
            UPDATE bcm_tx_l
            SET actv_tx_id = ?, tx_hash = ?, last_pub_stcd = 'FINALIZED', cnfm_cnt = 1
            WHERE vndr_tx_id = ?
            """.trimIndent(),
            VENDOR_TX_ID,
            ORIGINAL_TX_HASH,
            VENDOR_TX_ID,
        )
        inbox.insertIfAbsent(
            notification(
                "noti-winner-reorg",
                managedVaultPayload(
                    notificationId = "noti-winner-reorg",
                    externalTransactionId = ROOT_EXTERNAL_TX_ID,
                    status = "FAILED",
                    vendorTransactionId = VENDOR_TX_ID,
                    transactionHash = ORIGINAL_TX_HASH,
                ),
            ),
        )

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Processed("noti-winner-reorg", 1))

        assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)["last_pub_stcd"])
            .isEqualTo("FAILED")
        val payload = objectMapper.readTree(jdbc.queryForMap("SELECT * FROM bcm_outbox_l").getValue("payload").toString())
        assertThat(payload.path("status").asString()).isEqualTo("FAILED")
        assertThat(payload.path("txId").asString()).isEqualTo(VENDOR_TX_ID)
    }

    @Test
    fun `boost 응답을 회수 중이면 원 거래 FAILED를 보류하고 종결 재관찰에 맡긴다`() {
        insertBoostFixture(status = "REQUESTED", activeVendorTransactionId = VENDOR_TX_ID)
        jdbc.update(
            "UPDATE bcm_tx_l SET stall_alrt_dttm = '20260807115945' WHERE vndr_tx_id = ?",
            VENDOR_TX_ID,
        )
        inbox.insertIfAbsent(
            notification(
                "noti-failed-during-boost",
                managedVaultPayload(
                    notificationId = "noti-failed-during-boost",
                    externalTransactionId = ROOT_EXTERNAL_TX_ID,
                    status = "FAILED",
                    vendorTransactionId = VENDOR_TX_ID,
                    transactionHash = ORIGINAL_TX_HASH,
                ),
            ),
        )

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Processed("noti-failed-during-boost", 0))

        assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID))
            .containsEntry("last_pub_stcd", "CONFIRMED")
            .containsEntry("last_chng_dttm", "20260807120000")
            .containsEntry("stall_alrt_dttm", null)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
    }

    @Test
    fun `대체 거래 FAILED가 먼저 와도 보류하고 뒤늦은 원 거래 성공을 승자로 채택한다`() {
        insertBoostFixture(status = "SUBMITTED", activeVendorTransactionId = REPLACEMENT_TX_ID)
        inbox.insertIfAbsent(
            notification(
                "noti-replacement-failed",
                managedVaultPayload(
                    notificationId = "noti-replacement-failed",
                    externalTransactionId = BOOST_EXTERNAL_TX_ID,
                    status = "FAILED",
                    vendorTransactionId = REPLACEMENT_TX_ID,
                    transactionHash = REPLACEMENT_TX_HASH,
                ),
                vendorTransactionId = REPLACEMENT_TX_ID,
            ),
        )

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Processed("noti-replacement-failed", 0))
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID)["last_pub_stcd"])
            .isEqualTo("CONFIRMED")

        inbox.insertIfAbsent(
            notification(
                "noti-original-late-winner",
                managedVaultPayload(
                    notificationId = "noti-original-late-winner",
                    externalTransactionId = ROOT_EXTERNAL_TX_ID,
                    status = "COMPLETED",
                    confirmations = 1,
                    vendorTransactionId = VENDOR_TX_ID,
                    transactionHash = ORIGINAL_TX_HASH,
                ),
                receivedAt = "20260807120100",
            ),
        )

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Processed("noti-original-late-winner", 1))
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", VENDOR_TX_ID))
            .containsEntry("actv_tx_id", VENDOR_TX_ID)
            .containsEntry("last_pub_stcd", "FINALIZED")
    }

    @Test
    fun `이미 다른 대체 tx로 연결된 boost 웹훅은 즉시 격리한다`() {
        insertBoostFixture(status = "SUBMITTED", activeVendorTransactionId = REPLACEMENT_TX_ID)
        inbox.insertIfAbsent(
            notification(
                "noti-boost-conflict",
                managedVaultPayload(
                    notificationId = "noti-boost-conflict",
                    externalTransactionId = BOOST_EXTERNAL_TX_ID,
                    vendorTransactionId = "tx-unexpected",
                    transactionHash = "0xunexpected",
                ),
                vendorTransactionId = "tx-unexpected",
            ),
        )

        assertThat(processor.processNext())
            .isEqualTo(WebhookDecisionOutcome.Quarantined("noti-boost-conflict", 1))

        assertThat(jdbc.queryForMap("SELECT * FROM bcm_boost_l WHERE ext_tx_id = ?", BOOST_EXTERNAL_TX_ID)["new_tx_id"])
            .isEqualTo(REPLACEMENT_TX_ID)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
    }

    @Test
    fun `같은 주소에 USDT와 USDC가 발급되어도 USDC 입금은 USDC로 기록한다`() {
        insertAddress()
        jdbc.update(
            """
            INSERT INTO bcm_vndr_ast_m
              (ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT ntwk_cd, 'USDT', 'review-usdt', '0xOtherToken', reg_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd
            FROM bcm_vndr_ast_m WHERE tkn_smbl = 'USDC'
            """.trimIndent(),
        )
        jdbc.update("UPDATE bcm_addr_m SET tkn_smbl = 'USDT'")
        insertAddress()
        inbox.insertIfAbsent(notification("noti-multi-asset", realPayload("noti-multi-asset")))

        processor.processNext()

        val tx = jdbc.queryForMap("SELECT * FROM bcm_tx_l")
        val payload = objectMapper.readTree(jdbc.queryForMap("SELECT * FROM bcm_outbox_l").getValue("payload").toString())
        assertThat(tx["tkn_smbl"]).isEqualTo("USDC")
        assertThat(payload.path("symbol").asString()).isEqualTo("USDC")
    }

    @Test
    fun `완료된 sweep의 DROPPED_BY_BLOCKCHAIN은 항목 실패 이벤트를 한 번 발행하고 요청을 다시 연다`() {
        insertCompletedSweepFixture()
        val payload = sweepReorgPayload("noti-sweep-reorg")
        inbox.insertIfAbsent(notification("noti-sweep-reorg", payload))

        processor.processNext()

        assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l")["last_pub_stcd"]).isEqualTo("FAILED")
        assertThat(inboxRow("noti-sweep-reorg")["prcs_stcd"]).isEqualTo("S")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_exec_l")["swp_exec_stcd"]).isEqualTo("FAILED")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_req_item_l")["swp_req_item_stcd"]).isEqualTo("PENDING")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_req_l")["swp_req_stcd"]).isEqualTo("ACCEPTED")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_trgt")["actv_swp_exec_id"]).isNull()
        val event = objectMapper.readTree(jdbc.queryForMap("SELECT * FROM bcm_outbox_l").getValue("payload").toString())
        assertThat(event.path("chainStatus").asString()).isEqualTo("FAILED")
        assertThat(event.path("itemOutcome").asString()).isEqualTo("FAILED")
        assertThat(event.path("txId").asString()).isEqualTo(VENDOR_TX_ID)
        assertThat(event.path("sweepItemId").asString()).isEqualTo("webhook-sweep-request-item")
        assertThat(event.path("accountId").asString()).isEqualTo("acct-pool")

        inbox.insertIfAbsent(notification("noti-sweep-reorg-again", sweepReorgPayload("noti-sweep-reorg-again")))
        processor.processNext()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `같은 주소의 다른 토큰만 발급된 입금은 귀속하지 않는다`() {
        insertAddress()
        jdbc.update("UPDATE bcm_addr_m SET tkn_smbl = 'USDT'")
        inbox.insertIfAbsent(notification("noti-unissued-token", realPayload("noti-unissued-token")))

        assertThat(processor.processNext()).isInstanceOf(WebhookDecisionOutcome.Unattributed::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Int::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Int::class.java)).isZero()
    }

    @Test
    fun `sweep 무효화 outbox 실패는 원장 변경을 롤백하고 재시도해도 과거 성공 이벤트를 보존한다`() {
        insertCompletedSweepFixture()
        val oldPayload =
            """
            {"eventId":"0198c0de-0000-7000-8000-000000000099","type":"SWEEP",
             "chainStatus":"FINALIZED","itemOutcome":"SUCCEEDED","accountId":"acct-pool"}
            """.trimIndent()
        jdbc.update(
            """
            INSERT INTO bcm_outbox_l
              (evnt_id, evnt_dt, vndr_tx_id, agg_typ_dvcd, evt_typ_dvcd, topic, payload,
               evnt_stcd, rtry_cnt, max_rtry_cnt, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('0198c0de-0000-7000-8000-000000000099', '20260807', ?, 'TX', 'TXCF', 'sweep-events',
                    CAST(? AS jsonb), 'P', 0, 5, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            VENDOR_TX_ID,
            oldPayload,
        )
        jdbc.execute(
            "ALTER TABLE bcm_outbox_l ADD CONSTRAINT test_reject_sweep_failure CHECK (payload->>'chainStatus' <> 'FAILED')",
        )
        try {
            inbox.insertIfAbsent(notification("noti-reorg-rollback", sweepReorgPayload("noti-reorg-rollback")))

            assertThat(processor.processNext()).isEqualTo(WebhookDecisionOutcome.Retrying("noti-reorg-rollback", 1))
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l")["last_pub_stcd"]).isEqualTo("FINALIZED")
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_exec_l")["swp_exec_stcd"]).isEqualTo("COMPLETED")
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_item_l")["swp_item_stcd"]).isEqualTo("SUCCEEDED")
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_req_item_l")["swp_req_item_stcd"]).isEqualTo("COMPLETED")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_swp_trgt", Int::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Int::class.java)).isEqualTo(1)
        } finally {
            jdbc.execute("ALTER TABLE bcm_outbox_l DROP CONSTRAINT test_reject_sweep_failure")
        }

        processor.processNext()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Int::class.java)).isEqualTo(2)
        val preserved =
            jdbc.queryForObject(
                "SELECT payload::text FROM bcm_outbox_l WHERE evnt_id = '0198c0de-0000-7000-8000-000000000099'",
                String::class.java,
            )
        assertThat(objectMapper.readTree(preserved)).isEqualTo(objectMapper.readTree(oldPayload))
    }

    @Test
    fun `완료한 sweep의 무효화 알림이 동시에 처리되어도 항목 실패 이벤트는 하나다`() {
        insertCompletedSweepFixture()
        listOf("noti-reorg-race-1", "noti-reorg-race-2").forEach {
            inbox.insertIfAbsent(notification(it, sweepReorgPayload(it)))
        }
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Int::class.java)).isEqualTo(1)
        assertThat(inboxRow("noti-reorg-race-1")["prcs_stcd"]).isEqualTo("S")
        assertThat(inboxRow("noti-reorg-race-2")["prcs_stcd"]).isEqualTo("S")
    }

    @Test
    fun `이전 sweep이 무효화되어도 같은 요청의 후속 실행과 claim을 유지한다`() {
        insertCompletedSweepFixture()
        jdbc.update(
            """
            INSERT INTO bcm_swp_exec_l
              (swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id, swp_ctrt_addr,
               plcy_vrsn_id, plcy_snps_hash, ctrt_vrsn_id, ctrt_evdc_id, swp_exec_stcd, item_cnt,
               req_tot_amt, gasless_yn, vndr_tx_id, req_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT 'followup-execution', 'swp-followup', req_hash, ntwk_cd, tkn_smbl, opr_acnt_id, swp_ctrt_addr,
                   plcy_vrsn_id, plcy_snps_hash, ctrt_vrsn_id, ctrt_evdc_id, 'SUBMITTED', item_cnt,
                   req_tot_amt, gasless_yn, 'followup-vendor-tx', '20260807120200',
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd
            FROM bcm_swp_exec_l WHERE swp_exec_id = ?
            """.trimIndent(),
            SWEEP_EXECUTION_ID,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_item_l
              (swp_exec_id, item_seq, swp_req_item_id, acnt_id, src_addr, req_amt, swp_item_stcd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT 'followup-execution', item_seq, swp_req_item_id, acnt_id, src_addr, req_amt, 'READY',
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd
            FROM bcm_swp_item_l WHERE swp_exec_id = ?
            """.trimIndent(),
            SWEEP_EXECUTION_ID,
        )
        jdbc.update("UPDATE bcm_swp_req_item_l SET swp_req_item_stcd = 'PROCESSING'")
        jdbc.update("UPDATE bcm_swp_req_l SET swp_req_stcd = 'PROCESSING', fnsh_dttm = NULL")
        jdbc.update(
            """
            INSERT INTO bcm_swp_trgt
              (acnt_id, ntwk_cd, tkn_smbl, reg_dttm, actv_swp_exec_id, actv_item_seq, try_cnt,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('acct-pool', 'ETHEREUM', 'USDC', '20260807120200', 'followup-execution', 1, 2,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        inbox.insertIfAbsent(notification("noti-reorg-followup", sweepReorgPayload("noti-reorg-followup")))

        processor.processNext()

        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_trgt")["actv_swp_exec_id"]).isEqualTo("followup-execution")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_req_item_l")["swp_req_item_stcd"]).isEqualTo("PROCESSING")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_req_l")["swp_req_stcd"]).isEqualTo("PROCESSING")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_item_l WHERE swp_exec_id = 'followup-execution'")["swp_item_stcd"])
            .isEqualTo("READY")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Int::class.java)).isEqualTo(1)
    }

    private fun insertCompletedSweepFixture() {
        insertSweepFixture("swp-reorg")
        inbox.insertIfAbsent(
            notification("noti-sweep-finalized", managedVaultPayload("noti-sweep-finalized", "swp-reorg", "COMPLETED", 1)),
        )
        processor.processNext()
        jdbc.update("UPDATE bcm_swp_exec_l SET swp_exec_stcd = 'COMPLETED', actl_tot_amt = 100, fnsh_dttm = '20260807120100'")
        jdbc.update("UPDATE bcm_swp_item_l SET swp_item_stcd = 'SUCCEEDED', actl_amt = 100, log_idx = 1")
        jdbc.update("UPDATE bcm_swp_req_item_l SET swp_req_item_stcd = 'COMPLETED'")
        jdbc.update("UPDATE bcm_swp_req_l SET swp_req_stcd = 'COMPLETED', fnsh_dttm = '20260807120100'")
        jdbc.update("DELETE FROM bcm_swp_trgt")
    }

    private fun sweepReorgPayload(notificationId: String): String =
        objectMapper
            .readTree(managedVaultPayload(notificationId, "swp-reorg", "FAILED", 0))
            .also {
                (it.path("data") as ObjectNode).put("subStatus", "DROPPED_BY_BLOCKCHAIN")
            }.toString()

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
        sweepExecutionId: String? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_sbmt_l
              (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, tx_dvcd, vndr_tx_id, swp_exec_id,
               snd_acnt_id, rcv_dvcd, rcv_vl, ntwk_cd, tkn_smbl, trsf_amt,
               call_data, req_dttm, rsp_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, ?, ?, ?, ?,
                    'acct-pool', 'ADDRESS', '0x9fE2', 'ETHEREUM', 'USDC', 100,
                    ?, '20260807115900', ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            externalTransactionId,
            "a".repeat(64),
            if (transactionType.startsWith("SWEEP_")) "cc-v1" else "v1",
            status,
            transactionType,
            vendorTransactionId,
            sweepExecutionId,
            if (transactionType.startsWith("SWEEP_")) "0x1234" else null,
            vendorTransactionId?.let { "20260807115901" },
        )
    }

    private fun insertSweepFixture(externalTransactionId: String) {
        val snapshot = insertActiveSweepSnapshot(jdbc)
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('acct-pool', 'CU', 'sweep-fixture', 'vault-sweep-fixture', '20260807115900',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (acnt_id) DO NOTHING
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_l
              (swp_req_id, ext_swp_req_id, req_hash, ntwk_cd, tkn_smbl, swp_req_stcd,
               item_cnt, req_dttm, fnsh_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('webhook-sweep-request', 'webhook-sweep-request', ?, 'ETHEREUM', 'USDC', 'PROCESSING',
                    1, '20260807115900', NULL, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            "b".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_item_l
              (swp_req_item_id, swp_req_id, item_seq, acnt_id, swp_req_item_stcd, last_fail_cd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('webhook-sweep-request-item', 'webhook-sweep-request', 1, 'acct-pool', 'PROCESSING', NULL,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_exec_l
              (swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id, swp_ctrt_addr,
               plcy_vrsn_id, plcy_snps_hash, ctrt_vrsn_id, ctrt_evdc_id,
               swp_exec_stcd, item_cnt, req_tot_amt, actl_tot_amt, gasless_yn, vndr_tx_id, tx_hash,
               req_dttm, fnsh_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, 'ETHEREUM', 'USDC', 'acct-pool', '0xSweeper',
                    ?, ?, ?, ?,
                    'SUBMITTED', 1, 100, NULL, 'Y', ?, NULL,
                    '20260807115900', NULL, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            SWEEP_EXECUTION_ID,
            externalTransactionId,
            "a".repeat(64),
            snapshot.policyVersionId,
            snapshot.policySnapshotHash,
            snapshot.contractVersionId,
            snapshot.contractEvidenceId,
            VENDOR_TX_ID,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_item_l
              (swp_exec_id, item_seq, swp_req_item_id, acnt_id, src_addr, req_amt, actl_amt,
               swp_item_stcd, fail_cd, log_idx,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 1, 'webhook-sweep-request-item', 'acct-pool', '0xSource', 100, NULL, 'READY', NULL, NULL,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            SWEEP_EXECUTION_ID,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_trgt
              (acnt_id, ntwk_cd, tkn_smbl, reg_dttm, actv_swp_exec_id, actv_item_seq, try_cnt, last_try_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('acct-pool', 'ETHEREUM', 'USDC', '20260807115900', ?, 1, 1, '20260807115900',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            SWEEP_EXECUTION_ID,
        )
        insertSubmission(
            externalTransactionId,
            "SWEEP_BATCH",
            status = "SUBMITTED",
            vendorTransactionId = VENDOR_TX_ID,
            sweepExecutionId = SWEEP_EXECUTION_ID,
        )
    }

    private fun insertBoostFixture(
        status: String,
        activeVendorTransactionId: String,
    ) {
        insertSubmission(
            ROOT_EXTERNAL_TX_ID,
            "WITHDRAWAL",
            status = "SUBMITTED",
            vendorTransactionId = VENDOR_TX_ID,
        )
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, stall_alrt_dttm,
               vndr_crt_dttm, frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, 'acct-pool', 'ETHEREUM', 'USDC', ?,
                    'CONFIRMED', 0, NULL, NULL, NULL, '20260807115900', '20260807115900', '20260807115900',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            VENDOR_TX_ID,
            activeVendorTransactionId,
            ROOT_EXTERNAL_TX_ID,
            if (activeVendorTransactionId == VENDOR_TX_ID) ORIGINAL_TX_HASH else REPLACEMENT_TX_HASH,
        )
        jdbc.update(
            """
            INSERT INTO bcm_boost_l
              (orig_tx_id, try_seq, ext_tx_id, bst_stcd, claim_id, claim_exp_dttm,
               rplc_tx_id, rplc_tx_hash, fee_lvl, gasless_yn, new_tx_id, req_dttm, rsp_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 1, ?, ?, ?, ?, ?, ?, 'HIGH', 'Y', ?, '20260807115930', ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            VENDOR_TX_ID,
            BOOST_EXTERNAL_TX_ID,
            status,
            if (status == "REQUESTED") "claim-1" else null,
            if (status == "REQUESTED") "20260807120230" else null,
            VENDOR_TX_ID,
            ORIGINAL_TX_HASH,
            if (status == "SUBMITTED") REPLACEMENT_TX_ID else null,
            if (status == "SUBMITTED") "20260807115931" else null,
        )
    }

    private fun notification(
        id: String,
        payload: String,
        vendorTransactionId: String = VENDOR_TX_ID,
        receivedAt: String = "20260807120000",
        eventType: String = "transaction.created",
    ) = WebhookNotification(
        notificationId = id,
        eventType = eventType,
        vendorTransactionId = vendorTransactionId,
        payload = payload,
        payloadHash = "a".repeat(64),
        signature = "verified-signature",
        receivedAt = receivedAt,
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
        status: String = "CONFIRMING",
        confirmations: Int = 0,
        vendorTransactionId: String = VENDOR_TX_ID,
        transactionHash: String? = null,
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
                    put("id", vendorTransactionId)
                    put("status", status)
                    put("numOfConfirmations", confirmations)
                    transactionHash?.let { put("txHash", it) }
                }
            }.toString()

    private fun mutatedPayload(
        notificationId: String,
        status: String,
        confirmations: Int,
        vendorTransactionId: String = VENDOR_TX_ID,
    ): String =
        objectMapper
            .readTree(realPayload(notificationId))
            .also {
                (it.path("data") as ObjectNode).apply {
                    put("id", vendorTransactionId)
                    put("status", status)
                    put("subStatus", "CONFIRMED")
                    put("numOfConfirmations", confirmations)
                }
            }.toString()

    private fun finalizedPayload(
        notificationId: String,
        vendorTransactionId: String = VENDOR_TX_ID,
    ): String =
        mutatedPayload(
            notificationId = notificationId,
            status = "COMPLETED",
            confirmations = 1,
            vendorTransactionId = vendorTransactionId,
        )

    private fun inboxRow(id: String): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM bcm_whk_l WHERE noti_id = ?", id)

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_swp_trgt")
        jdbc.update("DELETE FROM bcm_swp_item_l")
        jdbc.update("DELETE FROM bcm_swp_exec_l")
        jdbc.update("DELETE FROM bcm_swp_req_item_l WHERE swp_req_id = 'webhook-sweep-request'")
        jdbc.update("DELETE FROM bcm_swp_req_l WHERE swp_req_id = 'webhook-sweep-request'")
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_boost_l")
        jdbc.update("DELETE FROM bcm_tx_l")
        jdbc.update("DELETE FROM bcm_sbmt_l")
        jdbc.update("DELETE FROM bcm_whk_l")
        jdbc.update("DELETE FROM bcm_addr_m")
        jdbc.update("DELETE FROM bcm_vndr_ast_m")
        jdbc.update("DELETE FROM bcm_blkc_m")
        jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id = 'acct-pool'")
    }

    private companion object {
        const val VENDOR_TX_ID = "f3339e5d-428e-4add-8018-631b972f3195"
        const val REPLACEMENT_TX_ID = "f4449e5d-428e-4add-8018-631b972f3196"
        const val ROOT_EXTERNAL_TX_ID = "wd-root"
        const val BOOST_EXTERNAL_TX_ID = "bst-0198c0de-0000-7000-8000-000000000001"
        const val ORIGINAL_TX_HASH = "0xoriginal"
        const val REPLACEMENT_TX_HASH = "0xreplacement"
        const val SWEEP_EXECUTION_ID = "01987654-3210-7abc-8def-0123456789ab"
        const val DESTINATION_ADDRESS = "0x628501678d302023ca4555B678581917dF8D7636"
    }
}
