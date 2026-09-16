package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.webhook.WebhookEnvelope
import com.whatto.bcm.domain.webhook.WebhookProtocol
import tools.jackson.databind.ObjectMapper

/**
 * Dfns 수신 envelope — 명세 1.1018.3 `WebhookEvent`의 필수 `id`(알림 ID)·`kind`(사건 종류)만 해석한다(계약13).
 * `data`는 명세에서 형식이 정해지지 않은 객체(`additionalProperties`)라 거래 연결값을 추정하지 않는다 — `vendorTransactionId`는 항상 null이며
 * kind별 request/chain event 해석은 형식 근거를 확보한 뒤 판단 워커에서 수행한다. 재전달 시도도 알림 ID가 다르면 별도 수신이다(dedup은 `id`).
 */
class DfnsWebhookProtocol(
    private val objectMapper: ObjectMapper,
) : WebhookProtocol {
    override val signatureHeaderName: String = DfnsWebhookSignatureVerifier.SIGNATURE_HEADER

    override fun parseEnvelope(payload: ByteArray): WebhookEnvelope {
        val root = objectMapper.readTree(payload)
        require(root.isObject) { "webhook payload is not a JSON object" }
        return WebhookEnvelope(
            notificationId = requiredText(root, "id"),
            eventType = requiredText(root, "kind"),
            vendorTransactionId = null,
        )
    }

    private fun requiredText(
        root: tools.jackson.databind.JsonNode,
        field: String,
    ): String {
        val value = root.path(field)
        require(value.isString && value.asString().isNotBlank()) { "webhook payload missing $field" }
        return value.asString()
    }
}
