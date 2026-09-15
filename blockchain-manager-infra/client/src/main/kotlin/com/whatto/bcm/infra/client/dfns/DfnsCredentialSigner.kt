package com.whatto.bcm.infra.client.dfns

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Key credential의 사용자 행위 서명 — 공식 인증 자료 "Credentials data"(docs.dfns.co/api-reference/auth/credentials-data, 2026-09-15 확인)를 따른다.
 * clientData는 `{"challenge":"<challenge>","type":"key.get"}` — 키를 알파벳순으로 두고 구분자 뒤에 공백을 두지 않으며 base64url로 보낸다.
 * 서명 입력은 그 stringified clientData 바이트다. 해시 SHA-256·DER 인코딩(EC), RSA는 SHA-256 PKCS#1, Ed25519는 순수 서명 —
 * 공식 서명 흐름 예제의 `crypto.sign(undefined, clientData, key)`와 같은 기본 동작이다. `algorithm` 필드는 보내지 않는다(키로 결정).
 * challenge는 이미 base64url 문자열이므로 다시 인코딩하지 않는다. 개인키는 메모리에만 두고 로그·예외 메시지에 넣지 않는다.
 */
class DfnsCredentialSigner(
    val credentialId: String,
    privateKeyPem: String,
) {
    private val privateKey: PrivateKey = parsePrivateKey(privateKeyPem)
    private val algorithm: String = signatureAlgorithm(privateKey)

    init {
        require(credentialId.isNotBlank() && credentialId == credentialId.trim()) { "Dfns credential id must not be blank" }
    }

    fun sign(challenge: String): DfnsKeyAssertion {
        require(isChallengeToken(challenge)) { "Dfns challenge must be a base64url token" }
        val clientData = """{"challenge":"$challenge","type":"key.get"}""".toByteArray(Charsets.UTF_8)
        val signature =
            Signature.getInstance(algorithm).run {
                initSign(privateKey)
                update(clientData)
                sign()
            }
        return DfnsKeyAssertion(credentialId, base64Url(clientData), base64Url(signature))
    }

    private fun parsePrivateKey(pemText: String): PrivateKey {
        check(pemText.isNotBlank()) { "bcm.dfns.credential-private-key 미설정 — 벤더 호출 전에 시크릿을 주입해야 한다" }
        val base64Body =
            pemText
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .filterNot(Char::isWhitespace)
        val keySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Body))
        var lastFailure: InvalidKeySpecException? = null
        for (keyAlgorithm in SUPPORTED_KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(keyAlgorithm).generatePrivate(keySpec)
            } catch (exception: InvalidKeySpecException) {
                lastFailure = exception
            }
        }
        throw IllegalStateException("Dfns credential private key must be an EC, RSA or Ed25519 PKCS#8 key", lastFailure)
    }

    private fun signatureAlgorithm(key: PrivateKey): String =
        when (key.algorithm) {
            "EC" -> "SHA256withECDSA"
            "RSA" -> "SHA256withRSA"
            "Ed25519", "EdDSA" -> "Ed25519"
            else -> throw IllegalStateException("Unsupported Dfns credential key algorithm")
        }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private val SUPPORTED_KEY_ALGORITHMS = listOf("EC", "RSA", "Ed25519")

        /** 명세의 challenge는 이미 base64url 문자열이다 — JSON을 깨뜨리는 문자·공백이 있으면 서명 입력으로 쓰지 않는다. */
        fun isChallengeToken(value: String): Boolean = value.isNotBlank() && value.none { it == '"' || it == '\\' || it.isWhitespace() }
    }
}

/** POST /auth/action firstFactor(kind=Key).credentialAssertion — 명세 KeyAssertion의 필수 3필드. */
data class DfnsKeyAssertion(
    val credId: String,
    val clientData: String,
    val signature: String,
)
