package com.whatto.bcm.infra.client.fireblocks

import tools.jackson.databind.ObjectMapper
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64
import java.util.UUID

/**
 * 벤더 API 요청 서명 — RS256 JWT, 클레임 uri/nonce/iat/exp/sub/bodyHash (exp 는 iat+30s 미만 요구).
 * 근거: Fireblocks 공식 문서 "Signing a request (JWT Structure)" (2026-08-05 확인).
 * 키 파싱은 lazy — 시크릿 미주입 환경(로컬 부트스트랩·테스트)에서도 컨텍스트는 뜨고, 실호출 시점에 설정 오류로 실패한다.
 */
class FireblocksJwtSigner(
    private val apiKey: String,
    privateKeyPem: String,
    private val clock: Clock,
) {
    private val pem = privateKeyPem
    private val objectMapper = ObjectMapper()
    private val privateKey: PrivateKey by lazy { parsePrivateKey(pem) }

    fun sign(
        uri: String,
        body: ByteArray,
    ): String {
        val issuedAt = clock.instant().epochSecond
        val payload =
            objectMapper.writeValueAsBytes(
                mapOf(
                    "uri" to uri,
                    "nonce" to UUID.randomUUID().toString(),
                    "iat" to issuedAt,
                    "exp" to issuedAt + TOKEN_LIFETIME_SECONDS,
                    "sub" to apiKey,
                    "bodyHash" to sha256Hex(body),
                ),
            )
        val signingInput = "${base64Url(HEADER_JSON.toByteArray())}.${base64Url(payload)}"
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(privateKey)
                update(signingInput.toByteArray())
                sign()
            }
        return "$signingInput.${base64Url(signature)}"
    }

    private fun parsePrivateKey(pemText: String): PrivateKey {
        check(pemText.isNotBlank()) { "bcm.fireblocks.private-key-pem 미설정 — 벤더 호출 전에 시크릿을 주입해야 한다" }
        val base64Body =
            pemText
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .filterNot(Char::isWhitespace)
        val keySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Body))
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val HEADER_JSON = """{"alg":"RS256","typ":"JWT"}"""

        /** 벤더 요구는 iat+30s 미만 — 시계 오차 여유를 두고 20s */
        private const val TOKEN_LIFETIME_SECONDS = 20L
    }
}
