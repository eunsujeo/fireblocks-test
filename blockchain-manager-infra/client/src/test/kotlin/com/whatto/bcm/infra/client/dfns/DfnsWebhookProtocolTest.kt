package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.webhook.WebhookEnvelope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/** 명세 WebhookEvent의 필수 `id`·`kind`만 envelope로 해석하고 형식 미정의 `data`에서 거래 ID를 추정하지 않는다(계약13). */
class DfnsWebhookProtocolTest {
    private val protocol = DfnsWebhookProtocol(ObjectMapper())

    @Test
    fun `서명 헤더 이름은 공식 가이드의 X-DFNS-WEBHOOK-SIGNATURE이고 envelope는 id·kind만 담는다`() {
        assertThat(protocol.signatureHeaderName).isEqualTo("X-DFNS-WEBHOOK-SIGNATURE")
        val payload =
            """{"id":"wh-1","date":"2026-09-16T00:00:00.000Z","kind":"wallet.transfer.confirmed",
               "data":{"transferRequest":{"id":"xfr-1","txHash":"0xabc"}},"status":"200","timestampSent":1789430400}""".toByteArray()

        assertThat(protocol.parseEnvelope(payload)).isEqualTo(WebhookEnvelope("wh-1", "wallet.transfer.confirmed", null))
    }

    @Test
    fun `id·kind가 없거나 문자열이 아니거나 본문이 객체가 아니면 해석하지 않는다`() {
        listOf(
            """{"kind":"wallet.created","data":{},"timestampSent":1}""" to "id",
            """{"id":"wh-1","data":{},"timestampSent":1}""" to "kind",
            """{"id":"","kind":"wallet.created"}""" to "id",
            """{"id":"wh-1","kind":7}""" to "kind",
        ).forEach { (body, field) ->
            assertThatThrownBy { protocol.parseEnvelope(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(field)
        }
        assertThatThrownBy { protocol.parseEnvelope("[]".toByteArray()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
