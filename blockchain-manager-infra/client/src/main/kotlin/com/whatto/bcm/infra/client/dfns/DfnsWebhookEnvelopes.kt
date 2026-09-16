package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.webhook.VendorWebhookDelivery
import tools.jackson.databind.JsonNode

/**
 * 명세 `WebhookEnvelopeBase`의 알림 메타 해석 — 사건 종류와 무관하게 같은 규칙이므로 종류별 파서가 함께 쓴다(계약13).
 *
 * `id`·`date`·`deliveryAttempt`는 필수이고 `id`·`retryOf`는 명세 형식(`whe-…`)이어야 한다. 결손·빈 값을 기본값이나 null로 축소하지 않는다.
 * `timestampSent`는 앞선 서명 검증(`DfnsWebhookSignatureVerifier`)이 이미 필수로 검사하므로 여기서 다시 보지 않는다.
 */
internal object DfnsWebhookEnvelopes {
    /** 명세 `WebhookEnvelopeBase`의 알림 ID 형식. 조회 모델 `WebhookEvent.id`에는 이 제약이 없지만 수신 envelope에는 있다. */
    val NOTIFICATION_ID_PATTERN: Regex = Regex("whe-[a-z0-9]{5}-[a-z0-9]{5}-[a-z0-9]{14,16}")

    fun delivery(
        root: JsonNode,
        failure: DfnsPayloadFailure,
    ): VendorWebhookDelivery =
        VendorWebhookDelivery(
            notificationId = notificationId(root, "id", failure),
            occurredAt = DfnsTransferRequests.requireUtcTimestamp(root, "date", failure),
            deliveryAttempt = deliveryAttempt(root, failure),
            retryOfNotificationId = retryOf(root, failure),
        )

    /** 명세가 필수·1 이상 정수로 정의한 전달 시도 번호. 결손이나 형식 오류는 사건으로 받지 않는다. */
    private fun deliveryAttempt(
        root: JsonNode,
        failure: DfnsPayloadFailure,
    ): Int {
        val value = root.path("deliveryAttempt")
        if (value.isMissingNode || value.isNull) throw failure("결손: deliveryAttempt", null)
        if (!value.isIntegralNumber || !value.canConvertToInt()) throw failure("필드 형식 오류: deliveryAttempt", null)
        return value.asInt().also { if (it < 1) throw failure("필드 형식 오류: deliveryAttempt", null) }
    }

    /** 재전달이면 원본 알림 ID. 선택 필드지만 **있으면** 명세 형식이어야 한다 — 빈 값을 결손으로 축소하지 않는다. */
    private fun retryOf(
        root: JsonNode,
        failure: DfnsPayloadFailure,
    ): String? {
        val value = root.path("retryOf")
        if (value.isMissingNode || value.isNull) return null
        return notificationId(root, "retryOf", failure)
    }

    private fun notificationId(
        node: JsonNode,
        field: String,
        failure: DfnsPayloadFailure,
    ): String {
        val value = DfnsTransferRequests.requiredText(node, field, failure)
        if (!NOTIFICATION_ID_PATTERN.matches(value)) throw failure("필드 형식 오류: $field", null)
        return value
    }
}
