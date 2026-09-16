package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.webhook.VendorWebhookDelivery

/**
 * 서명 검증을 통과한 벤더 웹훅 원문에서 **온체인 자산 이동 사건만** 읽어내는 출력 포트(계약13 "웹훅 온체인 이동 사건 관찰").
 *
 * 전송 요청의 진행 알림([NetworkTransferEventParser])과는 다른 사건이다 — 이쪽은 체인에서 관측된 이동이며 입금도 여기로 온다.
 * 온체인 이동 사건이 아닌 알림은 null이고, 그 사건인데 형식이 다르면 null로 축소하지 않고 예외로 알린다.
 */
fun interface NetworkChainEventParser {
    fun parse(payload: ByteArray): NetworkChainEvent?
}

data class NetworkChainEvent(
    val delivery: VendorWebhookDelivery,
    val kind: NetworkChainEventKind,
    val observation: NetworkChainTransfer,
)

/**
 * 모델링한 온체인 이동 알림 종류 — 벤더가 지갑에 대해 문서화한 둘이다(계약13).
 * 감시 주소(address watch) 사건은 지갑이 아니라 등록 주소가 대상이라 이 포트가 맡지 않는다.
 */
enum class NetworkChainEventKind(
    val vendorKind: String,
) {
    /** 체인에서 확인된 이동(입금 포함). */
    DETECTED("wallet.blockchainevent.detected"),

    /** 블록에 포함됐지만 아직 확인 전인 이동. 일부 네트워크만 제공한다. */
    TRANSFER_INCLUDED("wallet.blockchain_event.transfer.included"),
    ;

    companion object {
        fun ofVendorKind(value: String): NetworkChainEventKind? = entries.firstOrNull { it.vendorKind == value }
    }
}

/**
 * 체인에서 관측된 자산 이동 한 건.
 *
 * 금액은 **최소 단위 정수 문자열**로 받는다 — 단위를 명시한 서술이 없어 BCM이 정한 해석이며, 정수가 아니면 받지 않는다(계약13 수용 항목).
 * 정밀도·심볼은 담지 않는다. 그 값은 BCM이 등록한 자산 매핑에 있고, 벤더 사건의 선택·폐기 예정 필드에 업무 판단을 걸지 않는다.
 */
data class NetworkChainTransfer(
    val network: String,
    val vendorWalletId: String,
    /** 지갑의 온체인 주소. 명세상 선택이라 결손이면 null이다. */
    val vendorWalletAddress: String?,
    /** 등록 자산 매핑과 대조할 키. 모델링한 자산 종류가 아니면 null이다 — 그때는 [vendorAssetKind]만 남는다. */
    val vendorAssetId: String?,
    /** 벤더가 알린 이동 종류 원어(`NativeTransfer`·`Erc20Transfer`·`SplTransfer`·…). */
    val vendorAssetKind: String,
    val direction: NetworkChainDirection,
    val status: NetworkChainTransferStatus,
    /** 모델링한 자산 종류에서만 관찰한다. */
    val amountBaseUnits: String?,
    val fromAddress: String?,
    val toAddress: String?,
    val transactionHash: String,
    val blockNumber: Long,
    /** 한 트랜잭션 안의 이동 순번. 명세상 선택이며 형식 서술이 없어 원문 그대로 둔다. */
    val eventIndex: String?,
    /** 벤더가 알린 관측 시각 원문. 형식·시간대 서술이 없어 파싱하지 않는다(계약13 수용 항목). */
    val observedAt: String,
) {
    init {
        require(network.isNotBlank()) { "network must not be blank" }
        require(vendorWalletId.isNotBlank()) { "vendorWalletId must not be blank" }
        require(vendorAssetKind.isNotBlank()) { "vendorAssetKind must not be blank" }
        require(transactionHash.isNotBlank()) { "transactionHash must not be blank" }
        require(observedAt.isNotBlank()) { "observedAt must not be blank" }
        require(blockNumber >= 0) { "blockNumber must not be negative" }
        require(amountBaseUnits == null || BASE_UNITS.matches(amountBaseUnits)) { "amountBaseUnits must be a base-unit integer" }
        require(vendorWalletAddress == null || vendorWalletAddress.isNotBlank()) { "vendorWalletAddress must not be blank" }
        require(vendorAssetId == null || vendorAssetId.isNotBlank()) { "vendorAssetId must not be blank" }
        require(fromAddress == null || fromAddress.isNotBlank()) { "fromAddress must not be blank" }
        require(toAddress == null || toAddress.isNotBlank()) { "toAddress must not be blank" }
        require(eventIndex == null || eventIndex.isNotBlank()) { "eventIndex must not be blank" }
    }

    companion object {
        /** 선행 0 금지 — 같은 금액의 표기를 하나로 고정한다(전송 요청과 같은 BCM 정규화). */
        val BASE_UNITS: Regex = Regex("0|[1-9][0-9]*")
    }
}

/** 지갑 기준 방향. */
enum class NetworkChainDirection(
    val vendorValue: String,
) {
    IN("In"),
    OUT("Out"),
    ;

    companion object {
        fun ofVendorValue(value: String): NetworkChainDirection? = entries.firstOrNull { it.vendorValue == value }
    }
}

/**
 * 벤더가 정의한 이동 상태 둘. `Confirmed`는 **벤더 인덱싱 파이프라인의 확인**이며 BCM의 확정(DCCP)이 아니다 —
 * `TxStatus` 번역은 판단 워커와 함께 정한다.
 */
enum class NetworkChainTransferStatus(
    val vendorValue: String,
) {
    INCLUDED("Included"),
    CONFIRMED("Confirmed"),
    ;

    companion object {
        fun ofVendorValue(value: String): NetworkChainTransferStatus? = entries.firstOrNull { it.vendorValue == value }
    }
}
