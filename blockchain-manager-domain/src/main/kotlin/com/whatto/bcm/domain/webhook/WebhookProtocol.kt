package com.whatto.bcm.domain.webhook

/** 선택된 벤더의 수신 헤더와 envelope 해석. parseEnvelope는 서명 검증 성공 후에만 호출한다. */
interface WebhookProtocol {
    val signatureHeaderName: String

    /** 수신 배열을 변경하지 않고 메타데이터만 추출한다. */
    fun parseEnvelope(payload: ByteArray): WebhookEnvelope
}

/** 수신 알림 ID는 CORE evnt_id나 논리 사건 dedup 키가 아니다. */
data class WebhookEnvelope(
    val notificationId: String,
    val eventType: String,
    val vendorTransactionId: String?,
)
