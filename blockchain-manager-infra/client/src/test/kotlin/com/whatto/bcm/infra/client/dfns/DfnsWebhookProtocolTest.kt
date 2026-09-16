package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.webhook.WebhookEnvelope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * 명세 WebhookEvent의 필수 `id`·`kind`를 envelope로 해석하고, 형식이 문서화된 전송 종류에서만 거래 연결값을 채운다(계약13).
 * 인박스 수용을 막지 않도록 형식이 어긋난 전송 정보는 null로 두고 엄격한 해석은 판단 시점의 파서가 맡는다.
 */
class DfnsWebhookProtocolTest {
    private val protocol = DfnsWebhookProtocol(ObjectMapper())

    @Test
    fun `서명 헤더 이름은 공식 가이드의 X-DFNS-WEBHOOK-SIGNATURE이고 전송 종류는 전송 ID를 담는다`() {
        assertThat(protocol.signatureHeaderName).isEqualTo("X-DFNS-WEBHOOK-SIGNATURE")
        val payload =
            """{"id":"whe-1","date":"2026-09-16T00:00:00.000Z","kind":"wallet.transfer.confirmed",
               "data":{"transferRequest":{"id":"$TRANSFER_ID","txHash":"0xabc"}},"status":"200","timestampSent":1789430400}"""

        assertThat(protocol.parseEnvelope(payload.toByteArray()))
            .isEqualTo(WebhookEnvelope("whe-1", "wallet.transfer.confirmed", TRANSFER_ID))
    }

    @Test
    fun `전송이 아닌 종류와 형식이 다른 전송 정보에서는 거래 ID를 추정하지 않는다`() {
        listOf(
            // 형식이 정해지지 않은 `data`를 가진 다른 종류 — 같은 자리에 ID가 있어도 거래 연결값으로 쓰지 않는다.
            """{"id":"whe-1","kind":"wallet.transaction.confirmed","data":{"transferRequest":{"id":"$TRANSFER_ID"}}}""",
            """{"id":"whe-1","kind":"wallet.blockchainevent.detected","data":{"blockchainEvent":{"id":"$TRANSFER_ID"}}}""",
            // 전송 종류지만 명세 형식(`xfr-…`)이 아니거나 결손 — 수용은 막지 않고 연결값만 비운다.
            """{"id":"whe-1","kind":"wallet.transfer.failed","data":{"transferRequest":{"id":"xfr-short"}}}""",
            """{"id":"whe-1","kind":"wallet.transfer.failed","data":{"transferRequest":{"id":7}}}""",
            """{"id":"whe-1","kind":"wallet.transfer.failed","data":{"transferRequest":{}}}""",
            """{"id":"whe-1","kind":"wallet.transfer.failed","data":{}}""",
            """{"id":"whe-1","kind":"wallet.transfer.failed"}""",
        ).forEach { body ->
            assertThat(protocol.parseEnvelope(body.toByteArray()).vendorTransactionId).describedAs(body).isNull()
        }
    }

    @Test
    fun `id·kind가 없거나 문자열이 아니거나 본문이 객체가 아니면 해석하지 않는다`() {
        listOf(
            """{"kind":"wallet.created","data":{},"timestampSent":1}""" to "id",
            """{"id":"whe-1","data":{},"timestampSent":1}""" to "kind",
            """{"id":"","kind":"wallet.created"}""" to "id",
            """{"id":"whe-1","kind":7}""" to "kind",
        ).forEach { (body, field) ->
            assertThatThrownBy { protocol.parseEnvelope(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(field)
        }
        assertThatThrownBy { protocol.parseEnvelope("[]".toByteArray()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val TRANSFER_ID = "xfr-20g4k-nsdpo-mg6arrifgvid4orn"
    }
}
