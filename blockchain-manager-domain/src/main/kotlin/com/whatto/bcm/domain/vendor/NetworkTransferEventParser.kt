package com.whatto.bcm.domain.vendor

/**
 * 서명 검증을 통과한 벤더 웹훅 원문에서 **네트워크 전송 사건만** 읽어내는 출력 포트(계약13 "웹훅 전송 사건 관찰").
 *
 * 전송 사건이 아닌 알림(지갑·정책·입금 감지 등)은 null이다 — 이 포트는 전송만 해석하고 다른 종류는 각자의 경로가 맡는다.
 * 전송 사건인데 형식이 다르면 null로 축소하지 않고 예외로 알린다 — 자금 이동 신호를 조용히 버리지 않는다.
 */
fun interface NetworkTransferEventParser {
    fun parse(payload: ByteArray): NetworkTransferEvent?
}

/**
 * 웹훅이 알린 전송 사건. 알림 메타(전달 시도)와 전송 관찰값을 분리한다 —
 * 같은 전송의 여러 알림이 같은 [NetworkTransferObservation.transferId]를 가리키므로
 * 업무 판단은 알림 ID가 아니라 전송 ID와 관찰한 상태로 한다.
 */
data class NetworkTransferEvent(
    /** 알림 ID. 인박스 dedup 키이며 논리 사건 ID가 아니다. */
    val notificationId: String,
    val kind: NetworkTransferEventKind,
    /** 사건 발생 시각 — 벤더가 UTC ISO 8601로 정의한 원문 값. */
    val occurredAt: String,
    /** 전달 시도 번호 — 벤더가 필수·1 이상으로 정의한다. */
    val deliveryAttempt: Int,
    /** 재전달이면 원본 알림 ID. */
    val retryOfNotificationId: String?,
    val observation: NetworkTransferObservation,
) {
    init {
        require(notificationId.isNotBlank()) { "notificationId must not be blank" }
        require(occurredAt.isNotBlank()) { "occurredAt must not be blank" }
        require(deliveryAttempt >= 1) { "deliveryAttempt must be positive" }
        require(retryOfNotificationId == null || retryOfNotificationId.isNotBlank()) { "retryOfNotificationId must not be blank" }
    }
}

/**
 * 모델링한 전송 알림 종류 — 벤더가 문서화한 `wallet.transfer.*` 다섯이다(계약13).
 * 종류는 알림이 어느 지점에서 발생했는지만 알려주고 **업무 상태는 종류가 아니라 관찰한 전송의 상태에서 읽는다** —
 * 종류와 상태가 항상 짝을 이룬다는 보장은 문서에 없으므로 종류를 상태로 번역하지 않는다.
 */
enum class NetworkTransferEventKind(
    val vendorKind: String,
) {
    REQUESTED("wallet.transfer.requested"),
    BROADCASTED("wallet.transfer.broadcasted"),
    CONFIRMED("wallet.transfer.confirmed"),
    FAILED("wallet.transfer.failed"),
    REJECTED("wallet.transfer.rejected"),
    ;

    companion object {
        fun ofVendorKind(value: String): NetworkTransferEventKind? = entries.firstOrNull { it.vendorKind == value }
    }
}
