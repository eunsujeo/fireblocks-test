package com.whatto.bcm.domain.vendor

/**
 * 네트워크 지갑의 자산 전송 제출·조회 포트(계약13 "전송 제출·조회 계약"). Fireblocks의 vault 중심 `VendorTransactionPort`와 자원 모델이 달라 분리한다.
 * 내부 대역이며 제출 원장·출금/Sweep 유스케이스 연결은 후속이다.
 *
 * - 자산은 등록 매핑의 `vendorAssetId`로만 지정한다 — 벤더 전송 본문의 kind·locator는 어댑터가 그 키에서 만든다.
 * - 금액은 최소 단위 정수 문자열이다. 어댑터가 단위를 바꾸거나 반올림하지 않는다.
 * - 응답을 받지 못한 실패 뒤 자동 재제출은 없다. 같은 제출 키의 재요청 판정은 벤더 멱등 계약(409)과 호출자 원장이 함께 정한다.
 */
interface NetworkTransferPort {
    fun submit(request: NetworkTransferRequest): NetworkTransferSubmission

    /** 전송 하나의 현재 관찰 — 없으면 null이다. 조회 실패는 수신 바이트를 담은 `VendorApiException`으로 전파한다. */
    fun transfer(
        scope: NetworkWalletScope,
        vendorWalletId: String,
        transferId: String,
    ): NetworkTransferObservation?
}

/**
 * 전송 제출 요청. `externalId`는 호출자의 제출 키(멱등 키)이며 벤더 계약상 최대 50자다 —
 * 더 길면 자르지 않고 만들 수 없다(계약13). 실패한 전송의 재시도는 호출자가 **새 키**로 만든다.
 */
data class NetworkTransferRequest(
    val scope: NetworkWalletScope,
    val vendorWalletId: String,
    val vendorAssetId: String,
    val destinationAddress: String,
    val amountBaseUnits: String,
    val externalId: String,
) {
    init {
        requireWalletIdentifier("vendorWalletId", vendorWalletId)
        require(vendorAssetId.isNotBlank() && vendorAssetId == vendorAssetId.trim()) { "Invalid vendor asset id" }
        require(destinationAddress.isNotBlank() && destinationAddress == destinationAddress.trim()) { "Invalid destination address" }
        require(BASE_UNITS.matches(amountBaseUnits)) { "Invalid transfer amount" }
        require(externalId.isNotBlank() && externalId == externalId.trim() && externalId.length <= EXTERNAL_ID_MAX_LENGTH) {
            "Invalid transfer external id"
        }
    }

    companion object {
        /** 명세 Transfer Asset의 `externalId` 최대 길이. */
        const val EXTERNAL_ID_MAX_LENGTH = 50
        private val BASE_UNITS = Regex("0|[1-9][0-9]*")
    }
}

sealed interface NetworkTransferSubmission {
    /** 벤더가 수용한 전송 — 벤더 멱등 계약상 같은 키·같은 본문의 재요청도 같은 엔티티로 수용된다. */
    data class Accepted(
        val observation: NetworkTransferObservation,
    ) : NetworkTransferSubmission

    /**
     * 같은 `externalId`로 **다른 본문/지갑**을 보낸 충돌(벤더 409). 호출자는 조회 없이 요청 불일치로 판정하고 자동 재제출하지 않는다.
     * `duplicateTransferId`는 벤더가 알려준 기존 전송 ID이며 없을 수도 있다. `responseBody`는 수신 원문 바이트다.
     */
    class Conflict(
        val duplicateTransferId: String?,
        responseBody: ByteArray,
    ) : NetworkTransferSubmission {
        private val body = responseBody.copyOf()

        fun responseBody(): ByteArray = body.copyOf()
    }
}

/** 전송 하나의 정규화 관찰. 상태 원어는 [NetworkTransferStatus]로 옮기고 BCM 업무 상태(TxStatus) 번역은 하지 않는다(계약13). */
data class NetworkTransferObservation(
    val transferId: String,
    val network: String,
    val vendorWalletId: String,
    val vendorAssetId: String,
    val status: NetworkTransferStatus,
    val externalId: String?,
    val transactionHash: String?,
    val requestedAt: String,
    val failureReason: String?,
) {
    init {
        requireWalletIdentifier("network", network)
        requireWalletIdentifier("vendorWalletId", vendorWalletId)
        require(transferId.isNotBlank() && transferId == transferId.trim()) { "Invalid transfer id" }
        require(vendorAssetId.isNotBlank()) { "Invalid vendor asset id" }
        require(requestedAt.isNotBlank()) { "Invalid transfer request time" }
    }
}

/**
 * 명세 `TransferRequest.status`의 여섯 값. 종결(`externalId`가 그 전송에 영구 결속)과 체인 제출 여부만 판단한다.
 * `CONFIRMED`는 Dfns 인덱싱 파이프라인의 온체인 확인이며 BCM `FINALIZED`(DCCP 임계)가 아니다.
 */
enum class NetworkTransferStatus(
    /** 벤더 종결 — 공식 Idempotency 문서가 `externalId` 영구 결속 상태로 명시한 값이다. 재시도는 새 키를 쓴다. */
    val terminal: Boolean,
    /** 체인에 제출된 뒤의 상태 — 같은 키의 새 제출을 만들면 이중 지급 위험이 있다. */
    val broadcast: Boolean,
) {
    /** 지갑 정책 승인 대기. */
    PENDING(terminal = false, broadcast = false),

    /** 승인 뒤 실행 중(짧은 구간). */
    EXECUTING(terminal = false, broadcast = false),

    /** mempool 기록. */
    BROADCASTED(terminal = false, broadcast = true),

    /** Dfns 인덱싱이 확인한 온체인 포함. */
    CONFIRMED(terminal = true, broadcast = true),

    /** 시스템 실패 또는 온체인 실행 실패(문서상 벤더 재시도 없음). */
    FAILED(terminal = true, broadcast = false),

    /** 정책 승인에서 거절. */
    REJECTED(terminal = true, broadcast = false),
    ;

    companion object {
        private val BY_VENDOR =
            mapOf(
                "Pending" to PENDING,
                "Executing" to EXECUTING,
                "Broadcasted" to BROADCASTED,
                "Confirmed" to CONFIRMED,
                "Failed" to FAILED,
                "Rejected" to REJECTED,
            )

        /** 명세 enum 값만 받는다 — 모르는 원어를 임의 상태로 바꾸지 않는다. */
        fun ofVendorStatus(value: String): NetworkTransferStatus? = BY_VENDOR[value]
    }
}
