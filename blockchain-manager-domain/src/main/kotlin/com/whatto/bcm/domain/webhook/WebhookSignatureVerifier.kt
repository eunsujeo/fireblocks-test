package com.whatto.bcm.domain.webhook

/** 벤더 웹훅 서명 검증 경계 — 호출자는 파싱 전 수신 본문 byte[] 를 그대로 넘긴다. */
fun interface WebhookSignatureVerifier {
    fun verify(
        signature: String,
        payload: ByteArray,
    ): Boolean
}
