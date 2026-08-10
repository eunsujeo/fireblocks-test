package com.whatto.bcm.infra.client.fireblocks

import com.sun.net.httpserver.HttpServer
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.infra.client.fireblocks.fixture.TestRsaKeyFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FireblocksJwksProviderTest {
    private val objectMapper = ObjectMapper()
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `JWKS는 캐시하고 낯선 kid가 오면 한 번 다시 조회한다`() {
        val first = TestRsaKeyFixture.generateKeyPair()
        val second = TestRsaKeyFixture.generateKeyPair()
        val responseBody = AtomicReference(jwks("key-1", first))
        val requestCount = AtomicInteger()
        val url = startJwksServer(responseBody, requestCount)
        val provider =
            FireblocksJwksProvider(
                FireblocksProperties(
                    webhookJwksUrl = url,
                    webhookJwksTimeoutMillis = 1_000,
                    webhookJwksRefreshCooldownMillis = 0,
                ),
                objectMapper,
            )

        assertThat(provider.findByKeyId("key-1")?.encoded).isEqualTo(first.public.encoded)
        assertThat(provider.findByKeyId("key-1")?.encoded).isEqualTo(first.public.encoded)
        assertThat(requestCount).hasValue(1)

        responseBody.set(jwks("key-2", second))
        assertThat(provider.findByKeyId("key-2")).isNull()
        awaitRequestCount(requestCount, 2)
        assertThat(awaitKey(provider, "key-2")?.encoded).isEqualTo(second.public.encoded)
        assertThat(requestCount).hasValue(2)
    }

    @Test
    fun `JWKS 조회 실패는 벤더 시스템 오류이고 cooldown 동안 외부 호출을 반복하지 않는다`() {
        val requestCount = AtomicInteger()
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/jwks") { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        created.start()
        server = created
        val provider =
            FireblocksJwksProvider(
                FireblocksProperties(
                    webhookJwksUrl = "http://127.0.0.1:${created.address.port}/jwks",
                    webhookJwksTimeoutMillis = 1_000,
                    webhookJwksRefreshCooldownMillis = 30_000,
                ),
                objectMapper,
            )

        assertThatThrownBy { provider.findByKeyId("key-1") }
            .isInstanceOf(VendorApiException::class.java)
        assertThatThrownBy { provider.findByKeyId("key-1") }
            .isInstanceOf(VendorApiException::class.java)
        assertThat(requestCount).hasValue(1)
    }

    private fun awaitRequestCount(
        requestCount: AtomicInteger,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + 1_000_000_000
        while (requestCount.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertThat(requestCount).hasValue(expected)
    }

    private fun awaitKey(
        provider: FireblocksJwksProvider,
        keyId: String,
    ): PublicKey? {
        val deadline = System.nanoTime() + 1_000_000_000
        var key = provider.findByKeyId(keyId)
        while (key == null && System.nanoTime() < deadline) {
            Thread.sleep(10)
            key = provider.findByKeyId(keyId)
        }
        return key
    }

    private fun startJwksServer(
        responseBody: AtomicReference<ByteArray>,
        requestCount: AtomicInteger,
    ): String {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/jwks") { exchange ->
            requestCount.incrementAndGet()
            val body = responseBody.get()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}/jwks"
    }

    private fun jwks(
        keyId: String,
        keyPair: KeyPair,
    ): ByteArray {
        val publicKey = keyPair.public as RSAPublicKey
        return objectMapper.writeValueAsBytes(
            mapOf(
                "keys" to
                    listOf(
                        mapOf(
                            "kty" to "RSA",
                            "kid" to keyId,
                            "alg" to "RS512",
                            "n" to base64Url(publicKey.modulus.toByteArray().unsigned()),
                            "e" to base64Url(publicKey.publicExponent.toByteArray().unsigned()),
                        ),
                    ),
            ),
        )
    }

    private fun ByteArray.unsigned(): ByteArray = dropWhile { it == 0.toByte() }.toByteArray()

    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
