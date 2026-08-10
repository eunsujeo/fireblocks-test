package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.webhook.WebhookSignatureVerifier
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/** Fireblocks 웹훅 v2의 RS512 detached JWS 검증 — payload 재직렬화 없이 수신 byte[] 를 쓴다. */
@Component
internal class FireblocksWebhookSignatureVerifier(
    private val keyProvider: WebhookPublicKeyProvider,
    private val objectMapper: ObjectMapper,
) : WebhookSignatureVerifier {
    override fun verify(
        signature: String,
        payload: ByteArray,
    ): Boolean {
        val parts = signature.split('.')
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isNotEmpty() || parts[2].isBlank()) return false

        val header =
            runCatching { objectMapper.readTree(BASE64_URL_DECODER.decode(parts[0])) }
                .getOrNull() ?: return false
        if (header.path("alg").asString() != "RS512") return false
        if (header.path("b64").isBoolean && !header.path("b64").asBoolean()) return false
        if (header.path("crit").let { !it.isMissingNode && (!it.isArray || !it.isEmpty) }) return false
        val keyId = header.path("kid").asString().takeIf(String::isNotBlank) ?: return false
        val publicKey = keyProvider.findByKeyId(keyId) ?: return false

        return runCatching {
            val encodedPayload = BASE64_URL_ENCODER.encodeToString(payload)
            Signature.getInstance("SHA512withRSA").run {
                initVerify(publicKey)
                update("${parts[0]}.$encodedPayload".toByteArray(StandardCharsets.US_ASCII))
                verify(BASE64_URL_DECODER.decode(parts[2]))
            }
        }.getOrDefault(false)
    }

    companion object {
        private val BASE64_URL_DECODER = Base64.getUrlDecoder()
        private val BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
    }
}

internal fun interface WebhookPublicKeyProvider {
    fun findByKeyId(keyId: String): PublicKey?
}
