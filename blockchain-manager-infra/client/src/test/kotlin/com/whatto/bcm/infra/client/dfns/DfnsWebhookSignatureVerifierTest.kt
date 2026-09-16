package com.whatto.bcm.infra.client.dfns

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 공식 가이드의 `sha256=<hex>` HMAC-SHA256·webhook secret·timestampSent 허용 오차 계약 — 서명 입력은 수신 바이트 그대로다(계약13).
 * payload는 명세 WebhookEvent 필수 필드 형태의 시험 표기이며 실제 Dfns 발송 원문이 아니다. secret은 실행마다 무작위로 만든다(고정 자격을 소스에 두지 않음).
 */
class DfnsWebhookSignatureVerifierTest {
    private val now = Instant.parse("2026-09-16T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val verifier = DfnsWebhookSignatureVerifier(listOf(CURRENT_SECRET, PREVIOUS_SECRET), 300, clock, ObjectMapper())
    private val payload = event(now.epochSecond)

    @Test
    fun `현재 또는 회전 전 secret의 HMAC이 수신 바이트와 맞고 timestampSent가 허용 오차 안이면 통과한다`() {
        assertThat(verifier.verify(sign(CURRENT_SECRET, payload), payload)).isTrue()
        assertThat(verifier.verify(sign(PREVIOUS_SECRET, payload), payload)).isTrue()
        assertThat(verifier.verify(sign(CURRENT_SECRET, payload).uppercase().replace("SHA256=", "sha256="), payload)).isTrue()
    }

    @Test
    fun `다른 secret·변조된 바이트·재직렬화된 본문은 거절하고 다른 입력으로 재시도하지 않는다`() {
        assertThat(verifier.verify(sign(randomSecret(), payload), payload)).isFalse()
        assertThat(verifier.verify(sign(CURRENT_SECRET, payload), payload + '\n'.code.toByte())).isFalse()
        val reserialized = ObjectMapper().writeValueAsBytes(ObjectMapper().readTree(payload))
        val pretty = payload.toString(StandardCharsets.UTF_8).replace(",", ", ").toByteArray()
        assertThat(pretty).isNotEqualTo(payload)
        // 발신 측이 압축 JSON에 서명했다면 공백을 넣은 사본은 실패한다 — BCM은 받은 바이트로만 검증한다.
        assertThat(verifier.verify(sign(CURRENT_SECRET, reserialized), pretty)).isFalse()
    }

    @Test
    fun `헤더 형식이 sha256= 64자 hex가 아니면 거절한다`() {
        listOf(
            "",
            "sha256=",
            "sha256=abc",
            "sha1=${"0".repeat(40)}",
            "0".repeat(64),
            "sha256=${"g".repeat(64)}",
            " sha256=${"0".repeat(64)}",
        ).forEach { assertThat(verifier.verify(it, payload)).describedAs(it).isFalse() }
    }

    @Test
    fun `서명이 맞아도 timestampSent가 없거나 정수가 아니거나 허용 오차 밖이면 거절한다`() {
        listOf(
            """{"id":"wh-1","kind":"wallet.transfer.confirmed","data":{}}""".toByteArray(),
            """{"id":"wh-1","kind":"wallet.transfer.confirmed","data":{},"timestampSent":"${now.epochSecond}"}""".toByteArray(),
            """{"id":"wh-1","kind":"wallet.transfer.confirmed","data":{},"timestampSent":1.5}""".toByteArray(),
            event(now.epochSecond - 300),
            event(now.epochSecond + 300),
            event(0),
            event(-1),
            event(Long.MIN_VALUE),
            event(Long.MIN_VALUE + now.epochSecond),
            event(Long.MAX_VALUE),
            "not json".toByteArray(),
        ).forEach { body ->
            assertThat(verifier.verify(sign(CURRENT_SECRET, body), body)).describedAs(body.toString(StandardCharsets.UTF_8)).isFalse()
        }
        assertThat(verifier.verify(sign(CURRENT_SECRET, event(now.epochSecond - 299)), event(now.epochSecond - 299))).isTrue()
    }

    @Test
    fun `secret이 없거나 공백이거나 허용 오차가 0이면 검증기를 만들 수 없다`() {
        assertThatThrownBy {
            DfnsWebhookSignatureVerifier(emptyList(), 300, clock, ObjectMapper())
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DfnsWebhookSignatureVerifier(listOf(" "), 300, clock, ObjectMapper())
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DfnsWebhookSignatureVerifier(listOf(CURRENT_SECRET), 0, clock, ObjectMapper())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun event(timestampSent: Long) =
        """{"id":"wh-1","date":"2026-09-16T00:00:00.000Z","kind":"wallet.transfer.confirmed","data":{"transferRequest":{"id":"xfr-1"}},"status":"200","timestampSent":$timestampSent}"""
            .toByteArray()

    companion object {
        private val CURRENT_SECRET = randomSecret()
        private val PREVIOUS_SECRET = randomSecret()

        /** 시험용 secret — 실행마다 생성하며 값이 소스·로그에 남지 않는다. */
        fun randomSecret(): String =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(java.security.SecureRandom().generateSeed(32))

        fun sign(
            secret: String,
            payload: ByteArray,
        ): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return "sha256=" + mac.doFinal(payload).joinToString("") { "%02x".format(it) }
        }
    }
}
