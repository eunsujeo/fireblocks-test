package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.infra.client.fireblocks.fixture.TestRsaKeyFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.Signature
import java.util.Base64

class FireblocksWebhookSignatureVerifierTest {
    private val objectMapper = ObjectMapper()
    private val keyPair = TestRsaKeyFixture.generateKeyPair()
    private val keyProvider = WebhookPublicKeyProvider { kid -> keyPair.public.takeIf { kid == KEY_ID } }
    private val verifier = FireblocksWebhookSignatureVerifier(keyProvider, objectMapper)

    @Test
    fun `RS512 detached JWS 는 수신 본문 원문으로 검증한다`() {
        val body = """{"id":"notification-1","eventType":"transaction.created"}""".toByteArray()
        val signature = sign(body, keyPair)

        assertThat(verifier.verify(signature, body)).isTrue()
        assertThat(verifier.verify(signature, body + '\n'.code.toByte())).isFalse()
    }

    @Test
    fun `RS512가 아니거나 kid가 없는 서명은 거절한다`() {
        val body = "{}".toByteArray()
        val wrongAlgorithm = sign(body, keyPair, algorithm = "RS256", jcaAlgorithm = "SHA256withRSA")
        val noKeyId = sign(body, keyPair, keyId = null)
        val unknownCriticalHeader = sign(body, keyPair, critical = listOf("unknown"))

        assertThat(verifier.verify(wrongAlgorithm, body)).isFalse()
        assertThat(verifier.verify(noKeyId, body)).isFalse()
        assertThat(verifier.verify(unknownCriticalHeader, body)).isFalse()
    }

    @Test
    fun `깨진 detached JWS 는 예외를 노출하지 않고 거절한다`() {
        assertThat(verifier.verify("not-a-jws", "{}".toByteArray())).isFalse()
        assertThat(verifier.verify("a.payload.c", "{}".toByteArray())).isFalse()
    }

    private fun sign(
        body: ByteArray,
        keyPair: KeyPair,
        algorithm: String = "RS512",
        jcaAlgorithm: String = "SHA512withRSA",
        keyId: String? = KEY_ID,
        critical: List<String>? = null,
    ): String {
        val header =
            buildMap<String, Any> {
                put("alg", algorithm)
                keyId?.let { put("kid", it) }
                critical?.let { put("crit", it) }
            }
        val protected = base64Url(objectMapper.writeValueAsBytes(header))
        val encodedPayload = base64Url(body)
        val signature =
            Signature.getInstance(jcaAlgorithm).run {
                initSign(keyPair.private)
                update("$protected.$encodedPayload".toByteArray(StandardCharsets.US_ASCII))
                sign()
            }
        return "$protected..${base64Url(signature)}"
    }

    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    companion object {
        private const val KEY_ID = "webhook-test-key"
    }
}
