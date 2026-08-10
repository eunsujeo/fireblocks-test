package com.whatto.bcm.app.api.webhook

import com.sun.net.httpserver.HttpServer
import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.domain.webhook.WebhookSignatureVerifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
class WebhookIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var signatureVerifier: WebhookSignatureVerifier

    @BeforeEach
    @AfterEach
    fun clearInbox() {
        jdbc.update("DELETE FROM bcm_whk_l WHERE noti_id = ?", NOTIFICATION_ID)
    }

    @Test
    fun `실물 payload는 원문·해시·서명과 함께 적재되고 저장 원문으로 재검증된다`() {
        val payload = realPayload()
        val signature = sign(payload, signingKey)

        mockMvc
            .perform(
                post("/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookController.SIGNATURE_HEADER, signature)
                    .content(payload),
            ).andExpect(status().isOk)

        val row = jdbc.queryForMap("SELECT * FROM bcm_whk_l WHERE noti_id = ?", NOTIFICATION_ID)
        val storedPayload = row.getValue("payload") as String
        assertThat(storedPayload.toByteArray(StandardCharsets.UTF_8)).isEqualTo(payload)
        assertThat(row["payload_hash"]).isEqualTo(sha256Hex(payload))
        assertThat(row["sign_vl"]).isEqualTo(signature)
        assertThat(row["evnt_typ"]).isEqualTo("transaction.created")
        assertThat(row["vndr_tx_id"]).isEqualTo("f3339e5d-428e-4add-8018-631b972f3195")
        assertThat(row["prcs_stcd"]).isEqualTo("P")
        assertThat(row["rtry_cnt"]).isEqualTo(0)
        assertThat(row["err_msg"]).isNull()
        assertThat(row["prcs_dttm"]).isNull()
        assertThat(row.values).contains("SYSTEM", "9999")
        assertThat(signatureVerifier.verify(row.getValue("sign_vl") as String, storedPayload.toByteArray())).isTrue()
    }

    @Test
    fun `같은 noti_id 재전달은 두 번 모두 200이고 한 행만 남는다`() {
        val payload = realPayload()
        val signature = sign(payload, signingKey)

        repeat(2) {
            mockMvc
                .perform(
                    post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookController.SIGNATURE_HEADER, signature)
                        .content(payload),
                ).andExpect(status().isOk)
        }

        val count = jdbc.queryForObject("SELECT count(*) FROM bcm_whk_l WHERE noti_id = ?", Long::class.java, NOTIFICATION_ID)
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `같은 noti_id 동시 수신도 둘 다 200이고 PK 경합 뒤 한 행만 남는다`() {
        val payload = realPayload()
        val signature = sign(payload, signingKey)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("webhook-concurrent-test-", 0).factory())

        val responses =
            try {
                val futures =
                    List(2) {
                        executor.submit<Int> {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            mockMvc
                                .perform(
                                    post("/webhook")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header(WebhookController.SIGNATURE_HEADER, signature)
                                        .content(payload),
                                ).andReturn()
                                .response
                                .status
                        }
                    }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
                start.countDown()
                futures.map { it.get(5, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertThat(responses).containsOnly(200)
        val count = jdbc.queryForObject("SELECT count(*) FROM bcm_whk_l WHERE noti_id = ?", Long::class.java, NOTIFICATION_ID)
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `서명 없음과 다른 키의 서명은 401이고 인박스에 넣지 않는다`() {
        val payload = realPayload()
        val fakeSignature = sign(payload, otherKey, keyId = "unknown-key")

        mockMvc
            .perform(post("/webhook").contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isUnauthorized)
        mockMvc
            .perform(
                post("/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookController.SIGNATURE_HEADER, fakeSignature)
                    .content(payload),
            ).andExpect(status().isUnauthorized)

        val count = jdbc.queryForObject("SELECT count(*) FROM bcm_whk_l WHERE noti_id = ?", Long::class.java, NOTIFICATION_ID)
        assertThat(count).isZero()
    }

    private fun realPayload(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/payload/transaction.created.json"))
            .use { it.readAllBytes() }

    companion object {
        private const val KEY_ID = "phase3-test-key"
        private const val NOTIFICATION_ID = "434e8c65-9489-4621-830f-eebe03cb2b3d"
        private val objectMapper = ObjectMapper()
        private val signingKey: KeyPair = generateKeyPair()
        private val otherKey: KeyPair = generateKeyPair()
        private val jwksServer: HttpServer = startJwksServer()

        @JvmStatic
        @DynamicPropertySource
        fun webhookProperties(registry: DynamicPropertyRegistry) {
            registry.add("bcm.fireblocks.webhook-jwks-url") {
                "http://127.0.0.1:${jwksServer.address.port}/jwks"
            }
            registry.add("bcm.fireblocks.webhook-jwks-timeout-millis") { "1000" }
        }

        private fun startJwksServer(): HttpServer {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val response = jwks(signingKey)
            server.createContext("/jwks") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            return server
        }

        private fun jwks(keyPair: KeyPair): ByteArray {
            val publicKey = keyPair.public as RSAPublicKey
            return objectMapper.writeValueAsBytes(
                mapOf(
                    "keys" to
                        listOf(
                            mapOf(
                                "kty" to "RSA",
                                "kid" to KEY_ID,
                                "alg" to "RS512",
                                "n" to base64Url(publicKey.modulus.toUnsignedByteArray()),
                                "e" to base64Url(publicKey.publicExponent.toUnsignedByteArray()),
                            ),
                        ),
                ),
            )
        }

        private fun sign(
            body: ByteArray,
            keyPair: KeyPair,
            keyId: String = KEY_ID,
        ): String {
            val protected = base64Url(objectMapper.writeValueAsBytes(mapOf("alg" to "RS512", "kid" to keyId)))
            val encodedPayload = base64Url(body)
            val signature =
                Signature.getInstance("SHA512withRSA").run {
                    initSign(keyPair.private)
                    update("$protected.$encodedPayload".toByteArray(StandardCharsets.US_ASCII))
                    sign()
                }
            return "$protected..${base64Url(signature)}"
        }

        private fun sha256Hex(value: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value)
                .joinToString("") { "%02x".format(it) }

        private fun generateKeyPair(): KeyPair =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()

        private fun BigInteger.toUnsignedByteArray(): ByteArray = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()

        private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}
