package com.whatto.bcm.app.application.event

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.app.application.submission.TransactionSubmissionCommand
import com.whatto.bcm.app.application.submission.TransactionSubmissionRecipient
import com.whatto.bcm.app.application.submission.TransactionSubmissionService
import com.whatto.bcm.app.application.webhook.WebhookDecisionProcessor
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.infra.client.fireblocks.FireblocksClient
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import io.mockk.every
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@SpringBootTest(
    classes = [BcmApiApplication::class],
    properties = [
        "bcm.webhook-worker.enabled=false",
        "bcm.outbox-relay.enabled=false",
    ],
)
class TransferEventKafkaIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var submissions: TransactionSubmissionService

    @Autowired
    lateinit var submissionRecords: SubmissionRecordRepository

    @Autowired
    lateinit var webhookProcessor: WebhookDecisionProcessor

    @Autowired
    lateinit var relayProcessor: OutboxRelayProcessor

    @Autowired
    lateinit var inbox: WebhookInboxRepository

    @Autowired
    lateinit var accounts: AccountRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var vendor: FireblocksClient

    @BeforeEach
    fun setUp() {
        clearTables()
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, "source-ref", "vault-source"))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, "destination-ref", "vault-destination"))
        every { vendor.submitTransaction(match { it.externalTransactionId == WITHDRAWAL_EXTERNAL_ID }) } returns
            VendorTransactionSubmission.Accepted(WITHDRAWAL_VENDOR_TX_ID)
        every { vendor.submitTransaction(match { it.externalTransactionId == INTERNAL_EXTERNAL_ID }) } returns
            VendorTransactionSubmission.Accepted(INTERNAL_VENDOR_TX_ID)
    }

    @AfterEach
    fun tearDown() {
        clearTables()
    }

    @Test
    fun `출금 제출부터 FINALIZED 소비까지 이어지고 같은 externalTxId 재요청은 벤더를 다시 부르지 않는다`() {
        kafkaConsumer("withdrawal").use { consumer ->
            subscribeForNewRecords(consumer, listOf(WITHDRAWAL_TOPIC))

            assertThat(submissions.submit(withdrawalCommand()).transactionId).isEqualTo(WITHDRAWAL_VENDOR_TX_ID)
            assertThat(submissions.submit(withdrawalCommand(amount = "1.5")).transactionId)
                .isEqualTo(WITHDRAWAL_VENDOR_TX_ID)
            val submission = submissionRow(WITHDRAWAL_EXTERNAL_ID)
            assertThat(submission["sbmt_stcd"]).isEqualTo("SUBMITTED")
            assertThat(submission["vndr_tx_id"]).isEqualTo(WITHDRAWAL_VENDOR_TX_ID)
            verify(exactly = 1) { vendor.submitTransaction(match { it.externalTransactionId == WITHDRAWAL_EXTERNAL_ID }) }

            enqueueWebhook(WITHDRAWAL_VENDOR_TX_ID, WITHDRAWAL_EXTERNAL_ID, "CONFIRMING", 0, "01")
            enqueueWebhook(WITHDRAWAL_VENDOR_TX_ID, WITHDRAWAL_EXTERNAL_ID, "COMPLETED", 1, "02")
            webhookProcessor.processNext()
            webhookProcessor.processNext()
            relayProcessor.relayNext()
            relayProcessor.relayNext()

            val records = pollUntil(consumer, 2)
            val payloads = records.map { it.value() }
            val events = payloads.map(objectMapper::readTree)
            assertThat(records.map { it.key() }).containsExactly(SOURCE_ACCOUNT_ID, SOURCE_ACCOUNT_ID)
            assertThat(records.map { it.partition() }.distinct()).hasSize(1)
            assertThat(events.map { it.path("type").asString() }).containsOnly("WITHDRAWAL")
            assertThat(events.map { it.path("status").asString() }).containsExactly("CONFIRMED", "FINALIZED")
            assertThat(events.map { it.path("externalTxId").asString() }).containsOnly(WITHDRAWAL_EXTERNAL_ID)
            assertThat(events.map { it.path("eventId").asString() }).isSorted()
            payloads.forEach(::assertChainEventSchema)
        }
    }

    @Test
    fun `계정 간 제출은 INTERNAL로 기록되고 source account 파티션의 internal-events로 소비된다`() {
        kafkaConsumer("internal").use { consumer ->
            subscribeForNewRecords(consumer, listOf(INTERNAL_TOPIC))

            assertThat(submissions.submit(internalCommand()).transactionId).isEqualTo(INTERNAL_VENDOR_TX_ID)
            assertThat(submissionRow(INTERNAL_EXTERNAL_ID)["tx_dvcd"]).isEqualTo("INTERNAL")

            enqueueWebhook(INTERNAL_VENDOR_TX_ID, INTERNAL_EXTERNAL_ID, "CONFIRMING", 0, "03")
            webhookProcessor.processNext()
            relayProcessor.relayNext()

            val record = pollUntil(consumer, 1).single()
            val event = objectMapper.readTree(record.value())
            assertThat(record.key()).isEqualTo(SOURCE_ACCOUNT_ID)
            assertThat(event.path("type").asString()).isEqualTo("INTERNAL")
            assertThat(event.path("status").asString()).isEqualTo("CONFIRMED")
            assertThat(event.path("externalTxId").asString()).isEqualTo(INTERNAL_EXTERNAL_ID)
            assertChainEventSchema(record.value())
        }
    }

    @Test
    fun `같은 externalTxId 동시 요청 N개는 한 호출만 벤더에 제출하고 나머지는 503을 받는다`() {
        val vendorEntered = CountDownLatch(1)
        val releaseVendor = CountDownLatch(1)
        every { vendor.submitTransaction(match { it.externalTransactionId == WITHDRAWAL_EXTERNAL_ID }) } answers {
            vendorEntered.countDown()
            check(releaseVendor.await(5, TimeUnit.SECONDS))
            VendorTransactionSubmission.Accepted(WITHDRAWAL_VENDOR_TX_ID)
        }
        val first = FutureTask { submissions.submit(withdrawalCommand()) }
        Thread.ofVirtual().name("concurrent-submission-owner").start(first)
        val followers = Executors.newFixedThreadPool(CONCURRENT_FOLLOWERS)

        try {
            assertThat(vendorEntered.await(5, TimeUnit.SECONDS)).isTrue()
            val ready = CountDownLatch(CONCURRENT_FOLLOWERS)
            val start = CountDownLatch(1)
            val results =
                List(CONCURRENT_FOLLOWERS) {
                    followers.submit<Throwable?> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        runCatching { submissions.submit(withdrawalCommand()) }.exceptionOrNull()
                    }
                }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            results.forEach { result ->
                assertThat(result.get(5, TimeUnit.SECONDS)).isInstanceOf(SubmissionInProgressException::class.java)
            }
        } finally {
            releaseVendor.countDown()
            followers.shutdownNow()
        }

        assertThat(first.get(5, TimeUnit.SECONDS).transactionId).isEqualTo(WITHDRAWAL_VENDOR_TX_ID)
        verify(exactly = 1) { vendor.submitTransaction(match { it.externalTransactionId == WITHDRAWAL_EXTERNAL_ID }) }
    }

    @Test
    fun `SWEEP_BATCH 종결 웹훅은 항목 대사 전 대상을 유지하고 고객 토픽에 발행하지 않는다`() {
        submissionRecords.insert(sweepSubmission())
        insertSweepExecutionAndTarget()

        kafkaConsumer("sweep").use { consumer ->
            subscribeForNewRecords(consumer, listOf(DEPOSIT_TOPIC, WITHDRAWAL_TOPIC, INTERNAL_TOPIC))

            enqueueWebhook(SWEEP_VENDOR_TX_ID, SWEEP_EXTERNAL_ID, "COMPLETED", 1, "04")
            webhookProcessor.processNext()
            relayProcessor.relayNext()

            assertThat(consumer.poll(Duration.ofSeconds(1))).isEmpty()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isZero()
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_trgt")["actv_swp_exec_id"]).isEqualTo(SWEEP_EXECUTION_ID)
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_swp_exec_l")["swp_exec_stcd"]).isEqualTo("RECONCILING")
        }
    }

    private fun withdrawalCommand(amount: String = "1.50") =
        TransactionSubmissionCommand(
            externalTransactionId = WITHDRAWAL_EXTERNAL_ID,
            senderAccountId = SOURCE_ACCOUNT_ID,
            recipient = TransactionSubmissionRecipient.Address("0x9fE2"),
            network = "ETHEREUM",
            symbol = "USDC",
            amount = amount,
            note = "approved withdrawal",
            travelRuleMessage = null,
        )

    private fun internalCommand() =
        TransactionSubmissionCommand(
            externalTransactionId = INTERNAL_EXTERNAL_ID,
            senderAccountId = SOURCE_ACCOUNT_ID,
            recipient = TransactionSubmissionRecipient.Account(DESTINATION_ACCOUNT_ID),
            network = "ETHEREUM",
            symbol = "USDC",
            amount = "2.5",
            note = "delta settlement",
            travelRuleMessage = null,
        )

    private fun sweepSubmission(): SubmissionRecord {
        val fingerprint =
            SubmissionRequestHashes.contractCallV1(
                SOURCE_ACCOUNT_ID,
                "0x4444444444444444444444444444444444444444",
                "ETHEREUM",
                "USDC",
                "3",
                "0x1234",
            )
        return SubmissionRecord(
            externalTransactionId = SWEEP_EXTERNAL_ID,
            requestHash = fingerprint.requestHash,
            hashVersion = fingerprint.hashVersion,
            status = SubmissionStatus.SUBMITTED,
            claimId = null,
            claimExpiresAt = null,
            transactionType = SubmissionTransactionType.SWEEP_BATCH,
            vendorTransactionId = SWEEP_VENDOR_TX_ID,
            senderAccountId = SOURCE_ACCOUNT_ID,
            recipientType = SubmissionRecipientType.ADDRESS,
            recipientValue = "0x4444444444444444444444444444444444444444",
            network = "ETHEREUM",
            symbol = "USDC",
            amount = "3",
            requestedAt = "20260807115900",
            respondedAt = "20260807115901",
            sweepExecutionId = SWEEP_EXECUTION_ID,
            callData = fingerprint.normalizedCallData,
        )
    }

    private fun enqueueWebhook(
        vendorTransactionId: String,
        externalTransactionId: String,
        status: String,
        confirmations: Int,
        sequence: String,
    ) {
        val notificationId = "noti-$sequence-$externalTransactionId"
        inbox.insertIfAbsent(
            WebhookNotification(
                notificationId = notificationId,
                eventType = "transaction.status.updated",
                vendorTransactionId = vendorTransactionId,
                payload = payload(notificationId, vendorTransactionId, externalTransactionId, status, confirmations),
                payloadHash = "b".repeat(64),
                signature = "verified-signature",
                receivedAt = "202608071200$sequence",
            ),
        )
    }

    private fun payload(
        notificationId: String,
        vendorTransactionId: String,
        externalTransactionId: String,
        status: String,
        confirmations: Int,
    ): String =
        objectMapper
            .readTree(checkNotNull(javaClass.getResourceAsStream("/payload/transaction.created.json")))
            .also {
                (it as ObjectNode).put("id", notificationId)
                (it.path("data") as ObjectNode).apply {
                    put("id", vendorTransactionId)
                    put("externalTxId", externalTransactionId)
                    put("status", status)
                    put("subStatus", "CONFIRMED")
                    put("numOfConfirmations", confirmations)
                    (path("source") as ObjectNode).apply {
                        put("type", "VAULT_ACCOUNT")
                        put("id", "vault-source")
                    }
                }
            }.toString()

    private fun kafkaConsumer(label: String): KafkaConsumer<String, String> =
        KafkaConsumer(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "$label-e2e-${java.util.UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            },
        )

    private fun subscribeForNewRecords(
        consumer: KafkaConsumer<String, String>,
        topics: List<String>,
    ) {
        consumer.subscribe(topics)
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (consumer.assignment().isEmpty() && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250))
        }
        assertThat(consumer.assignment()).isNotEmpty()
    }

    private fun pollUntil(
        consumer: KafkaConsumer<String, String>,
        expected: Int,
    ) = buildList {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (size < expected && System.nanoTime() < deadline) {
            addAll(consumer.poll(Duration.ofMillis(500)))
        }
    }.also { assertThat(it).hasSize(expected) }

    private fun assertChainEventSchema(payload: String) {
        val yaml = YAMLMapper()
        val openApi = yaml.readTree(File("../../docs/api/openapi.yaml"))
        val schemaDocument = yaml.createObjectNode()
        schemaDocument.put("\$schema", SpecificationVersion.DRAFT_2020_12.dialectId)
        schemaDocument.put("\$ref", "#/components/schemas/ChainEvent")
        schemaDocument.set<com.fasterxml.jackson.databind.JsonNode>("components", openApi.path("components"))
        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaDocument)

        assertThat(schema.validate(yaml.readTree(payload))).isEmpty()
    }

    private fun insertAssetMapping() {
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
            VALUES ('ETHEREUM', 'USDC', 'vendor-usdc', '0xToken', '20260807120000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    private fun insertSweepExecutionAndTarget() {
        jdbc.update(
            """
            INSERT INTO bcm_swp_exec_l
              (swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id, swp_ctrt_addr,
               swp_exec_stcd, item_cnt, req_tot_amt, actl_tot_amt, gasless_yn, vndr_tx_id, tx_hash,
               req_dttm, fnsh_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, 'ETHEREUM', 'USDC', ?, '0xSweeper',
                    'SUBMITTED', 1, 3, NULL, 'Y', ?, NULL,
                    '20260807115900', NULL, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            SWEEP_EXECUTION_ID,
            SWEEP_EXTERNAL_ID,
            "a".repeat(64),
            SOURCE_ACCOUNT_ID,
            SWEEP_VENDOR_TX_ID,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_item_l
              (swp_exec_id, item_seq, acnt_id, src_addr, req_amt, actl_amt, swp_item_stcd, fail_cd, log_idx,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 1, ?, '0xSource', 3, NULL, 'READY', NULL, NULL,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            SWEEP_EXECUTION_ID,
            SOURCE_ACCOUNT_ID,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_trgt
              (acnt_id, ntwk_cd, tkn_smbl, reg_dttm, actv_swp_exec_id, actv_item_seq, try_cnt, last_try_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'ETHEREUM', 'USDC', '20260807115900', ?, 1, 1, '20260807115900',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            SOURCE_ACCOUNT_ID,
            SWEEP_EXECUTION_ID,
        )
    }

    private fun account(
        accountId: String,
        ref: String,
        vaultId: String,
    ) = Account(
        accountId = accountId,
        accountType = AccountType.SYSTEM,
        ref = ref,
        vendorVaultId = vaultId,
        registeredAt = "20260807115900",
    )

    private fun submissionRow(externalTransactionId: String): Map<String, Any?> =
        jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", externalTransactionId)

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_swp_trgt")
        jdbc.update("DELETE FROM bcm_swp_item_l")
        jdbc.update("DELETE FROM bcm_swp_exec_l")
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_tx_l")
        jdbc.update("DELETE FROM bcm_sbmt_l")
        jdbc.update("DELETE FROM bcm_whk_l")
        jdbc.update("DELETE FROM bcm_addr_m")
        jdbc.update("DELETE FROM bcm_vndr_ast_m")
        jdbc.update("DELETE FROM bcm_blkc_m")
        jdbc.update("DELETE FROM bcm_acnt_m")
    }

    private companion object {
        const val DEPOSIT_TOPIC = "deposit-events"
        const val WITHDRAWAL_TOPIC = "withdrawal-events"
        const val INTERNAL_TOPIC = "internal-events"
        const val SOURCE_ACCOUNT_ID = "acct-pool"
        const val DESTINATION_ACCOUNT_ID = "acct-omnibus"
        const val WITHDRAWAL_EXTERNAL_ID = "wd-e2e-1"
        const val INTERNAL_EXTERNAL_ID = "internal-e2e-1"
        const val SWEEP_EXTERNAL_ID = "swp-e2e-1"
        const val SWEEP_EXECUTION_ID = "01987654-3210-7abc-8def-0123456789ab"
        const val WITHDRAWAL_VENDOR_TX_ID = "f3339e5d-428e-4add-8018-631b972f3101"
        const val INTERNAL_VENDOR_TX_ID = "f3339e5d-428e-4add-8018-631b972f3102"
        const val SWEEP_VENDOR_TX_ID = "f3339e5d-428e-4add-8018-631b972f3103"
        const val CONCURRENT_FOLLOWERS = 7
    }
}
