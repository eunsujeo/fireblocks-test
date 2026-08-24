package com.whatto.bcm.testsupport.stub

import com.sun.net.httpserver.HttpServer
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.infra.client.fireblocks.FireblocksClient
import com.whatto.bcm.infra.client.fireblocks.FireblocksJwtSigner
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import com.whatto.bcm.infra.client.fireblocks.PooledFireblocksRestClientFactory
import com.whatto.bcm.testsupport.TestSupportApplication
import com.whatto.bcm.testsupport.chain.LocalChainConfiguration
import com.whatto.bcm.testsupport.chain.LocalChainEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Clock
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@SpringBootTest(
    classes = [TestSupportApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "bcm.test-support.vendor-mode=STUB",
        "bcm.test-support.chain-mode=LOCAL",
        "bcm.test-support.server-address=127.0.0.1",
        "bcm.test-support.management-address=127.0.0.1",
        "bcm.test-support.fireblocks-base-url=http://127.0.0.1:18080",
        "bcm.test-support.fireblocks-api-key=bcm-local-stub",
        "bcm.test-support.webhook-jwks-url=http://127.0.0.1:18080/.well-known/jwks.json",
        "bcm.test-support.evm-chain-id=31337",
    ],
)
class FireblocksWebhookDeliveryIntegrationTest {
    @LocalServerPort
    var serverPort: Int = 0

    @Test
    fun `거래 상태 Webhook은 RS512 서명으로 전달되고 실패분은 resend_failed로 회수된다`() {
        receiver.clear()
        val client = client()
        client.activateWebhook(LOCAL_WEBHOOK_ID)
        val source = client.createVault("CUSTOMER:WEBHOOK-001", "createVault:CUSTOMER:WEBHOOK-001")
        val destination = client.createVault("CUSTOMER:WEBHOOK-002", "createVault:CUSTOMER:WEBHOOK-002")
        client.createDepositAddress(source.vaultId, TOKEN_ASSET_ID, "createWallet:1:TUSD_LOCAL")
        client.createDepositAddress(destination.vaultId, TOKEN_ASSET_ID, "createWallet:2:TUSD_LOCAL")
        val first = submit(client, source.vaultId, destination.vaultId, "webhook-transfer-1")

        advance(first.transactionId)
        advance(first.transactionId)

        assertThat(receiver.eventTypes()).containsExactly(
            "transaction.created",
            "transaction.status.updated",
            "transaction.status.updated",
            "transaction.network_records.processing_completed",
        )
        assertThat(receiver.received).allSatisfy { notification ->
            assertThat(notification.signature).contains("..")
            assertThat(
                ObjectMapper()
                    .readTree(notification.body)
                    .required("data")
                    .required("id")
                    .asString(),
            ).isEqualTo(first.transactionId)
        }

        receiver.fail.set(true)
        val failed = submit(client, source.vaultId, destination.vaultId, "webhook-transfer-failed-delivery")
        receiver.fail.set(false)
        val resent = client.resendFailedWebhookNotifications(LOCAL_WEBHOOK_ID)

        assertThat(resent.scheduledNotificationCount).isEqualTo(1)
        assertThat(receiver.received.last().eventType()).isEqualTo("transaction.created")
        assertThat(
            ObjectMapper()
                .readTree(receiver.received.last().body)
                .required("data")
                .required("id")
                .asString(),
        ).isEqualTo(failed.transactionId)
    }

    private fun submit(
        client: FireblocksClient,
        sourceVaultId: String,
        destinationVaultId: String,
        externalTransactionId: String,
    ): VendorTransactionSubmission.Accepted =
        client.submitTransaction(
            VendorTransactionRequest(
                externalTransactionId = externalTransactionId,
                vendorAssetId = TOKEN_ASSET_ID,
                sourceVaultId = sourceVaultId,
                destination = VendorTransactionDestination.Account(destinationVaultId),
                amount = "1",
                note = null,
                travelRuleMessage = null,
                useGasless = false,
            ),
        ) as VendorTransactionSubmission.Accepted

