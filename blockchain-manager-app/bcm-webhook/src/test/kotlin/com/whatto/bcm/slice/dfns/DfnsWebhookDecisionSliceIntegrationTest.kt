package com.whatto.bcm.slice.dfns

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.id.UuidV7EventIdGenerator
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.app.config.ClockConfig
import com.whatto.bcm.app.webhook.application.webhook.DfnsWebhookDecisionConfig
import com.whatto.bcm.app.webhook.application.webhook.DfnsWebhookDecisionTransaction
import com.whatto.bcm.app.webhook.application.webhook.WebhookDecisionOutcome
import com.whatto.bcm.app.webhook.application.webhook.WebhookDecisionProcessingException
import com.whatto.bcm.app.webhook.application.webhook.WebhookDecisionWork
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical
import com.whatto.bcm.domain.tx.ChainHeadPort
import com.whatto.bcm.domain.vendor.NetworkChainEventParser
import com.whatto.bcm.domain.vendor.NetworkTransferEventParser
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.infra.client.config.ConfiguredFinalityPolicy
import com.whatto.bcm.infra.client.dfns.DfnsNetworkChainEventParser
import com.whatto.bcm.infra.client.dfns.DfnsNetworkTransferEventParser
import com.whatto.bcm.infra.client.dfns.DfnsProperties
import com.whatto.bcm.infra.client.json.JacksonChainEventSerializer
import com.whatto.bcm.infra.persistence.account.DepositAddressJdbcAdapter
import com.whatto.bcm.infra.persistence.asset.VendorAssetMappingJdbcAdapter
import com.whatto.bcm.infra.persistence.config.SpringTransactionRunner
import com.whatto.bcm.infra.persistence.event.OutboxJdbcAdapter
import com.whatto.bcm.infra.persistence.submission.SubmissionJdbcAdapter
import com.whatto.bcm.infra.persistence.tx.TxJdbcAdapter
import com.whatto.bcm.infra.persistence.webhook.WebhookInboxJdbcAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * Dfns 판단 유스케이스 전체를 **실제 PostgreSQL**과 결합한다 — 제출 원장 연결·`bcm_tx_l` 전이·outbox 적재·인박스 상태가
 * 한 트랜잭션에서 함께 커밋되고 함께 롤백되는지, 재전달이 이벤트를 두 번 쌓지 않는지를 고정한다(계약13·CLAUDE.md 3절 transactional outbox).
 *
 * 단위 테스트는 저장소를 대역으로 두므로 "한 트랜잭션"이라는 계약 자체는 검증하지 못한다 — 그 경계는 실제 DB에서만 드러난다.
 *
 * 조립은 실행 앱과 같은 `DfnsWebhookDecisionConfig`를 그대로 가져오고 **체인 head만 대역**이다(위탁 RPC는 외부 호출이다).
 * 파서는 실제 구현을 쓰고 원문은 채택 명세 1.1018.3의 `webhooks` 형태로 만든 표기이며 Baseline 실측이 아니다. 실벤더 호출 없음.
 */
