package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.BoostAttemptRepository
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionLifecycleStage
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.infra.client.fireblocks.FireblocksStatusTranslator
import com.whatto.bcm.infra.persistence.boost.BoostJdbcAdapter
import com.whatto.bcm.infra.persistence.config.SpringTransactionRunner
import com.whatto.bcm.infra.persistence.event.OutboxJdbcAdapter
import com.whatto.bcm.infra.persistence.tx.TxCrudRepository
import com.whatto.bcm.infra.persistence.tx.TxJdbcAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@SpringBootTest(classes = [StallTerminalObservationIntegrationTest.TestApplication::class])
@Import(TxJdbcAdapter::class, BoostJdbcAdapter::class, OutboxJdbcAdapter::class, SpringTransactionRunner::class)
class StallTerminalObservationIntegrationTest : IntegrationTestSupport() {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJdbcRepositories(basePackageClasses = [TxCrudRepository::class])
    class TestApplication

    @Autowired
    lateinit var transactions: TxRecordRepository

    @Autowired
    lateinit var outbox: OutboxEventRepository

    @Autowired
    lateinit var boosts: BoostAttemptRepository

    @Autowired
    lateinit var transactionRunner: TransactionRunner

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        clearTables()
    }

    @AfterEach
    fun tearDown() {
        clearTables()
    }

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_boost_l")
        jdbc.update("DELETE FROM bcm_tx_l")
    }

    @Test
    fun `막힘 점검의 종결 최신 관찰은 root 상태와 outbox를 같은 트랜잭션으로 진행한다`() {
        val root = transactions.insert(rootRecord())
        val handler =
            TransactionalStallTerminalObservationHandler(
                transactions = transactions,
                statusTranslator = FireblocksStatusTranslator { 1 },
                transactionRunner = transactionRunner,
                outbox = outbox,
                boosts = boosts,
                vendor = FamilyVendor(emptyMap()),
                eventSerializer = ChainEventSerializer { "{\"txId\":\"${it.txId}\"}" },
                clock = Clock.fixed(Instant.parse("2026-08-07T03:00:00Z"), ZoneId.of("Asia/Seoul")),
                outboxMaxAttempts = 5,
            )

        handler.observe(
            StallCandidate(root, SubmissionTransactionType.WITHDRAWAL),
            completedTransaction(),
            "20260807120000",
        )

        assertThat(transactions.findByVendorTxId("tx-root"))
            .extracting("lastPublishedStatus", "confirmationCount", "transactionHash", "stallAlertedAt")
            .containsExactly(TxStatus.FINALIZED, 1, "0xwinner", null)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_outbox_l"))
            .containsEntry("vndr_tx_id", "tx-root")
            .containsEntry("evt_typ_dvcd", "TXCF")
            .containsEntry("topic", "withdrawal-events")
    }

    @Test
    fun `제출 원장이 없는 입금 종결 재관찰도 deposit outbox를 함께 적재한다`() {
        val root = transactions.insert(rootRecord().copy(externalTxId = null))
        val handler = handler(FamilyVendor(emptyMap()))

        handler.observe(
            StallCandidate(root, null),
            completedTransaction().copy(externalTransactionId = null),
            "20260807120000",
        )

        assertThat(transactions.findByVendorTxId("tx-root")?.lastPublishedStatus).isEqualTo(TxStatus.FINALIZED)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_outbox_l"))
            .containsEntry("vndr_tx_id", "tx-root")
            .containsEntry("evt_typ_dvcd", "TXCF")
            .containsEntry("topic", "deposit-events")
    }

    @Test
    fun `active 대체 거래가 FAILED여도 원 거래 성공을 재조회해 승자로 채택한다`() {
        val root =
            transactions.insert(
                rootRecord().copy(
                    activeVendorTxId = "tx-replacement",
                    transactionHash = "0xreplacement",
                ),
            )
        insertSubmittedBoost()
        val handler =
            TransactionalStallTerminalObservationHandler(
                transactions = transactions,
                statusTranslator = FireblocksStatusTranslator { 1 },
                transactionRunner = transactionRunner,
                outbox = outbox,
                boosts = boosts,
                vendor = FamilyVendor(mapOf("tx-root" to completedTransaction())),
                eventSerializer = ChainEventSerializer { "{\"txId\":\"${it.txId}\"}" },
                clock = Clock.fixed(Instant.parse("2026-08-07T03:00:00Z"), ZoneId.of("Asia/Seoul")),
                outboxMaxAttempts = 5,
            )

        handler.observe(
            StallCandidate(root, SubmissionTransactionType.WITHDRAWAL),
            failedReplacementTransaction(),
            "20260807120000",
        )

        assertThat(transactions.findByVendorTxId("tx-root"))
            .extracting("activeVendorTxId", "lastPublishedStatus", "transactionHash")
            .containsExactly("tx-root", TxStatus.FINALIZED, "0xwinner")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_outbox_l"))
            .containsEntry("vndr_tx_id", "tx-root")
            .containsEntry("evt_typ_dvcd", "TXCF")
    }

    @Test
    fun `RBF 계열 전체가 FAILED일 때만 active 실패를 root에 확정한다`() {
        val root =
            transactions.insert(
                rootRecord().copy(
                    activeVendorTxId = "tx-replacement",
                    transactionHash = "0xreplacement",
                ),
            )
        insertSubmittedBoost()
        val rootFailed = failedReplacementTransaction().copy(transactionId = "tx-root", externalTransactionId = "wd-root")
        val handler = handler(FamilyVendor(mapOf("tx-root" to rootFailed)))

        handler.observe(
            StallCandidate(root, SubmissionTransactionType.WITHDRAWAL),
            failedReplacementTransaction(),
            "20260807120000",
        )

        assertThat(transactions.findByVendorTxId("tx-root")?.lastPublishedStatus).isEqualTo(TxStatus.FAILED)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_outbox_l")["evt_typ_dvcd"]).isEqualTo("TXFL")
    }

    @Test
    fun `REQUESTED boost 응답을 externalTxId로 회수하고 성공 대체 거래를 root에 확정한다`() {
        val root = transactions.insert(rootRecord())
        insertRequestedBoost()
        val replacement = completedTransaction().copy(transactionId = "tx-replacement", externalTransactionId = "bst-1")
        val handler =
            handler(
                FamilyVendor(
                    transactions = mapOf("tx-replacement" to replacement),
                    externalTransactions = mapOf("bst-1" to replacement),
                ),
            )

        handler.observe(
            StallCandidate(root, SubmissionTransactionType.WITHDRAWAL),
            failedReplacementTransaction().copy(transactionId = "tx-root", externalTransactionId = "wd-root"),
            "20260807120000",
        )

        assertThat(transactions.findByVendorTxId("tx-root"))
            .extracting("activeVendorTxId", "lastPublishedStatus", "transactionHash")
            .containsExactly("tx-replacement", TxStatus.FINALIZED, "0xwinner")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_boost_l")["new_tx_id"]).isEqualTo("tx-replacement")
    }

    @Test
    fun `종결 root의 REQUESTED boost가 벤더에도 없으면 무음 보류하지 않는다`() {
        val root = transactions.insert(rootRecord())
        insertRequestedBoost()
        val handler = handler(FamilyVendor(emptyMap()))

        assertThatThrownBy {
            handler.observe(
                StallCandidate(root, SubmissionTransactionType.WITHDRAWAL),
                failedReplacementTransaction().copy(transactionId = "tx-root", externalTransactionId = "wd-root"),
                "20260807120000",
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("bst-1")

        assertThat(transactions.findByVendorTxId("tx-root")?.lastPublishedStatus).isEqualTo(TxStatus.CONFIRMED)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
    }

    private fun handler(vendor: VendorTransactionPort) =
        TransactionalStallTerminalObservationHandler(
            transactions = transactions,
            statusTranslator = FireblocksStatusTranslator { 1 },
            transactionRunner = transactionRunner,
            outbox = outbox,
            boosts = boosts,
            vendor = vendor,
            eventSerializer = ChainEventSerializer { "{\"txId\":\"${it.txId}\"}" },
            clock = Clock.fixed(Instant.parse("2026-08-07T03:00:00Z"), ZoneId.of("Asia/Seoul")),
            outboxMaxAttempts = 5,
        )

    private fun insertSubmittedBoost() {
        jdbc.update(
            """
            INSERT INTO bcm_boost_l
              (orig_tx_id, try_seq, ext_tx_id, bst_stcd, claim_id, claim_exp_dttm,
               rplc_tx_id, rplc_tx_hash, fee_lvl, gasless_yn, new_tx_id, req_dttm, rsp_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('tx-root', 1, 'bst-1', 'SUBMITTED', NULL, NULL,
               'tx-root', '0xwinner', 'HIGH', 'Y', 'tx-replacement', '20260807113000', '20260807113100',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    private fun insertRequestedBoost() {
        jdbc.update(
            """
            INSERT INTO bcm_boost_l
              (orig_tx_id, try_seq, ext_tx_id, bst_stcd, claim_id, claim_exp_dttm,
               rplc_tx_id, rplc_tx_hash, fee_lvl, gasless_yn, new_tx_id, req_dttm, rsp_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('tx-root', 1, 'bst-1', 'REQUESTED', 'claim-1', '20260807123000',
               'tx-root', '0xwinner', 'HIGH', 'Y', NULL, '20260807113000', NULL,
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    private fun rootRecord() =
        TxRecord(
            vendorTxId = "tx-root",
            externalTxId = "wd-root",
            accountId = "account-1",
            network = "ETHEREUM",
            symbol = "USDC",
            transactionHash = "0xwinner",
            lastPublishedStatus = TxStatus.CONFIRMED,
            confirmationCount = 0,
            stallAlertedAt = "20260807113000",
            firstDetectedAt = "20260807110000",
            lastChangedAt = "20260807110000",
        )

    private fun completedTransaction() =
        VendorTransaction(
            transactionId = "tx-root",
            externalTransactionId = "wd-root",
            vendorAssetId = "asset-1",
            rawStatus = "COMPLETED",
            subStatus = "CONFIRMED",
            transactionHash = "0xwinner",
            source = VendorTransactionPeer("VAULT_ACCOUNT", "71"),
            destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
            sourceAddress = "0xfrom",
            destinationAddress = "0xto",
            amount = "1",
            confirmationCount = 1,
            createdAtEpochMillis = 0,
            lastUpdatedEpochMillis = 0,
            lifecycleStage = VendorTransactionLifecycleStage.TERMINAL,
        )

    private fun failedReplacementTransaction() =
        completedTransaction().copy(
            transactionId = "tx-replacement",
            externalTransactionId = "bst-1",
            rawStatus = "FAILED",
            subStatus = "DROPPED_BY_BLOCKCHAIN",
            transactionHash = "0xreplacement",
            confirmationCount = 0,
        )
}

private class FamilyVendor(
    private val transactions: Map<String, VendorTransaction>,
    private val externalTransactions: Map<String, VendorTransaction> = emptyMap(),
) : VendorTransactionPort {
    override fun transaction(transactionId: String): VendorTransaction? = transactions[transactionId]

    override fun transactionByExternalTransactionId(externalTransactionId: String): VendorTransaction? =
        externalTransactions[externalTransactionId]

    override fun submitTransaction(request: VendorTransactionRequest): VendorTransactionSubmission = error("not used")

    override fun transactions(request: VendorTransactionPageRequest): VendorPage<VendorTransaction> = error("not used")
}
