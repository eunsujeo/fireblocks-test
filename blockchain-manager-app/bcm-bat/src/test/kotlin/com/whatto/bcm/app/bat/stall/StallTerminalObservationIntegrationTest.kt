package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionLifecycleStage
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.infra.client.fireblocks.FireblocksStatusTranslator
import com.whatto.bcm.infra.persistence.config.SpringTransactionRunner
import com.whatto.bcm.infra.persistence.event.OutboxJdbcAdapter
import com.whatto.bcm.infra.persistence.tx.TxCrudRepository
import com.whatto.bcm.infra.persistence.tx.TxJdbcAdapter
import org.assertj.core.api.Assertions.assertThat
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
@Import(TxJdbcAdapter::class, OutboxJdbcAdapter::class, SpringTransactionRunner::class)
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
}
