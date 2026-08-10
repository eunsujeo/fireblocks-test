package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.infra.client.fireblocks.fixture.TestRsaKeyFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * 벤더 API 요청 JWT 계약 고정 — RS256 · uri/nonce/iat/exp/sub/bodyHash · exp ≤ iat+30s.
 * 근거: Fireblocks 공식 문서 "Signing a request (JWT Structure)" (2026-08-05 확인).
 */
class FireblocksJwtSignerTest {
    private val keyPair: KeyPair = TestRsaKeyFixture.generateKeyPair()

    private val privateKeyPem: String = TestRsaKeyFixture.toPkcs8Pem(keyPair)

    private val fixedClock = Clock.fixed(Instant.parse("2026-08-05T01:02:03Z"), ZoneOffset.UTC)
    private val objectMapper = ObjectMapper()

    private fun signer(pem: String = privateKeyPem) = FireblocksJwtSigner(apiKey = "api-key-1", privateKeyPem = pem, clock = fixedClock)

    @Test
    fun `클레임 — uri·sub·iat·exp·bodyHash 를 계약대로 담는다`() {
        val body = """{"name":"acct-1"}""".toByteArray()

        val payload = payloadOf(signer().sign("/v1/vault/accounts", body))

        assertThat(payload.path("uri").asString()).isEqualTo("/v1/vault/accounts")
        assertThat(payload.path("sub").asString()).isEqualTo("api-key-1")
        assertThat(payload.path("iat").asLong()).isEqualTo(fixedClock.instant().epochSecond)
        val lifetime = payload.path("exp").asLong() - payload.path("iat").asLong()
        assertThat(lifetime).isGreaterThan(0).isLessThan(30)
        assertThat(payload.path("bodyHash").asString()).isEqualTo(sha256Hex(body))
    }

    @Test
    fun `본문 없는 요청(GET)의 bodyHash 는 빈 바이트의 해시다`() {
        val payload = payloadOf(signer().sign("/v1/vault/accounts/7/ETH", ByteArray(0)))

        assertThat(payload.path("bodyHash").asString()).isEqualTo(sha256Hex(ByteArray(0)))
    }

    @Test
    fun `nonce 는 서명마다 유일하다`() {
        val subject = signer()

        val first = payloadOf(subject.sign("/v1/x", ByteArray(0))).path("nonce").asString()
        val second = payloadOf(subject.sign("/v1/x", ByteArray(0))).path("nonce").asString()

        assertThat(first).isNotBlank()
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `헤더 alg 는 RS256 이고 서명은 공개키로 검증된다`() {
        val jwt = signer().sign("/v1/vault/accounts", ByteArray(0))
        val (header, payload, signature) = jwt.split(".")

        val headerJson = objectMapper.readTree(Base64.getUrlDecoder().decode(header))
        assertThat(headerJson.path("alg").asString()).isEqualTo("RS256")

        val verifier =
            Signature.getInstance("SHA256withRSA").apply {
                initVerify(keyPair.public)
                update("$header.$payload".toByteArray())
            }
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(signature))).isTrue()
    }

    @Test
    fun `서명키 미설정이면 서명 시점에 설정 오류로 실패한다`() {
        assertThatThrownBy { signer(pem = "").sign("/v1/x", ByteArray(0)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("private-key")
    }

    private fun payloadOf(jwt: String): JsonNode = objectMapper.readTree(Base64.getUrlDecoder().decode(jwt.split(".")[1]))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
