package com.whatto.bcm.domain.vendor

/**
 * 네트워크 지갑의 자산 전송 제출·조회 포트(계약13 "전송 제출·조회 계약"). Fireblocks의 vault 중심 `VendorTransactionPort`와 자원 모델이 달라 분리한다.
 * 출금 제출 유스케이스가 이 포트를 쓴다(`DfnsTransferSubmissionService`). 내부이체·Sweep 연결은 후속이다.
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
        // 명세 패턴은 `^\d+$`지만 BCM은 선행 0을 금지해 같은 금액의 표기를 하나로 고정한다(계약13) — 응답 대조가 표기 차이로 어긋나지 않게 한다.
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
     * 멱등 충돌 — 같은 `externalId`에 벤더가 **다른 본문/지갑**을 갖고 있다는 표식이다.
     * 다만 앞 제출이 **진행 중일 때** 같은 본문 재제출이 무엇을 주는지는 아직 실측하지 못했다(계약13 수용 항목) —
     * 호출자는 최초 제출에서만 확정 거절로 읽고, 회수 재제출에서는 종결로 굳히지 않는다. 공식 문서가 규정한 표식(`error.details.duplicate`)이 있는 409만 이 결과가 되며,
     * 표식 없는 409는 원인을 단정하지 않고 일반 벤더 오류로 전파한다.
     * 호출자는 **최초 제출에서만** 조회 없이 요청 불일치로 판정하며, 어느 경우에도 자동 재제출하지 않는다.
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

/**
 * 전송 하나의 정규화 관찰. 상태 원어는 [NetworkTransferStatus]로 옮기고 BCM 업무 상태(TxStatus) 번역은 하지 않는다(계약13).
 * 목적지·금액을 함께 돌려줘 호출자가 자기 요청과 대조할 수 있게 한다 — 어댑터의 대조를 거쳤더라도 원장에 남길 값은 관찰에서 읽는다.
 */
data class NetworkTransferObservation(
    val transferId: String,
    val network: String,
    val vendorWalletId: String,
    val vendorAssetId: String,
    val destinationAddress: String,
    val amountBaseUnits: String,
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
        require(destinationAddress.isNotBlank() && destinationAddress == destinationAddress.trim()) { "Invalid destination address" }
        require(amountBaseUnits.isNotBlank() && amountBaseUnits.all { it in '0'..'9' }) { "Invalid transfer amount" }
        require(requestedAt.isNotBlank()) { "Invalid transfer request time" }
    }
}

/**
 * 명세 `TransferRequest.status`의 여섯 값. 종결(`externalId`가 그 전송에 영구 결속)과 체인 제출 여부만 판단한다.
 * `CONFIRMED`는 벤더 인덱싱 파이프라인의 온체인 확인이며 BCM `FINALIZED`(DCCP 임계)가 아니다 — 명세는 이 값을 final이라 부르지 않는다.
 */
enum class NetworkTransferStatus(
    /** 명세 enum 원어. 상태 번역기는 원어를 받으므로 관찰에서 이 값을 꺼내 쓴다 — 이름을 원어로 가정하지 않는다. */
    val vendorValue: String,
    /** 벤더 종결 — 공식 Idempotency 문서가 `externalId` 영구 결속 상태로 명시한 값이다. 재시도는 새 키를 쓴다. */
    val terminal: Boolean,
    /**
     * 체인 제출 여부. `null`은 **상태 원어만으로 알 수 없다**는 뜻이며 모름을 0/false로 바꾸지 않는다 —
     * 호출자는 관찰의 `transactionHash`·벤더 조회로 판단한다. 체인에 나간 뒤에는 같은 자금의 새 제출이 이중 지급이 된다.
     */
    val onChainSubmitted: Boolean?,
) {
    /** 지갑 정책 승인 대기. */
    PENDING(vendorValue = "Pending", terminal = false, onChainSubmitted = false),

    /** 승인 뒤 실행 중(짧은 구간). */
    EXECUTING(vendorValue = "Executing", terminal = false, onChainSubmitted = false),

    /** mempool 기록. */
    BROADCASTED(vendorValue = "Broadcasted", terminal = false, onChainSubmitted = true),

    /** Dfns 인덱싱이 확인한 온체인 포함. */
    CONFIRMED(vendorValue = "Confirmed", terminal = true, onChainSubmitted = true),

    /** 시스템 실패 **또는** 온체인 실행 실패 — 공식 문서가 두 경우를 함께 두므로 제출 여부는 상태만으로 확정하지 않는다. */
    FAILED(vendorValue = "Failed", terminal = true, onChainSubmitted = null),

    /** 정책 승인에서 거절 — 실행 전 단계다. */
    REJECTED(vendorValue = "Rejected", terminal = true, onChainSubmitted = false),
    ;

    companion object {
        private val BY_VENDOR = entries.associateBy { it.vendorValue }

        /** 명세 enum 값만 받는다 — 모르는 원어를 임의 상태로 바꾸지 않는다. */
        fun ofVendorStatus(value: String): NetworkTransferStatus? = BY_VENDOR[value]
    }
}