@DataJdbcTest
@ContextConfiguration(classes = [DfnsWebhookSliceTestConfiguration::class])
@TestPropertySource(
    properties = [
        "bcm.provider=dfns",
        "bcm.webhook-worker.max-attempts=3",
        "bcm.webhook-worker.outbox-max-attempts=5",
        // 이 테스트는 즉시 재시도를 전제한다 — 재시도 대기(03 V29)는 WebhookInboxPersistenceTest가 고정한다.
        "bcm.webhook-worker.retry-base-seconds=0",
        "bcm.finality-confirmations.ethereum_test=12",
    ],
)
@Import(
    DfnsWebhookDecisionSliceIntegrationTest.SliceSupportConfiguration::class,
    DfnsWebhookDecisionConfig::class,
    DfnsWebhookDecisionTransaction::class,
    ClockConfig::class,
    ConfiguredFinalityPolicy::class,
    JacksonChainEventSerializer::class,
    UuidV7EventIdGenerator::class,
    SpringTransactionRunner::class,
    TxStateService::class,
    OutboxEventService::class,
    SubmissionObservationService::class,
    DepositAddressQueryService::class,
    VendorAssetMappingQueryService::class,
    WebhookInboxJdbcAdapter::class,
    TxJdbcAdapter::class,
    OutboxJdbcAdapter::class,
    SubmissionJdbcAdapter::class,
    DepositAddressJdbcAdapter::class,
    VendorAssetMappingJdbcAdapter::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DfnsWebhookDecisionSliceIntegrationTest {
    @Autowired lateinit var work: WebhookDecisionWork

    @Autowired lateinit var inbox: WebhookInboxRepository

    @Autowired lateinit var submissions: SubmissionRecordRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    /** `@TestConfiguration`이어야 한다 — 그냥 `@Configuration`이면 이 패키지를 훑는 실행 앱 컨텍스트에 시험용 빈이 섞인다. */
    @TestConfiguration(proxyBeanMethods = false)
    class SliceSupportConfiguration {
        /** 슬라이스 컨텍스트에는 Jackson 자동 구성이 없다 — 실행 앱과 같은 조립을 재현하려면 여기서 제공한다. */
        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()

        @Bean
        fun restClientBuilder(): RestClient.Builder = RestClient.builder()

        /**
         * 파서는 실제 구현이다. `DfnsProperties`를 **빈으로 두지 않는다** — 이 값은 `VendorExecutionLimits`이기도 해서
         * 이 패키지를 훑는 실행 앱 컨텍스트에 섞이면 Fireblocks 조립의 단일 후보 주입이 깨진다.
         */
        @Bean
        fun transferEventParser(objectMapper: ObjectMapper): NetworkTransferEventParser =
            DfnsNetworkTransferEventParser(objectMapper, dfnsProperties())

        @Bean
        fun chainEventParser(objectMapper: ObjectMapper): NetworkChainEventParser =
            DfnsNetworkChainEventParser(objectMapper, dfnsProperties())

        private fun dfnsProperties() = DfnsProperties(networks = mapOf(NETWORK to VENDOR_NETWORK))

        /**
         * 체인 head만 대역이다 — 위탁 RPC는 외부 호출이라 테스트에서 부르지 않는다(CLAUDE.md 0절).
         * 실행 조립의 `dfnsChainHeadPort`도 함께 만들어지므로 이 대역이 우선한다.
         */
        @Bean
        @Primary
        fun testChainHeadPort(): ChainHeadPort =
            object : ChainHeadPort {
                override fun headBlockNumber(network: String): Long = HEAD_BLOCK_NUMBER
            }
    }

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_tx_l")
        jdbc.update("DELETE FROM bcm_sbmt_l")
        jdbc.update("DELETE FROM bcm_whk_l")
    }

    @Test
    fun `전송 알림은 제출 원장 연결·거래 행·outbox 적재·인박스 처리 완료를 한 트랜잭션으로 커밋한다`() {
        submissions.insert(requestedSubmission())
        receive(TRANSFER_NOTIFICATION_ID, "wallet.transfer.confirmed", transferEvent())

        val outcome = work.processNext()

        assertThat(outcome).isInstanceOf(WebhookDecisionOutcome.Processed::class.java)
        // 넷이 같은 트랜잭션에서 함께 커밋됐다 — 응답을 못 받아 비어 있던 vndr_tx_id를 이 알림이 채운다(02).
        assertThat(submissionRow()).containsEntry("sbmt_stcd", "SUBMITTED").containsEntry("vndr_tx_id", TRANSFER_ID)
        assertThat(txRow()).containsEntry("vndr_tx_id", TRANSFER_ID).containsEntry("ext_tx_id", EXTERNAL_TX_ID)
        assertThat(txRow()).containsEntry("tx_hash", TX_HASH).containsEntry("acnt_id", ACCOUNT_ID)
        // 전송 알림에는 blockNumber가 없어 확정을 내지 않는다 — 확정은 온체인 이동 사건에서 난다(계약13).
        assertThat(txRow()).containsEntry("last_pub_stcd", "CONFIRMED").containsEntry("cnfm_cnt", 0)
        assertThat(outboxRows()).allSatisfy { row ->
            assertThat(row).containsEntry("topic", "withdrawal-events").containsEntry("evnt_stcd", "P")
            assertThat(row).containsEntry("vndr_tx_id", TRANSFER_ID).containsEntry("trace_id", TRANSFER_NOTIFICATION_ID)
        }
        assertThat(outboxRows()).isNotEmpty()
        assertThat(inboxRow(TRANSFER_NOTIFICATION_ID)).containsEntry("prcs_stcd", "S")
    }

    @Test
    fun `outbox 적재가 실패하면 제출 연결·거래 행·인박스 처리 완료가 모두 남지 않는다`() {
        submissions.insert(requestedSubmission())
        receive(TRANSFER_NOTIFICATION_ID, "wallet.transfer.confirmed", transferEvent())

        withRejectingTrigger("INSERT", "bcm_outbox_l") {
            // outbox 적재까지 실제로 도달했는지 확인한다 — 그 앞에서 멈췄다면 아래 롤백 단언이 공허하게 통과한다.
            assertThatThrownBy { work.processNext() }
                .isInstanceOf(WebhookDecisionProcessingException::class.java)
                .hasStackTraceContaining(WRITE_REJECTED)
        }

        // 하나라도 남으면 원장과 발행이 어긋난다 — 발행 없는 확정이나 원장 없는 이벤트가 된다(CLAUDE.md 3절).
        assertThat(submissionRow()).containsEntry("sbmt_stcd", "REQUESTED")
        assertThat(submissionRow()["vndr_tx_id"]).isNull()
        assertThat(txRows()).isEmpty()
        assertThat(outboxRows()).isEmpty()
        assertThat(inboxRow(TRANSFER_NOTIFICATION_ID)).containsEntry("prcs_stcd", "P").containsEntry("rtry_cnt", 0)

        // 실패 기록은 업무 트랜잭션 밖이라 롤백에 휩쓸리지 않는다 — 그래야 상한에 닿아 격리된다.
        work.recordUnexpectedFailure(TRANSFER_NOTIFICATION_ID)

        assertThat(inboxRow(TRANSFER_NOTIFICATION_ID)).containsEntry("prcs_stcd", "P").containsEntry("rtry_cnt", 1)
    }

    @Test
    fun `인박스 처리 완료가 실패해도 제출 연결·거래 행·outbox 적재가 되돌아온다`() {
        // 인박스 S는 이 트랜잭션의 **마지막** 쓰기다 — outbox 에서 실패시키면 S 갱신은 아예 시도되지 않아
        // "인박스가 P" 단언이 롤백의 증거가 되지 못한다. 마지막 쓰기를 실패시켜야 경계가 S까지 닿는지 드러난다.
        submissions.insert(requestedSubmission())
        receive(TRANSFER_NOTIFICATION_ID, "wallet.transfer.confirmed", transferEvent())

        withRejectingTrigger("UPDATE", "bcm_whk_l", "NEW.prcs_stcd = 'S'") {
            assertThatThrownBy { work.processNext() }
                .isInstanceOf(WebhookDecisionProcessingException::class.java)
                .hasStackTraceContaining(WRITE_REJECTED)
        }

        // markProcessed 가 별도 트랜잭션으로 빠지면 여기서 제출·거래·outbox 가 남아 실패한다.
        assertThat(submissionRow()).containsEntry("sbmt_stcd", "REQUESTED")
        assertThat(submissionRow()["vndr_tx_id"]).isNull()
        assertThat(txRows()).isEmpty()
        assertThat(outboxRows()).isEmpty()
        assertThat(inboxRow(TRANSFER_NOTIFICATION_ID)).containsEntry("prcs_stcd", "P").containsEntry("rtry_cnt", 0)
    }

    @Test
    fun `원장 밖 전송 알림은 거래를 만들지 않고 격리된다`() {
        // 우리 지갑에서 우리가 내지 않은 전송이 나갔다는 뜻이다. 처리 완료로 소거하면 원문이 사라지고,
        // 원장을 만들면 남의 자금이 우리 원장에 들어온다 — 둘 다 하지 않는다(계약13).
        receive(TRANSFER_NOTIFICATION_ID, "wallet.transfer.confirmed", transferEvent())

        val outcome = work.processNext()

        assertThat(outcome).isInstanceOf(WebhookDecisionOutcome.Quarantined::class.java)
        assertThat(txRows()).isEmpty()
        assertThat(outboxRows()).isEmpty()
        assertThat(inboxRow(TRANSFER_NOTIFICATION_ID))
            .containsEntry("prcs_stcd", "F")
            .containsEntry("err_msg", "transfer notification has no submission ledger entry")
    }

    @Test
    fun `우리 발신 거래가 없는 hash의 이동 사건은 아무 거래도 확정시키지 않는다`() {
        // 벤더가 우리 전송 요청에 결속해 준 txHash 만이 귀속의 근거다 — 다른 hash 의 이동은 우리 출금의 확정이 아니다.
        submissions.insert(requestedSubmission())
        receive(TRANSFER_NOTIFICATION_ID, "wallet.transfer.confirmed", transferEvent())
        work.processNext()
        val beforeEvent = outboxRows().map { it["evnt_id"] }

        receive(CHAIN_NOTIFICATION_ID, "wallet.blockchainevent.detected", outgoingChainEvent(txHash = OTHER_TX_HASH))

        assertThat(work.processNext()).isInstanceOf(WebhookDecisionOutcome.Retrying::class.java)
        assertThat(txRow()).containsEntry("last_pub_stcd", "CONFIRMED").containsEntry("cnfm_cnt", 0)
        assertThat(outboxRows().map { it["evnt_id"] }).isEqualTo(beforeEvent)
    }

    @Test
    fun `같은 전송의 재전달은 거래를 다시 만들지도 outbox를 다시 쌓지도 않는다`() {
        submissions.insert(requestedSubmission())
        receive(TRANSFER_NOTIFICATION_ID, "wallet.transfer.confirmed", transferEvent())
        work.processNext()
        val published = outboxRows().map { it["evnt_id"] }
        assertThat(published).isNotEmpty()

        // 벤더 재전달은 알림 ID가 다르므로 인박스 dedup이 막지 못한다 — 같은 상태의 관찰이 이벤트를 만들지 않아야 한다.
        receive(
            RETRY_NOTIFICATION_ID,
            "wallet.transfer.confirmed",
            transferEvent(notificationId = RETRY_NOTIFICATION_ID, retryOf = TRANSFER_NOTIFICATION_ID),
        )

        assertThat(work.processNext()).isInstanceOf(WebhookDecisionOutcome.Processed::class.java)
        assertThat(outboxRows().map { it["evnt_id"] }).isEqualTo(published)
        assertThat(txRows()).hasSize(1)
        assertThat(inboxRow(RETRY_NOTIFICATION_ID)).containsEntry("prcs_stcd", "S")
    }

    @Test
    fun `발신 이동 사건은 그 거래에 붙어 확정과 이벤트를 한 트랜잭션에 더한다`() {
        submissions.insert(requestedSubmission())
        receive(TRANSFER_NOTIFICATION_ID, "wallet.transfer.confirmed", transferEvent())
        work.processNext()
        val beforeAttach = outboxRows().map { it["evnt_id"] }

        receive(CHAIN_NOTIFICATION_ID, "wallet.blockchainevent.detected", outgoingChainEvent())

        assertThat(work.processNext()).isInstanceOf(WebhookDecisionOutcome.Processed::class.java)
        // 새 거래를 만들지 않고 기존 거래에 붙는다 — 출금의 확정이 여기서 난다(계약13).
        assertThat(txRows()).hasSize(1)
        assertThat(txRow()).containsEntry("vndr_tx_id", TRANSFER_ID).containsEntry("last_pub_stcd", "FINALIZED")
        assertThat(txRow()).containsEntry("cnfm_cnt", 12)
        assertThat(outboxRows().map { it["evnt_id"] }).containsAll(beforeAttach).hasSizeGreaterThan(beforeAttach.size)
        assertThat(outboxRows().last()).containsEntry("vndr_tx_id", TRANSFER_ID).containsEntry("trace_id", CHAIN_NOTIFICATION_ID)
        assertThat(inboxRow(CHAIN_NOTIFICATION_ID)).containsEntry("prcs_stcd", "S")
    }

    /**
     * 한 쓰기만 실패시켜 같은 트랜잭션의 나머지가 되돌아오는지 본다 — 테스트 전용 trigger이며 끝나면 반드시 지운다.
     * [condition]은 trigger 의 `WHEN` 절이다 — 같은 테이블의 다른 쓰기까지 막지 않으려면 필요하다.
     */
    private fun withRejectingTrigger(
        event: String,
        table: String,
        condition: String? = null,
        block: () -> Unit,
    ) {
        jdbc.execute(
            "CREATE OR REPLACE FUNCTION bcm_test_reject() RETURNS trigger AS " +
                "'BEGIN RAISE EXCEPTION ''$WRITE_REJECTED''; END;' LANGUAGE plpgsql",
        )
        jdbc.execute(
            "CREATE TRIGGER trg_bcm_test_reject BEFORE $event ON $table FOR EACH ROW " +
                (condition?.let { "WHEN ($it) " } ?: "") + "EXECUTE FUNCTION bcm_test_reject()",
        )
        try {
            block()
        } finally {
            jdbc.execute("DROP TRIGGER trg_bcm_test_reject ON $table")
            jdbc.execute("DROP FUNCTION bcm_test_reject()")
        }
    }

    private fun receive(
        notificationId: String,
        eventType: String,
        payload: String,
    ) {
        inbox.insertIfAbsent(
            WebhookNotification(
                notificationId = notificationId,
                eventType = eventType,
                vendorTransactionId = null,
                payload = payload,
                payloadHash = sha256(payload),
                signature = "sha256=test",
                receivedAt = "20260917090000",
            ),
        )
    }

    private fun requestedSubmission() =
        SubmissionRecord(
            externalTransactionId = EXTERNAL_TX_ID,
            requestHash = "0".repeat(64),
            hashVersion = "v1",
            status = SubmissionStatus.REQUESTED,
            claimId = null,
            claimExpiresAt = null,
            transactionType = SubmissionTransactionType.WITHDRAWAL,
            // 제출 응답을 못 받은 상태다 — 이 알림이 벤더 전송 ID를 채운다(02).
            vendorTransactionId = null,
            senderAccountId = ACCOUNT_ID,
            recipientType = SubmissionRecipientType.ADDRESS,
            recipientValue = DESTINATION,
            network = NETWORK,
            symbol = "USDC",
            amount = "1.5",
            requestedAt = "20260917085900",
            respondedAt = null,
            vendorCanonical =
                SubmissionVendorCanonical(
                    vendorWalletId = WALLET_ID,
                    vendorAssetId = ASSET_KEY,
                    amountBaseUnits = BASE_UNITS,
                    decimals = 6,
                ),
        )

    private fun transferEvent(
        notificationId: String = TRANSFER_NOTIFICATION_ID,
        retryOf: String? = null,
    ): String =
        buildString {
            append("""{"data":{"transferRequest":{"network":"$VENDOR_NETWORK"""")
            append(""","requestBody":{"kind":"Erc20","contract":"$CONTRACT","to":"$DESTINATION","amount":"$BASE_UNITS"}""")
            append(""","txHash":"$TX_HASH","externalId":"$EXTERNAL_TX_ID","walletId":"$WALLET_ID","id":"$TRANSFER_ID"""")
            append(""","requester":{"userId":"us-1"},"metadata":{"asset":{"symbol":"USDC","decimals":6}}""")
            append(""","status":"Confirmed","dateRequested":"2026-09-17T08:59:00.000Z"}}""")
            append(""","id":"$notificationId","date":"2026-09-17T09:00:05.000Z"""")
            append(""","kind":"wallet.transfer.confirmed","deliveryAttempt":1""")
            retryOf?.let { append(""","retryOf":"$it"""") }
            append("}")
        }

    private fun outgoingChainEvent(txHash: String = TX_HASH): String =
        buildString {
            append("""{"data":{"blockchainEvent":{"network":"$VENDOR_NETWORK","direction":"Out","index":"3"""")
            append(""","metadata":{"asset":{"symbol":"USDC","decimals":6}}""")
            append(""","from":"$WALLET_ADDRESS","to":"$DESTINATION","symbol":"USDC","decimals":6""")
            append(""","walletId":"$WALLET_ID","kind":"Erc20Transfer","contract":"$CONTRACT"""")
            append(""","status":"Confirmed","value":"$BASE_UNITS","txHash":"$txHash"""")
            append(""","timestamp":"1758099600","blockNumber":$BLOCK_NUMBER}""")
            append(""","wallet":{"id":"$WALLET_ID","network":"$VENDOR_NETWORK","address":"$WALLET_ADDRESS"}}""")
            append(""","id":"$CHAIN_NOTIFICATION_ID","date":"2026-09-17T09:01:05.000Z"""")
            append(""","kind":"wallet.blockchainevent.detected","deliveryAttempt":1}""")
        }

    private fun submissionRow(): Map<String, Any?> = jdbc.queryForList("SELECT * FROM bcm_sbmt_l").single()

    private fun txRows(): List<Map<String, Any?>> = jdbc.queryForList("SELECT * FROM bcm_tx_l ORDER BY vndr_tx_id")

    private fun txRow(): Map<String, Any?> = txRows().single()

    private fun outboxRows(): List<Map<String, Any?>> = jdbc.queryForList("SELECT * FROM bcm_outbox_l ORDER BY evnt_id")

    private fun inboxRow(notificationId: String): Map<String, Any?> =
        jdbc.queryForList("SELECT * FROM bcm_whk_l WHERE noti_id = ?", notificationId).single()

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val NETWORK = "ETHEREUM_TEST"
        const val VENDOR_NETWORK = "EthereumSepolia"
        const val ACCOUNT_ID = "acnt-slice-1"
        const val EXTERNAL_TX_ID = "wd-slice-1"
        const val WALLET_ID = "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx"
        const val WALLET_ADDRESS = "0x9c7d4b196cb0c7b01d743fbc6116a902379c7999"
        const val TRANSFER_ID = "xfr-20g4k-nsdpo-mg6arrifgvid4orn"
        const val CONTRACT = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
        const val DESTINATION = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47"
        const val TX_HASH = "0x2a6f0c9b6bd0a7b9f4e3e0b33d2ff4c7cf9a7a0f9b4d2a8f8c1b3e5d7a9c0b11"
        const val OTHER_TX_HASH = "0x3b7e1d0c7ce1b8caf5f4f1c44e3ff5d8da0b8b1fac5e3b9f9d2c4f6e8bad1c22"
        const val ASSET_KEY = "$VENDOR_NETWORK:Erc20:$CONTRACT"
        const val BASE_UNITS = "1500000"
        const val BLOCK_NUMBER = 8452119L

        /** 임계 12를 정확히 채우는 head — 블록 자체가 1컨펌이다(계약13). */
        const val HEAD_BLOCK_NUMBER = 8452130L
        const val WRITE_REJECTED = "write rejected by test"
        const val TRANSFER_NOTIFICATION_ID = "whe-544ul-uqgad-jkgltj5p6fvd04cj"
        const val RETRY_NOTIFICATION_ID = "whe-544ul-uqgad-aaaaaaaaaaaaaaaa"
        const val CHAIN_NOTIFICATION_ID = "whe-544ul-uqgad-bbbbbbbbbbbbbbbb"

        @JvmStatic
        @DynamicPropertySource
        fun dataset(registry: DynamicPropertyRegistry) {
            DfnsWebhookDatasetTestSupport.register(registry)
        }
    }
}