    private fun advance(transactionId: String) {
        val response =
            java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create("http://127.0.0.1:$serverPort/__stub/transactions/$transactionId/advance"))
                    .POST(
                        java.net.http.HttpRequest.BodyPublishers
                            .noBody(),
                    ).build(),
                java.net.http.HttpResponse.BodyHandlers
                    .discarding(),
            )
        check(response.statusCode() == 200)
    }

    private fun client(): FireblocksClient {
        val privateKeyPem = testPrivateKeyPem()
        val properties =
            FireblocksProperties(
                baseUrl = "http://127.0.0.1:$serverPort",
                apiKey = "bcm-local-stub",
                privateKeyPem = privateKeyPem,
            )
        return FireblocksClient(
            restClientBuilder = RestClient.builder(),
            properties = properties,
            signer = FireblocksJwtSigner(properties.apiKey, privateKeyPem, Clock.systemUTC()),
            metrics = NoOpOperationalMetricsPort,
            restClientFactory = PooledFireblocksRestClientFactory(),
        )
    }

    private fun testPrivateKeyPem(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }

    private fun tools.jackson.databind.JsonNode.required(field: String) =
        checkNotNull(get(field)) { "test response field is missing: $field" }

    companion object {
        private const val LOCAL_WEBHOOK_ID = "local-webhook"
        private const val TOKEN_ASSET_ID = "TUSD_LOCAL"
        private val runtimeDirectory: Path = Files.createTempDirectory("bcm-webhook-delivery-")
        private val receiver = WebhookReceiver.start()
        private val chain =
            LocalChainEnvironment.start(
                LocalChainConfiguration(
                    seed = "fireblocks-webhook-delivery-contract-seed",
                    runtimeDirectory = runtimeDirectory,
                    contractArtifactDirectory = Path.of(requireNotNull(System.getProperty("bcm.contract-artifacts"))),
                ),
            )

        @JvmStatic
        @DynamicPropertySource
        fun localEnvironmentProperties(registry: DynamicPropertyRegistry) {
            registry.add("bcm.test-support.evm-rpc-url", chain::rpcUrl)
            registry.add("bcm.test-support.local-chain-manifest-file") {
                runtimeDirectory.resolve("manifest.json").toString()
            }
            registry.add("bcm.test-support.local-chain-key-file") {
                runtimeDirectory.resolve("evm-keys.json").toString()
            }
            registry.add("bcm.test-support.webhook-delivery-url", receiver::url)
        }

        @JvmStatic
        @AfterAll
        fun closeEnvironment() {
            chain.close()
            receiver.close()
            runtimeDirectory.toFile().deleteRecursively()
        }
    }
}

private data class ReceivedWebhook(
    val signature: String?,
    val body: ByteArray,
) {
    fun eventType(): String = ObjectMapper().readTree(body).required("eventType").asString()

    private fun tools.jackson.databind.JsonNode.required(field: String) = checkNotNull(get(field))
}

private class WebhookReceiver private constructor(
    private val server: HttpServer,
) : AutoCloseable {
    val fail = AtomicBoolean(false)
    val received = CopyOnWriteArrayList<ReceivedWebhook>()
    val url = "http://127.0.0.1:${server.address.port}/webhooks/fireblocks"

    fun clear() {
        received.clear()
        fail.set(false)
    }

    fun eventTypes(): List<String> = received.map(ReceivedWebhook::eventType)

    override fun close() = server.stop(0)

    companion object {
        fun start(): WebhookReceiver {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val receiver = WebhookReceiver(server)
            server.createContext("/webhooks/fireblocks") { exchange ->
                val body = exchange.requestBody.readAllBytes()
                val signature = exchange.requestHeaders.getFirst("Fireblocks-Webhook-Signature")
                if (!receiver.fail.get()) receiver.received += ReceivedWebhook(signature, body)
                val status = if (receiver.fail.get()) 500 else 200
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            server.start()
            return receiver
        }
    }
}
