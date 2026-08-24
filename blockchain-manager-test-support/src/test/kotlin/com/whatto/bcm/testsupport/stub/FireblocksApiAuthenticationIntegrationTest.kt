package com.whatto.bcm.testsupport.stub

import com.whatto.bcm.testsupport.TestSupportApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Clock
import java.util.Base64
import java.util.UUID

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
        "bcm.test-support.api-authentication-mode=STRICT",
    ],
)
class FireblocksApiAuthenticationIntegrationTest {
    @LocalServerPort
    var serverPort: Int = 0

    @Autowired
    lateinit var clock: Clock

    @Test
    fun `STRICT 인증은 RS256 요청을 허용하고 같은 nonce 재사용을 거절한다`() {
        val uri = "/v1/blockchains?pageSize=500"
        val authorization = authorization(uri, ByteArray(0))

        val first = get(uri, authorization)
        val replayed = get(uri, authorization)

        assertThat(first.statusCode()).isEqualTo(200)
        assertThat(replayed.statusCode()).isEqualTo(401)
    }

    @Test
    fun `STRICT 인증은 누락된 헤더와 원문 bodyHash 불일치를 거절한다`() {
        val uri = "/v1/vault/accounts"
        val body = "{\"name\":\"CUSTOMER:STRICT\"}".toByteArray()
        val missingHeaders =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint("/v1/blockchains?pageSize=500")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        val mismatchedBody = post(uri, body, authorization(uri, "{}".toByteArray()))

        assertThat(missingHeaders.statusCode()).isEqualTo(401)
        assertThat(mismatchedBody.statusCode()).isEqualTo(401)
    }

    @Test
    fun `STRICT 인증은 sub와 요청 uri 불일치를 거절한다`() {
        val uri = "/v1/blockchains?pageSize=500"

        val mismatchedSubject = get(uri, authorization(uri, ByteArray(0), subject = "another-api-key"))
        val mismatchedUri = get(uri, authorization(uri, ByteArray(0), signedUri = "/v1/blockchains?pageSize=100"))

        assertThat(mismatchedSubject.statusCode()).isEqualTo(401)
        assertThat(mismatchedUri.statusCode()).isEqualTo(401)
    }

    @Test
    fun `STRICT 인증은 미래 발급과 만료 및 최대 수명 초과 토큰을 거절한다`() {
        val uri = "/v1/blockchains?pageSize=500"
        val now = clock.instant().epochSecond

        val issuedInFuture =
            get(uri, authorization(uri, ByteArray(0), issuedAt = now + 6, expiresAt = now + 20))
        val expired =
            get(uri, authorization(uri, ByteArray(0), issuedAt = now - 20, expiresAt = now - 6))
        val overlongLifetime =
            get(uri, authorization(uri, ByteArray(0), issuedAt = now, expiresAt = now + 30))

        assertThat(issuedInFuture.statusCode()).isEqualTo(401)
        assertThat(expired.statusCode()).isEqualTo(401)
        assertThat(overlongLifetime.statusCode()).isEqualTo(401)
    }

    private fun get(
        uri: String,
        authorization: String,
    ): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(endpoint(uri))
                .header("X-API-Key", API_KEY)
                .header("Authorization", authorization)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(
        uri: String,
        body: ByteArray,
        authorization: String,
    ): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(endpoint(uri))
                .header("X-API-Key", API_KEY)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun authorization(
        uri: String,
        body: ByteArray,
        subject: String = API_KEY,
        signedUri: String = uri,
        issuedAt: Long = clock.instant().epochSecond,
        expiresAt: Long = issuedAt + 20,
    ): String {
        val header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".toByteArray())
        val payload =
            base64Url(
                ObjectMapper().writeValueAsBytes(
                    mapOf(
                        "uri" to signedUri,
                        "nonce" to UUID.randomUUID().toString(),
                        "iat" to issuedAt,
                        "exp" to expiresAt,
                        "sub" to subject,
                        "bodyHash" to sha256Hex(body),
                    ),
                ),
            )
        val signingInput = "$header.$payload"
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(keyPair.private)
                update(signingInput.toByteArray(StandardCharsets.US_ASCII))
                sign()
            }
        return "Bearer $signingInput.${base64Url(signature)}"
    }

    private fun endpoint(uri: String): URI = URI.create("http://127.0.0.1:$serverPort$uri")

    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val API_KEY = "bcm-local-stub"
        private val runtimeDirectory = Files.createTempDirectory("bcm-strict-api-auth-")
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val publicKeyFile =
            runtimeDirectory.resolve("api-public.pem").also { path ->
                val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(keyPair.public.encoded)
                Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----")
            }

        @JvmStatic
        @DynamicPropertySource
        fun strictAuthenticationProperties(registry: DynamicPropertyRegistry) {
            registry.add("bcm.test-support.api-public-key-file") { publicKeyFile.toString() }
        }
    }
}
