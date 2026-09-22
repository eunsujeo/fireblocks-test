package com.whatto.bcm.app.webhook.application.event

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.whatto.bcm.app.webhook.BcmWebhookApplication
import com.whatto.bcm.app.webhook.application.webhook.WebhookDecisionProcessor
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
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

@SpringBootTest(
    classes = [BcmWebhookApplication::class],
    properties = [
        "bcm.webhook-worker.enabled=false",
        "bcm.outbox-relay.enabled=false",
        // 판단 재시도가 즉시 일어나야 하는 테스트다 — 재시도 대기(03 V29)는 별도 테스트가 고정한다.
        "bcm.webhook-worker.retry-base-seconds=0",
    ],
)
class DepositEventKafkaIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var webhookProcessor: WebhookDecisionProcessor

    @Autowired
    lateinit var relayProcessor: OutboxRelayProcessor

    @Autowired
    lateinit var inbox: WebhookInboxRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        clearTables()
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm, chain_mdl_dvcd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('ethereum-id', 'ETHEREUM', 1, 'Ethereum', 'N', 'N', '20260807120000', 'EVM',
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

    @AfterEach
    fun tearDown() {
        clearTables()
    }

    @Test
    fun `감지와 확정은 accountId 파티션에서 순서대로 발행되고 ChainEvent 스키마와 일치한다`() {
        inbox.insertIfAbsent(notification("noti-confirmed", payload("noti-confirmed", "CONFIRMING", 0), "20260807120000"))
        inbox.insertIfAbsent(notification("noti-finalized", payload("noti-finalized", "COMPLETED", 1), "20260807120001"))

        kafkaConsumer().use { consumer ->
            subscribeForNewRecords(consumer)
            webhookProcessor.processNext()
            webhookProcessor.processNext()
            relayProcessor.relayNext()
            relayProcessor.relayNext()

            val records = pollUntil(consumer, 2)
            val payloads = records.map { it.value() }
            val events = payloads.map(objectMapper::readTree)
            assertThat(records.map { it.key() }).containsExactly("acct-deposit", "acct-deposit")
            assertThat(records.map { it.partition() }.distinct()).hasSize(1)
            assertThat(events.map { it.path("status").asString() }).containsExactly("CONFIRMED", "FINALIZED")
            assertThat(events.map { it.path("eventId").asString() }).isSorted()
            payloads.forEach(::assertChainEventSchema)
        }

        assertThat(jdbc.queryForList("SELECT evnt_stcd FROM bcm_outbox_l ORDER BY evnt_id", String::class.java))
            .containsExactly("S", "S")
    }

    @Test
    fun `확정이 먼저 오면 감지를 합성해 Kafka에 두 이벤트를 순서대로 발행한다`() {
        inbox.insertIfAbsent(notification("noti-finalized-first", payload("noti-finalized-first", "COMPLETED", 1), "20260807120000"))

        kafkaConsumer().use { consumer ->
            subscribeForNewRecords(consumer)
            webhookProcessor.processNext()
            relayProcessor.relayNext()
            relayProcessor.relayNext()

            val events = pollUntil(consumer, 2).map { objectMapper.readTree(it.value()) }
            assertThat(events.map { it.path("status").asString() }).containsExactly("CONFIRMED", "FINALIZED")
            assertThat(events.map { it.path("eventId").asString() }).isSorted()
        }
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

    private fun kafkaConsumer(): KafkaConsumer<String, String> =
        KafkaConsumer(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "deposit-e2e-${java.util.UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            },
        )

    private fun subscribeForNewRecords(consumer: KafkaConsumer<String, String>) {
        consumer.subscribe(listOf(DEPOSIT_TOPIC))
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (consumer.assignment().isEmpty() && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250))
        }
        assertThat(consumer.assignment()).isNotEmpty()
    }

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

    private fun notification(
        id: String,
        payload: String,
        receivedAt: String,
    ) = WebhookNotification(
        notificationId = id,
        eventType = "transaction.status.updated",
        vendorTransactionId = VENDOR_TX_ID,
        payload = payload,
        payloadHash = "a".repeat(64),
        signature = "verified-signature",
        receivedAt = receivedAt,
    )

    private fun payload(
        notificationId: String,
        status: String,
        confirmations: Int,
    ): String =
        objectMapper
            .readTree(checkNotNull(javaClass.getResourceAsStream("/payload/transaction.created.json")))
            .also {
                (it as ObjectNode).put("id", notificationId)
                (it.path("data") as ObjectNode).apply {
                    put("status", status)
                    put("subStatus", "CONFIRMED")
                    put("numOfConfirmations", confirmations)
                }
            }.toString()

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_swp_trgt")
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_tx_l")
        jdbc.update("DELETE FROM bcm_whk_l")
        jdbc.update("DELETE FROM bcm_addr_m")
        jdbc.update("DELETE FROM bcm_vndr_ast_m")
        jdbc.update("DELETE FROM bcm_blkc_m")
    }

    private companion object {
        const val DEPOSIT_TOPIC = "deposit-events"
        const val VENDOR_TX_ID = "f3339e5d-428e-4add-8018-631b972f3195"
        const val DESTINATION_ADDRESS = "0x628501678d302023ca4555B678581917dF8D7636"
    }
}
