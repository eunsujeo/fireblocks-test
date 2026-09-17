package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.vendor.NetworkTransferEventKind
import com.whatto.bcm.domain.webhook.WebhookEnvelope
import com.whatto.bcm.domain.webhook.WebhookProtocol
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Dfns 수신 envelope — 명세 `WebhookEvent`의 필수 `id`(알림 ID)·`kind`(사건 종류)를 해석한다(계약13).
 *
 * `vendorTransactionId`는 **형식이 문서화된 종류에서만** 채운다 — 공식 `webhooks`의 `wallet.transfer.*`는 `data.transferRequest`가
 * 조회와 같은 `TransferRequest`이므로 그 `id`를 쓰고, 형식이 정해지지 않은 나머지 종류는 추정하지 않고 null로 둔다.
 * 여기서는 형식이 어긋나도 거절하지 않는다 — 인박스 수용(원문 보관)을 막지 않는다. 전송 사건의 엄격한 해석기
 * [DfnsNetworkTransferEventParser]는 아직 실행 빈이 아니며 판단 워커는 `wallet.transfer.*`를 처리 완료로만 남긴다(계약13).
 * 인박스 dedup 키는 `id`이며 재전달도 알림 ID가 다르면 별도 수신이다.
 */
class DfnsWebhookProtocol(
    private val objectMapper: ObjectMapper,
) : WebhookProtocol {
    override val signatureHeaderName: String = DfnsWebhookSignatureVerifier.SIGNATURE_HEADER

    override fun parseEnvelope(payload: ByteArray): WebhookEnvelope {
        val root = objectMapper.readTree(payload)
        require(root.isObject) { "webhook payload is not a JSON object" }
        val eventType = requiredText(root, "kind")
        return WebhookEnvelope(
            notificationId = requiredText(root, "id"),
            eventType = eventType,
            vendorTransactionId = transferId(root, eventType),
        )
    }

    /** 전송 종류의 `data.transferRequest.id`만 거래 연결값으로 쓴다. 명세 형식이 아니면 넣지 않는다. */
    private fun transferId(
        root: JsonNode,
        eventType: String,
    ): String? {
        if (NetworkTransferEventKind.ofVendorKind(eventType) == null) return null
        return root
            .path("data")
            .path("transferRequest")
            .path("id")
            .takeIf(JsonNode::isString)
            ?.asString()
            ?.takeIf(DfnsTransferRequests.ID_PATTERN::matches)
    }

    private fun requiredText(
        root: JsonNode,
        field: String,
    ): String {
        val value = root.path(field)
        require(value.isString && value.asString().isNotBlank()) { "webhook payload missing $field" }
        return value.asString()
    }
}
