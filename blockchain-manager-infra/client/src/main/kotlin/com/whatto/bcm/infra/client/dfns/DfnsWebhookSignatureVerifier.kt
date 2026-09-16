package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.webhook.WebhookSignatureVerifier
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Dfns 웹훅 서명 검증 — 공식 가이드(계약13에 해시 기록)의 `X-DFNS-WEBHOOK-SIGNATURE: sha256=<hex>`·HMAC-SHA256·webhook secret·`timestampSent` 허용 오차.
 *
 * - 서명 입력은 **수신 바이트 그대로**다(CLAUDE.md 3절). 가이드 예제는 파싱한 payload를 다시 직렬화해 서명하지만 BCM은 재직렬화하지 않으며,
 *   실제 Baseline이 보낸 바이트와 서명의 일치는 수용 항목이다(계약13). 여기서 실패하면 다른 입력으로 재시도하지 않고 거절한다.
 * - 회전 중에는 secret이 둘일 수 있어(가이드: 새 webhook 생성 후 옛 것 삭제) 설정한 secret들을 순서대로 대조한다. secret은 env로만 주입하고 저장·로그하지 않는다.
 * - `timestampSent`(명세 WebhookEvent 필수, Unix 초)가 현재 시각과 허용 오차 안이어야 한다 — 가이드 예제의 5분을 BCM 기본값으로 둔다.
 *   서명이 맞아도 결손·형식 오류·오차 밖이면 거절한다(재전송 방어). 비교는 상수 시간이다.
 */
class DfnsWebhookSignatureVerifier(
    secrets: List<String>,
    private val replayToleranceSeconds: Long,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : WebhookSignatureVerifier {
    private val keys: List<ByteArray> = secrets.map { it.toByteArray(StandardCharsets.UTF_8) }

    init {
        // secret 자체는 어디에도 남기지 않는다 — 이 클래스는 로그·예외 메시지에 키 값을 포함하지 않는다.
        require(keys.isNotEmpty() && secrets.all { it.isNotBlank() && it == it.trim() }) { "Dfns webhook secrets must be configured" }
        require(replayToleranceSeconds > 0) { "Dfns webhook replay tolerance must be positive" }
    }

    override fun verify(
        signature: String,
        payload: ByteArray,
    ): Boolean {
        if (!signature.startsWith(SIGNATURE_PREFIX)) return false
        val provided = signature.removePrefix(SIGNATURE_PREFIX)
        if (!HEX_DIGEST.matches(provided)) return false
        val providedBytes = provided.lowercase().toByteArray(StandardCharsets.US_ASCII)
        val matched =
            keys.any { key ->
                val expected = hmacHex(key, payload).toByteArray(StandardCharsets.US_ASCII)
                MessageDigest.isEqual(expected, providedBytes)
            }
        if (!matched) return false
        return timestampWithinTolerance(payload)
    }

    private fun hmacHex(
        key: ByteArray,
        payload: ByteArray,
    ): String =
        Mac
            .getInstance(HMAC_ALGORITHM)
            .apply { init(SecretKeySpec(key, HMAC_ALGORITHM)) }
            .doFinal(payload)
            .joinToString("") { "%02x".format(it) }

    /** 명세는 `timestampSent`를 양수 Unix 초로 둔다 — 양수 검사를 먼저 하고 감산 없이 상·하한으로 비교해 극단값의 overflow 우회를 막는다. */
    private fun timestampWithinTolerance(payload: ByteArray): Boolean {
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return false
        val sent = node.path(TIMESTAMP_FIELD)
        if (!sent.isIntegralNumber || !sent.canConvertToLong()) return false
        val sentSeconds = sent.asLong()
        if (sentSeconds <= 0) return false
        val now = clock.instant().epochSecond
        val lower = runCatching { Math.subtractExact(now, replayToleranceSeconds) }.getOrNull() ?: return false
        val upper = runCatching { Math.addExact(now, replayToleranceSeconds) }.getOrNull() ?: return false
        return sentSeconds > lower && sentSeconds < upper
    }

    companion object {
        const val SIGNATURE_HEADER = "X-DFNS-WEBHOOK-SIGNATURE"
        private const val SIGNATURE_PREFIX = "sha256="
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val TIMESTAMP_FIELD = "timestampSent"
        private val HEX_DIGEST = Regex("[0-9a-fA-F]{64}")
    }
}
