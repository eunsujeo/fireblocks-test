package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.vendor.NetworkTransferObservation
import com.whatto.bcm.domain.vendor.NetworkTransferStatus
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime

/**
 * 명세 `TransferRequest`의 정규화 — HTTP 조회 응답(`DfnsNetworkTransferClient`)과 웹훅 사건(`DfnsNetworkTransferEventParser`)이
 * **같은 규칙**으로 읽는다. 두 경로가 같은 schema를 참조하므로 검사도 하나여야 한다(계약13 "전송 제출·조회 계약"·"웹훅 전송 사건 관찰").
 *
 * 실패는 호출 경로가 정하는 예외로 올린다 — HTTP는 수신 바이트를 담은 `VendorApiException`, 웹훅은 `WebhookPayloadException`이다.
 * 메시지에는 필드 이름만 담고 본문 값은 담지 않는다.
 */
internal object DfnsTransferRequests {
    /** 명세 `TransferRequest.id` 형식. */
    val ID_PATTERN: Regex = Regex("xfr-[a-z0-9]{5}-[a-z0-9]{5}-[a-z0-9]{14,16}")

    /** 정규화 전에 BCM 네트워크 코드를 되찾을 때 쓰는 `network` 원문. */
    fun vendorNetwork(
        node: JsonNode,
        failure: DfnsPayloadFailure,
    ): String = requiredText(node, "network", failure)

    /**
     * `TransferRequest` 한 건을 중립 관찰값으로 옮긴다. 명세 필수 필드가 형식대로 있어야 하고,
     * 응답이 알려준 `network`는 [vendorNetwork] 파라미터와, `walletId`는 [expectedWalletId]가 주어졌을 때 그 값과 같아야 한다.
     */
    fun normalize(
        node: JsonNode,
        network: String,
        vendorNetwork: String,
        expectedWalletId: String?,
        failure: DfnsPayloadFailure,
    ): NetworkTransferObservation {
        val observedNetwork = vendorNetwork(node, failure)
        if (observedNetwork != vendorNetwork) throw failure("전송 network 불일치", null)
        val walletId = requiredText(node, "walletId", failure)
        if (expectedWalletId != null && walletId != expectedWalletId) throw failure("전송 지갑 ID 불일치", null)
        val transferId = requiredText(node, "id", failure)
        if (!ID_PATTERN.matches(transferId)) throw failure("필드 형식 오류: id", null)
        // 명세 필수 필드 — 값을 쓰지 않더라도 결손이면 전송 정보로 인정하지 않는다.
        if (!node.path("metadata").isObject) throw failure("결손: metadata", null)
        val requester = node.path("requester")
        if (!requester.isObject) throw failure("결손: requester", null)
        requiredText(requester, "userId", failure)
        val requestBody = node.path("requestBody")
        if (!requestBody.isObject) throw failure("결손: requestBody", null)
        val kind = requiredText(requestBody, "kind", failure)
        // 키 생성 규칙(EVM 주소 형식·Solana base58 32바이트)을 그대로 적용한다 — 형식이 깨진 locator는 자산 지정으로 받지 않는다.
        val observedLocator = DfnsAssetKeys.locatorField(kind)?.let { requiredText(requestBody, it, failure) }
        val observedAsset =
            runCatching { DfnsAssetKeys.of(observedNetwork, kind, observedLocator) }.getOrNull()
                ?: throw failure("자산 지정을 해석할 수 없다: requestBody", null)
        val status =
            NetworkTransferStatus.ofVendorStatus(requiredText(node, "status", failure))
                ?: throw failure("필드 형식 오류: status", null)
        return try {
            NetworkTransferObservation(
                transferId = transferId,
                network = network,
                vendorWalletId = walletId,
                vendorAssetId = observedAsset,
                destinationAddress = requiredText(requestBody, "to", failure),
                amountBaseUnits = requiredText(requestBody, "amount", failure),
                status = status,
                externalId = optionalText(node, "externalId", failure),
                transactionHash = optionalText(node, "txHash", failure),
                requestedAt = requireUtcTimestamp(node, "dateRequested", failure),
                failureReason = optionalText(node, "reason", failure),
            )
        } catch (exception: IllegalArgumentException) {
            throw failure("필드 형식 오류", exception)
        }
    }

    fun requiredText(
        node: JsonNode,
        field: String,
        failure: DfnsPayloadFailure,
    ): String {
        val value = node.path(field)
        if (!value.isString || value.asString().isBlank()) throw failure("결손: $field", null)
        return value.asString()
    }

    /** 명세가 UTC ISO 8601로 정의한 시각 — 형식이 다르거나 UTC가 아니면 감사 시각으로 받지 않는다. */
    fun requireUtcTimestamp(
        node: JsonNode,
        field: String,
        failure: DfnsPayloadFailure,
    ): String {
        val value = requiredText(node, field, failure)
        val parsed =
            runCatching { OffsetDateTime.parse(value) }.getOrNull()
                ?: throw failure("필드 형식 오류: $field", null)
        if (parsed.offset.totalSeconds != 0) throw failure("시각이 UTC가 아니다: $field", null)
        return value
    }

    fun optionalText(
        node: JsonNode,
        field: String,
        failure: DfnsPayloadFailure,
    ): String? {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) return null
        if (!value.isString) throw failure("필드 형식 오류: $field", null)
        return value.asString().takeIf(String::isNotBlank)
    }
}

/** 해석 실패를 호출 경로의 예외로 바꾼다. */
internal fun interface DfnsPayloadFailure {
    operator fun invoke(
        reason: String,
        cause: Throwable?,
    ): RuntimeException
}
