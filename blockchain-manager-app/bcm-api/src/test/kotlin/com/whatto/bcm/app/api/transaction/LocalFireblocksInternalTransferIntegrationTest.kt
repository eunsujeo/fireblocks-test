package com.whatto.bcm.app.api.transaction

import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.app.application.event.OutboxRelayProcessor
import com.whatto.bcm.app.application.webhook.WebhookDecisionOutcome
import com.whatto.bcm.app.application.webhook.WebhookDecisionProcessor
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorWebhookRecoveryPort
import com.whatto.bcm.domain.vendor.WalletVendorPort
import com.whatto.bcm.testsupport.TestSupportApplication
import com.whatto.bcm.testsupport.chain.LocalChainConfiguration
import com.whatto.bcm.testsupport.chain.LocalChainEnvironment
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.BigInteger
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Duration
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    classes = [BcmApiApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = [
        "bcm.webhook-worker.enabled=false",
        "bcm.outbox-relay.enabled=false",
    ],
)
class LocalFireblocksInternalTransferIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var vendor: WalletVendorPort

    @Autowired
    lateinit var webhookRecovery: VendorWebhookRecoveryPort

    @Autowired
    lateinit var vendorTransactions: VendorTransactionPort

    @Autowired
    lateinit var accounts: AccountRepository

    @Autowired
    lateinit var webhookProcessor: WebhookDecisionProcessor

    @Autowired
    lateinit var relayProcessor: OutboxRelayProcessor

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        clearTables()
        chain.reset()
    }

    @AfterEach
    fun tearDown() {
        clearTables()
    }

    @Test
    fun `BCM 내부이체는 Stub 실제 거래와 서명 Webhook을 거쳐 원장과 Kafka까지 수렴한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        webhookRecovery.activateWebhook(LOCAL_WEBHOOK_ID)
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)

        kafkaConsumer().use { consumer ->
            subscribeForNewRecords(consumer)

            val transactionId = submitInternalTransfer()

            assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore - RAW_AMOUNT)
            assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore + RAW_AMOUNT)
            advance(transactionId)
            advance(transactionId)
            assertThat(vendorTransactions.transaction(transactionId)?.networkRecords).singleElement().satisfies({ record ->
                assertThat(record.type).isEqualTo("TRANSFER")
                assertThat(record.source.id).isEqualTo(sourceVault.vaultId)
                assertThat(record.destination.id).isEqualTo(destinationVault.vaultId)
                assertThat(record.destinationAddress).isEqualTo(destinationWallet.address)
                assertThat(record.netAmount).isEqualTo(DISPLAY_AMOUNT)
                assertThat(record.dropped).isFalse()
            })
            processAllWebhooks()
            relayProcessor.relayNext()
            relayProcessor.relayNext()
            relayProcessor.relayNext()

            val records = pollUntil(consumer, 3)
            val events = records.map { objectMapper.readTree(it.value()) }
            assertThat(records.map { it.key() }).containsOnly(SOURCE_ACCOUNT_ID)
            assertThat(events.map { it.path("type").asString() }).containsOnly("INTERNAL")
            assertThat(events.map { it.path("status").asString() }).containsExactly("SUBMITTED", "CONFIRMED", "FINALIZED")
            assertThat(events.map { it.path("externalTxId").asString() }).containsOnly(EXTERNAL_TRANSACTION_ID)
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", EXTERNAL_TRANSACTION_ID))
                .containsEntry("vndr_tx_id", transactionId)
                .containsEntry("sbmt_stcd", "SUBMITTED")
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", transactionId))
                .containsEntry("last_pub_stcd", "FINALIZED")
                .containsEntry("cnfm_cnt", 2)
        }
    }

    @Test
    fun `BCM 외부 출금은 source native 잔액 없이 Universal Gasless로 실제 토큰을 전송한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        chain.setNativeBalance(sourceWallet.address, BigInteger.ZERO)
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(chain.manifest.omnibusAddress)

        val payload =
            objectMapper.writeValueAsBytes(
                mapOf(
                    "externalTxId" to GASLESS_WITHDRAWAL_EXTERNAL_TRANSACTION_ID,
                    "from" to mapOf("type" to "ACCOUNT", "accountId" to SOURCE_ACCOUNT_ID),
                    "to" to mapOf("type" to "ADDRESS", "address" to chain.manifest.omnibusAddress),
                    "network" to NETWORK,
                    "symbol" to SYMBOL,
                    "amount" to DISPLAY_AMOUNT,
                    "note" to "local gasless withdrawal",
                ),
            )
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$bcmPort/transactions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )

        assertThat(response.statusCode()).isEqualTo(202)
        assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore - RAW_AMOUNT)
        assertThat(chain.tokenBalance(chain.manifest.omnibusAddress)).isEqualTo(destinationBefore + RAW_AMOUNT)
        assertThat(chain.nativeBalance(sourceWallet.address)).isZero()
        assertThat(chain.delegatedCode(sourceWallet.address))
            .isEqualTo("0xef0100${chain.manifest.gaslessDelegationContractAddress.removePrefix("0x")}")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", GASLESS_WITHDRAWAL_EXTERNAL_TRANSACTION_ID))
            .containsEntry("tx_dvcd", "WITHDRAWAL")
            .containsEntry("sbmt_stcd", "SUBMITTED")
    }

    @Test
    fun `실제 로컬 입금은 Stub 서명 Webhook을 거쳐 BCM 입금 원장과 Kafka까지 수렴한다`() {
        val destinationVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        webhookRecovery.activateWebhook(LOCAL_WEBHOOK_ID)
        insertAssetMapping()
        accounts.insert(account(DEPOSIT_ACCOUNT_ID, destinationVault.vaultId))
        insertDepositAddress(destinationWallet.address)
        val balanceBefore = chain.tokenBalance(destinationWallet.address)

        kafkaConsumer().use { consumer ->
            subscribeForNewRecords(consumer, DEPOSIT_TOPIC)

            val transactionId = createExternalDeposit(destinationVault.vaultId)

            assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(balanceBefore + RAW_AMOUNT)
            advance(transactionId)
            advance(transactionId)
            processAllWebhooks()
            relayProcessor.relayNext()
            relayProcessor.relayNext()
            relayProcessor.relayNext()

            val records = pollUntil(consumer, 3)
            val events = records.map { objectMapper.readTree(it.value()) }
            assertThat(records.map { it.key() }).containsOnly(DEPOSIT_ACCOUNT_ID)
            assertThat(events.map { it.path("type").asString() }).containsOnly("DEPOSIT")
            assertThat(events.map { it.path("status").asString() }).containsExactly("SUBMITTED", "CONFIRMED", "FINALIZED")
            assertThat(events.map { it.path("externalTxId").asString() }).containsOnly(DEPOSIT_EXTERNAL_TRANSACTION_ID)
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", transactionId))
                .containsEntry("acnt_id", DEPOSIT_ACCOUNT_ID)
                .containsEntry("last_pub_stcd", "FINALIZED")
                .containsEntry("cnfm_cnt", 2)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM bcm_swp_trgt WHERE acnt_id = ? AND ntwk_cd = ? AND tkn_smbl = ?",
                    Long::class.java,
                    DEPOSIT_ACCOUNT_ID,
                    NETWORK,
                    SYMBOL,
                ),
            ).isEqualTo(1)
        }
    }

    @Test
    fun `Stub 제출 응답이 유실되어도 BCM은 externalTxId로 실제 거래를 한 번만 회수한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val nonceBefore = chain.transactionCount(sourceWallet.address)
        injectNextTransactionResponseLoss()

        val transactionId = submitInternalTransfer(LOST_RESPONSE_EXTERNAL_TRANSACTION_ID)

        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore - RAW_AMOUNT)
        assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore + RAW_AMOUNT)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", LOST_RESPONSE_EXTERNAL_TRANSACTION_ID))
            .containsEntry("vndr_tx_id", transactionId)
            .containsEntry("sbmt_stcd", "SUBMITTED")
    }

    @Test
    fun `Stub이 같은 서명 Webhook을 중복 전달해도 BCM은 고객 이벤트를 한 번만 발행한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        webhookRecovery.activateWebhook(LOCAL_WEBHOOK_ID)
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))

        kafkaConsumer().use { consumer ->
            subscribeForNewRecords(consumer)

            val transactionId = submitInternalTransfer(DUPLICATE_WEBHOOK_EXTERNAL_TRANSACTION_ID)
            redeliverLastWebhook()
            advance(transactionId)
            advance(transactionId)
            processAllWebhooks()
            relayProcessor.relayNext()
            relayProcessor.relayNext()
            relayProcessor.relayNext()

            val records = pollUntil(consumer, 3)
            assertThat(records.map { objectMapper.readTree(it.value()).path("status").asString() })
                .containsExactly("SUBMITTED", "CONFIRMED", "FINALIZED")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_whk_l", Long::class.java)).isEqualTo(4)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l WHERE vndr_tx_id = ?", Long::class.java, transactionId))
                .isEqualTo(1)
        }
    }

    @Test
    fun `Stub이 제출 전 429를 반환하면 BCM은 백오프 재시도로 실제 거래를 한 번만 만든다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val nonceBefore = chain.transactionCount(sourceWallet.address)
        injectNextTransactionResponse(status = 429, afterCommit = false)

        val transactionId = submitInternalTransfer(RATE_LIMIT_EXTERNAL_TRANSACTION_ID)

        assertThat(transactionId).isNotBlank()
        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore - RAW_AMOUNT)
        assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore + RAW_AMOUNT)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", RATE_LIMIT_EXTERNAL_TRANSACTION_ID))
            .containsEntry("vndr_tx_id", transactionId)
            .containsEntry("sbmt_stcd", "SUBMITTED")
    }

    @Test
    fun `Stub이 온체인 커밋 뒤 500을 반환해도 BCM은 claim 만료 후 같은 거래를 회수한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val nonceBefore = chain.transactionCount(sourceWallet.address)
        injectNextTransactionResponse(status = 500, afterCommit = true)

        val failed = requestInternalTransfer(COMMITTED_SERVER_ERROR_EXTERNAL_TRANSACTION_ID)

        assertThat(failed.statusCode()).isEqualTo(500)
        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore - RAW_AMOUNT)
        assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore + RAW_AMOUNT)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", COMMITTED_SERVER_ERROR_EXTERNAL_TRANSACTION_ID))
            .containsEntry("vndr_tx_id", null)
            .containsEntry("sbmt_stcd", "REQUESTED")
        jdbc.update(
            "UPDATE bcm_sbmt_l SET claim_exp_dttm = '00000000000000' WHERE ext_tx_id = ?",
            COMMITTED_SERVER_ERROR_EXTERNAL_TRANSACTION_ID,
        )

        val transactionId = submitInternalTransfer(COMMITTED_SERVER_ERROR_EXTERNAL_TRANSACTION_ID)

        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", COMMITTED_SERVER_ERROR_EXTERNAL_TRANSACTION_ID))
            .containsEntry("vndr_tx_id", transactionId)
            .containsEntry("sbmt_stcd", "SUBMITTED")
    }

    @Test
    fun `실제 ERC20 잔액 부족 revert는 FAILED 원장과 고객 이벤트로 수렴한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        webhookRecovery.activateWebhook(LOCAL_WEBHOOK_ID)
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val nonceBefore = chain.transactionCount(sourceWallet.address)
        val insufficientAmount = BigDecimal(sourceBefore, 6).add(BigDecimal.ONE).toPlainString()

        kafkaConsumer().use { consumer ->
            subscribeForNewRecords(consumer)

            val transactionId = submitInternalTransfer(REVERT_EXTERNAL_TRANSACTION_ID, insufficientAmount)
            advance(transactionId)
            processWebhooks(expected = 2)
            relayProcessor.relayNext()
            relayProcessor.relayNext()

            val records = pollUntil(consumer, 2)
            assertThat(records.map { objectMapper.readTree(it.value()).path("status").asString() })
                .containsExactly("SUBMITTED", "FAILED")
            assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore + BigInteger.ONE)
            assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore)
            assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore)
            assertThat(vendorTransactions.transaction(transactionId)?.rawStatus).isEqualTo("FAILED")
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", transactionId))
                .containsEntry("last_pub_stcd", "FAILED")
                .containsEntry("cnfm_cnt", 0)
        }
    }

    @Test
    fun `최초 Webhook 유실 뒤 재전달되어도 FINALIZED 원장은 역행하지 않는다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        webhookRecovery.activateWebhook(LOCAL_WEBHOOK_ID)
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        injectNextWebhookDeliveryFailure()

        kafkaConsumer().use { consumer ->
            subscribeForNewRecords(consumer)

            val transactionId = submitInternalTransfer(LOST_WEBHOOK_EXTERNAL_TRANSACTION_ID)
            advance(transactionId)
            advance(transactionId)
            processWebhooks(expected = 3)

            assertThat(webhookRecovery.resendFailedWebhookNotifications(LOCAL_WEBHOOK_ID).scheduledNotificationCount).isEqualTo(1)
            processWebhooks(expected = 1)
            relayProcessor.relayNext()
            relayProcessor.relayNext()

            val records = pollUntil(consumer, 2)
            assertThat(records.map { objectMapper.readTree(it.value()).path("status").asString() })
                .containsExactly("CONFIRMED", "FINALIZED")
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", transactionId))
                .containsEntry("last_pub_stcd", "FINALIZED")
                .containsEntry("cnfm_cnt", 2)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_whk_l", Long::class.java)).isEqualTo(4)
        }
    }

    @ParameterizedTest(name = "벤더 {0}는 온체인 전송 없이 REJECTED로 수렴한다")
    @ValueSource(strings = ["BLOCKED", "REJECTED"])
    fun `벤더 정책 거절은 온체인 전송 없이 REJECTED 고객 이벤트로 수렴한다`(vendorStatus: String) {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        webhookRecovery.activateWebhook(LOCAL_WEBHOOK_ID)
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val nonceBefore = chain.transactionCount(sourceWallet.address)
        val externalTransactionId = "internal-local-${vendorStatus.lowercase()}-e2e-1"
        injectNextTerminalVendorState(vendorStatus)

        kafkaConsumer().use { consumer ->
            subscribeForNewRecords(consumer)

            val transactionId = submitInternalTransfer(externalTransactionId)
            processWebhooks(expected = 1)
            relayProcessor.relayNext()
            relayProcessor.relayNext()

            val records = pollUntil(consumer, 2)
            assertThat(records.map { objectMapper.readTree(it.value()).path("status").asString() })
                .containsExactly("CONFIRMED", "REJECTED")
            assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore)
            assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore)
            assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore)
            assertThat(vendorTransactions.transaction(transactionId)).satisfies({ transaction ->
                assertThat(transaction?.rawStatus).isEqualTo(vendorStatus)
                assertThat(transaction?.transactionHash).isNull()
            })
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", transactionId))
                .containsEntry("last_pub_stcd", "REJECTED")
                .containsEntry("cnfm_cnt", 0)
        }
    }

    @Test
    fun `제출 전 응답 timeout은 REQUESTED를 유지하고 claim 만료 뒤 한 건만 재제출한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val nonceBefore = chain.transactionCount(sourceWallet.address)
        injectNextTransactionResponse(status = 504, afterCommit = false, delayMillis = 1_000)

        val startedAt = System.nanoTime()
        val failed = requestInternalTransfer(TIMEOUT_EXTERNAL_TRANSACTION_ID)
        val elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

        assertThat(failed.statusCode()).isEqualTo(500)
        assertThat(elapsedMillis).isBetween(400, 900)
        Thread.sleep(700)
        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore)
        assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore)
        assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", TIMEOUT_EXTERNAL_TRANSACTION_ID))
            .containsEntry("vndr_tx_id", null)
            .containsEntry("sbmt_stcd", "REQUESTED")
        jdbc.update(
            "UPDATE bcm_sbmt_l SET claim_exp_dttm = '00000000000000' WHERE ext_tx_id = ?",
            TIMEOUT_EXTERNAL_TRANSACTION_ID,
        )

        val transactionId = submitInternalTransfer(TIMEOUT_EXTERNAL_TRANSACTION_ID)

        assertThat(transactionId).isNotBlank()
        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore - RAW_AMOUNT)
        assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore + RAW_AMOUNT)
    }

    @Test
    fun `장기 PENDING_SIGNATURE는 온체인 전송과 중복 제출 없이 SUBMITTED로 유지된다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        webhookRecovery.activateWebhook(LOCAL_WEBHOOK_ID)
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val nonceBefore = chain.transactionCount(sourceWallet.address)
        injectNextPendingVendorState("PENDING_SIGNATURE")

        kafkaConsumer().use { consumer ->
            subscribeForNewRecords(consumer)

            val transactionId = submitInternalTransfer(LONG_PENDING_EXTERNAL_TRANSACTION_ID)
            val retriedTransactionId = submitInternalTransfer(LONG_PENDING_EXTERNAL_TRANSACTION_ID)
            advance(transactionId)
            advance(transactionId)
            processWebhooks(expected = 1)
            relayProcessor.relayNext()

            val records = pollUntil(consumer, 1)
            assertThat(retriedTransactionId).isEqualTo(transactionId)
            assertThat(records.map { objectMapper.readTree(it.value()).path("status").asString() })
                .containsExactly("SUBMITTED")
            assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore)
            assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore)
            assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore)
            assertThat(vendorTransactions.transaction(transactionId)).satisfies({ transaction ->
                assertThat(transaction?.rawStatus).isEqualTo("PENDING_SIGNATURE")
                assertThat(transaction?.transactionHash).isNull()
            })
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", LONG_PENDING_EXTERNAL_TRANSACTION_ID))
                .containsEntry("vndr_tx_id", transactionId)
                .containsEntry("sbmt_stcd", "SUBMITTED")
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", transactionId))
                .containsEntry("last_pub_stcd", "SUBMITTED")
                .containsEntry("cnfm_cnt", 0)
        }
    }

    @Test
    fun `가스 잔액 부족은 거래를 만들지 않고 REQUESTED로 복구 대기한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val nonceBefore = chain.transactionCount(sourceWallet.address)
        setNativeBalance(sourceWallet.address, BigInteger.ZERO)

        val failed = requestInternalTransfer(INSUFFICIENT_GAS_EXTERNAL_TRANSACTION_ID)

        assertThat(failed.statusCode()).isEqualTo(500)
        assertThat(chain.nativeBalance(sourceWallet.address)).isZero()
        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore)
        assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore)
        assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore)
        assertThat(
            vendorTransactions.transactionByExternalTransactionId(INSUFFICIENT_GAS_EXTERNAL_TRANSACTION_ID),
        ).isNull()
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", INSUFFICIENT_GAS_EXTERNAL_TRANSACTION_ID))
            .containsEntry("vndr_tx_id", null)
            .containsEntry("sbmt_stcd", "REQUESTED")
    }

    @Test
    fun `과거 nonce 재사용은 두 번째 거래를 만들지 않고 REQUESTED로 복구 대기한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val staleNonce = chain.transactionCount(sourceWallet.address)
        submitInternalTransfer(NONCE_PRIMER_EXTERNAL_TRANSACTION_ID)
        injectNextTransactionNonce(staleNonce)

        val failed = requestInternalTransfer(STALE_NONCE_EXTERNAL_TRANSACTION_ID)

        assertThat(failed.statusCode()).isEqualTo(500)
        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(staleNonce + BigInteger.ONE)
        assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore - RAW_AMOUNT)
        assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore + RAW_AMOUNT)
        assertThat(vendorTransactions.transactionByExternalTransactionId(STALE_NONCE_EXTERNAL_TRANSACTION_ID)).isNull()
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", STALE_NONCE_EXTERNAL_TRANSACTION_ID))
            .containsEntry("vndr_tx_id", null)
            .containsEntry("sbmt_stcd", "REQUESTED")
    }

    @Test
    fun `동시 제출은 소유 요청만 벤더에 도달하고 응답 유실 뒤 같은 거래를 회수한다`() {
        val sourceVault = vendor.createVault("CUSTOMER:E2E-SOURCE", "createVault:e2e-source")
        val destinationVault = vendor.createVault("CUSTOMER:E2E-DESTINATION", "createVault:e2e-destination")
        val sourceWallet = vendor.createDepositAddress(sourceVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-source")
        val destinationWallet =
            vendor.createDepositAddress(destinationVault.vaultId, TOKEN_ASSET_ID, "createWallet:e2e-destination")
        insertAssetMapping()
        accounts.insert(account(SOURCE_ACCOUNT_ID, sourceVault.vaultId))
        accounts.insert(account(DESTINATION_ACCOUNT_ID, destinationVault.vaultId))
        val sourceBefore = chain.tokenBalance(sourceWallet.address)
        val destinationBefore = chain.tokenBalance(destinationWallet.address)
        val nonceBefore = chain.transactionCount(sourceWallet.address)
        injectNextTransactionResponse(status = 500, afterCommit = true, delayMillis = 300)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val responses =
            try {
                val futures =
                    List(2) {
                        executor.submit<HttpResponse<ByteArray>> {
                            check(start.await(5, TimeUnit.SECONDS))
                            requestInternalTransfer(CONCURRENT_EXTERNAL_TRANSACTION_ID)
                        }
                    }
                start.countDown()
                futures.map { it.get(5, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertThat(responses.map { it.statusCode() }).containsExactlyInAnyOrder(500, 503)
        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(chain.tokenBalance(sourceWallet.address)).isEqualTo(sourceBefore - RAW_AMOUNT)
        assertThat(chain.tokenBalance(destinationWallet.address)).isEqualTo(destinationBefore + RAW_AMOUNT)
        val committed =
            checkNotNull(vendorTransactions.transactionByExternalTransactionId(CONCURRENT_EXTERNAL_TRANSACTION_ID))
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", CONCURRENT_EXTERNAL_TRANSACTION_ID))
            .containsEntry("vndr_tx_id", null)
            .containsEntry("sbmt_stcd", "REQUESTED")
        jdbc.update(
            "UPDATE bcm_sbmt_l SET claim_exp_dttm = '00000000000000' WHERE ext_tx_id = ?",
            CONCURRENT_EXTERNAL_TRANSACTION_ID,
        )

        val recoveredTransactionId = submitInternalTransfer(CONCURRENT_EXTERNAL_TRANSACTION_ID)

        assertThat(recoveredTransactionId).isEqualTo(committed.transactionId)
        assertThat(chain.transactionCount(sourceWallet.address)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", CONCURRENT_EXTERNAL_TRANSACTION_ID))
            .containsEntry("vndr_tx_id", committed.transactionId)
            .containsEntry("sbmt_stcd", "SUBMITTED")
    }

    private fun submitInternalTransfer(
        externalTransactionId: String = EXTERNAL_TRANSACTION_ID,
        amount: String = DISPLAY_AMOUNT,
    ): String {
        val response = requestInternalTransfer(externalTransactionId, amount)
        assertThat(response.statusCode()).isEqualTo(202)
        return objectMapper
            .readTree(response.body())
            .path("data")
            .path("txId")
            .asString()
    }

    private fun requestInternalTransfer(
        externalTransactionId: String,
        amount: String = DISPLAY_AMOUNT,
    ): HttpResponse<ByteArray> {
        val payload =
            objectMapper.writeValueAsBytes(
                mapOf(
                    "externalTxId" to externalTransactionId,
                    "from" to mapOf("type" to "ACCOUNT", "accountId" to SOURCE_ACCOUNT_ID),
                    "to" to mapOf("type" to "ACCOUNT", "accountId" to DESTINATION_ACCOUNT_ID),
                    "network" to NETWORK,
                    "symbol" to SYMBOL,
                    "amount" to amount,
                    "note" to "local vertical integration",
                ),
            )
        return http.send(
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$bcmPort/transactions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
    }

    private fun advance(transactionId: String) {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/transactions/$transactionId/advance"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun createExternalDeposit(destinationVaultId: String): String {
        val payload =
            objectMapper.writeValueAsBytes(
                mapOf(
                    "externalTxId" to DEPOSIT_EXTERNAL_TRANSACTION_ID,
                    "assetId" to TOKEN_ASSET_ID,
                    "destinationVaultId" to destinationVaultId,
                    "amount" to DISPLAY_AMOUNT,
                ),
            )
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/deposits"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
        return objectMapper.readTree(response.body()).path("id").asString()
    }

    private fun injectNextTransactionResponseLoss() {
        injectNextTransactionResponse(status = 400, afterCommit = true)
    }

    private fun injectNextTransactionResponse(
        status: Int,
        afterCommit: Boolean,
        delayMillis: Long = 0,
    ) {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/faults/transactions/next-response"))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"status":$status,"afterCommit":$afterCommit,"delayMillis":$delayMillis}""",
                        ),
                    ).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun redeliverLastWebhook() {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/webhooks/notifications/redeliver-last"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun injectNextWebhookDeliveryFailure() {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/faults/webhooks/next-delivery"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun injectNextTerminalVendorState(status: String) {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/faults/transactions/next-terminal-state"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"status":"$status"}"""))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun injectNextPendingVendorState(status: String) {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/faults/transactions/next-pending-state"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"status":"$status"}"""))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun setNativeBalance(
        address: String,
        balanceWei: BigInteger,
    ) {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/faults/chain/native-balance"))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"address":"$address","balanceWei":"$balanceWei"}""",
                        ),
                    ).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun injectNextTransactionNonce(nonce: BigInteger) {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/faults/chain/next-transaction-nonce"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"nonce":"$nonce"}"""))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun processAllWebhooks() {
        processWebhooks(expected = 4)
    }

    private fun processWebhooks(expected: Int) {
        val outcomes = generateSequence { webhookProcessor.processNext() }.take(10).toList()
        assertThat(outcomes.filterNot { it is WebhookDecisionOutcome.NoWork }).hasSize(expected)
        assertThat(outcomes.last()).isInstanceOf(WebhookDecisionOutcome.NoWork::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_whk_l WHERE prcs_stcd = 'P'", Long::class.java)).isZero()
    }

    private fun kafkaConsumer(): KafkaConsumer<String, String> =
        KafkaConsumer(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "local-fireblocks-e2e-${UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            },
        )

    private fun subscribeForNewRecords(
        consumer: KafkaConsumer<String, String>,
        topic: String = INTERNAL_TOPIC,
    ) {
        consumer.subscribe(listOf(topic))
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

    private fun insertAssetMapping() {
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('local-evm', ?, 31337, 'Local EVM', 'Y', 'N', '20260820000000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            NETWORK,
        )
        jdbc.update(
            """
            INSERT INTO bcm_vndr_ast_m
              (ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, ?, '20260820000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            NETWORK,
            SYMBOL,
            TOKEN_ASSET_ID,
            chain.manifest.tokenContractAddress,
        )
    }

    private fun insertDepositAddress(address: String) {
        jdbc.update(
            """
            INSERT INTO bcm_addr_m
              (acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, ?, '20260820000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            DEPOSIT_ACCOUNT_ID,
            NETWORK,
            SYMBOL,
            address,
        )
    }

    private fun account(
        accountId: String,
        vaultId: String,
    ) = Account(
        accountId = accountId,
        accountType = AccountType.SYSTEM,
        ref = accountId,
        vendorVaultId = vaultId,
        registeredAt = "20260820000000",
    )

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_tx_l")
        jdbc.update("DELETE FROM bcm_sbmt_l")
        jdbc.update("DELETE FROM bcm_whk_l")
        jdbc.update("DELETE FROM bcm_addr_m")
        jdbc.update("DELETE FROM bcm_vndr_ast_m")
        jdbc.update("DELETE FROM bcm_blkc_m")
        jdbc.update("DELETE FROM bcm_acnt_m")
    }

    companion object {
        private const val NETWORK = "ETHEREUM"
        private const val SYMBOL = "TUSD"
        private const val TOKEN_ASSET_ID = "TUSD_LOCAL"
        private const val LOCAL_WEBHOOK_ID = "local-webhook"
        private const val INTERNAL_TOPIC = "internal-events"
        private const val DEPOSIT_TOPIC = "deposit-events"
        private const val SOURCE_ACCOUNT_ID = "acct-local-source"
        private const val DESTINATION_ACCOUNT_ID = "acct-local-destination"
        private const val EXTERNAL_TRANSACTION_ID = "internal-local-e2e-1"
        private const val GASLESS_WITHDRAWAL_EXTERNAL_TRANSACTION_ID = "withdrawal-local-gasless-e2e-1"
        private const val DEPOSIT_ACCOUNT_ID = "acct-local-deposit"
        private const val DEPOSIT_EXTERNAL_TRANSACTION_ID = "deposit-local-e2e-1"
        private const val LOST_RESPONSE_EXTERNAL_TRANSACTION_ID = "internal-local-lost-response-e2e-1"
        private const val DUPLICATE_WEBHOOK_EXTERNAL_TRANSACTION_ID = "internal-local-duplicate-webhook-e2e-1"
        private const val RATE_LIMIT_EXTERNAL_TRANSACTION_ID = "internal-local-rate-limit-e2e-1"
        private const val COMMITTED_SERVER_ERROR_EXTERNAL_TRANSACTION_ID = "internal-local-committed-500-e2e-1"
        private const val REVERT_EXTERNAL_TRANSACTION_ID = "internal-local-revert-e2e-1"
        private const val LOST_WEBHOOK_EXTERNAL_TRANSACTION_ID = "internal-local-lost-webhook-e2e-1"
        private const val TIMEOUT_EXTERNAL_TRANSACTION_ID = "internal-local-timeout-e2e-1"
        private const val LONG_PENDING_EXTERNAL_TRANSACTION_ID = "internal-local-long-pending-e2e-1"
        private const val INSUFFICIENT_GAS_EXTERNAL_TRANSACTION_ID = "internal-local-insufficient-gas-e2e-1"
        private const val NONCE_PRIMER_EXTERNAL_TRANSACTION_ID = "internal-local-nonce-primer-e2e-1"
        private const val STALE_NONCE_EXTERNAL_TRANSACTION_ID = "internal-local-stale-nonce-e2e-1"
        private const val CONCURRENT_EXTERNAL_TRANSACTION_ID = "internal-local-concurrent-e2e-1"
        private const val DISPLAY_AMOUNT = "2.5"
        private val RAW_AMOUNT = BigInteger("2500000")
        private val http: HttpClient = HttpClient.newHttpClient()
        private val runtimeDirectory: Path = Files.createTempDirectory("bcm-api-local-fireblocks-")
        private val bcmPort: Int = availablePort()
        private val stubPort: Int = availablePort()
        private val privateKeyFile: Path = writePrivateKey(runtimeDirectory.resolve("fireblocks-api.key"))
        private val chain =
            LocalChainEnvironment.start(
                LocalChainConfiguration(
                    seed = "bcm-api-local-fireblocks-internal-e2e-seed",
                    runtimeDirectory = runtimeDirectory,
                    contractArtifactDirectory = Path.of(requireNotNull(System.getProperty("bcm.contract-artifacts"))),
                ),
            )
        private val stub =
            SpringApplicationBuilder(TestSupportApplication::class.java)
                .run(
                    "--server.address=127.0.0.1",
                    "--server.port=$stubPort",
                    "--management.server.address=127.0.0.1",
                    "--management.server.port=0",
                    "--spring.autoconfigure.exclude=" +
                        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
                        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                    "--bcm.test-support.vendor-mode=STUB",
                    "--bcm.test-support.chain-mode=LOCAL",
                    "--bcm.test-support.server-address=127.0.0.1",
                    "--bcm.test-support.management-address=127.0.0.1",
                    "--bcm.test-support.fireblocks-base-url=http://127.0.0.1:$stubPort",
                    "--bcm.test-support.fireblocks-api-key=bcm-local-stub",
                    "--bcm.test-support.webhook-jwks-url=http://127.0.0.1:$stubPort/.well-known/jwks.json",
                    "--bcm.test-support.evm-rpc-url=${chain.rpcUrl}",
                    "--bcm.test-support.evm-chain-id=31337",
                    "--bcm.test-support.local-chain-manifest-file=${runtimeDirectory.resolve("manifest.json")}",
                    "--bcm.test-support.local-chain-key-file=${runtimeDirectory.resolve("evm-keys.json")}",
                    "--bcm.test-support.webhook-delivery-url=http://127.0.0.1:$bcmPort/webhook",
                )

        @JvmStatic
        @DynamicPropertySource
        fun localEnvironmentProperties(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { bcmPort }
            registry.add("management.server.address") { "127.0.0.1" }
            registry.add("management.server.port") { "0" }
            registry.add("bcm.fireblocks.base-url") { "http://127.0.0.1:$stubPort" }
            registry.add("bcm.fireblocks.api-key") { "bcm-local-stub" }
            registry.add("bcm.fireblocks.private-key-file") { privateKeyFile.toString() }
            registry.add("bcm.fireblocks.webhook-jwks-url") {
                "http://127.0.0.1:$stubPort/.well-known/jwks.json"
            }
            registry.add("bcm.fireblocks.webhook-jwks-refresh-cooldown-millis") { "0" }
            registry.add("bcm.fireblocks.read-timeout-millis") { "500" }
        }

        @JvmStatic
        @AfterAll
        fun closeEnvironment() {
            stub.close()
            chain.close()
            runtimeDirectory.toFile().deleteRecursively()
        }

        private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

        private fun writePrivateKey(path: Path): Path {
            val key =
                KeyPairGenerator
                    .getInstance("RSA")
                    .apply { initialize(2048) }
                    .generateKeyPair()
                    .private.encoded
            val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(key)
            Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----")
            return path
        }
    }
}
