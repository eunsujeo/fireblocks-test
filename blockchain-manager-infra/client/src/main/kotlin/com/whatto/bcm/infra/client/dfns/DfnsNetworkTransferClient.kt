package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkTransferObservation
import com.whatto.bcm.domain.vendor.NetworkTransferPort
import com.whatto.bcm.domain.vendor.NetworkTransferRequest
import com.whatto.bcm.domain.vendor.NetworkTransferStatus
import com.whatto.bcm.domain.vendor.NetworkTransferSubmission
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * NetworkTransferPort의 Dfns 구현 — 채택 명세 1.1018.3의 `POST /wallets/{walletId}/transfers`·`GET /wallets/{walletId}/transfers/{transferId}`
 * (계약13 "전송 제출·조회 계약"). 실행 빈으로 등록하지 않는 내부 대역이며 제출 원장·출금/Sweep 연결은 후속이다.
 *
 * - 본문은 등록 자산 키에서 `kind`·locator를 되돌려 만들고 `to`·`amount`(최소 단위 정수)·`externalId`만 담는다.
 *   수수료·대납·Travel Rule·memo 같은 선택 필드는 각각 별도 계약 전이라 보내지 않는다.
 * - 제출은 지갑 생성과 같은 사용자 행위 서명을 거치며 서명 경로는 지갑 ID가 들어간 실제 경로다.
 * - 409는 "같은 `externalId` 다른 본문/지갑"이라는 벤더 멱등 계약이라 조회 없이 `Conflict`로 돌려주고 자동 재제출하지 않는다.
 *   그 밖의 오류는 상태·수신 바이트를 담아 전파한다.
 * - 응답은 `walletId`·`network`가 요청 scope와, `requestBody`의 `kind`·locator가 보낸 값과 같아야 정규화한다.
 */
class DfnsNetworkTransferClient(
    restClientBuilder: RestClient.Builder,
    private val properties: DfnsProperties,
    private val origin: ProviderOrigin,
    signer: DfnsCredentialSigner,
    metrics: OperationalMetricsPort,
    restClientFactory: DfnsRestClientFactory,
) : NetworkTransferPort {
    private val objectMapper = ObjectMapper()
    private val http = DfnsHttp(restClientFactory.create(restClientBuilder, properties), properties, metrics)
    private val userActions = DfnsUserActionClient(http, signer, objectMapper)

    init {
        require(origin.protocolProvider == "dfns") { "DfnsNetworkTransferClient requires the Dfns origin" }
    }

    override fun submit(request: NetworkTransferRequest): NetworkTransferSubmission {
        origin.requireMatch(request.scope.origin)
        val asset = requireAsset(request.scope, request.vendorAssetId)
        val body = transferBody(asset, request)
        val path = transfersPath(request.vendorWalletId)
        val userAction = userActions.userAction(HttpMethod.POST, path, body)
        val response = http.call(SUBMIT_OPERATION, HttpMethod.POST, body, userAction) { it.path(path).build() }
        if (response.status == HTTP_CONFLICT) {
            return NetworkTransferSubmission.Conflict(duplicateTransferId(response), response.body)
        }
        response.requireSuccess()
        return NetworkTransferSubmission.Accepted(
            toObservation(parseObject(response), response, request.scope, request.vendorWalletId, asset),
        )
    }

    override fun transfer(
        scope: NetworkWalletScope,
        vendorWalletId: String,
        transferId: String,
    ): NetworkTransferObservation? {
        origin.requireMatch(scope.origin)
        requireVendorId(vendorWalletId, "wallet id")
        requireVendorId(transferId, "transfer id")
        val response =
            http.call(READ_OPERATION, HttpMethod.GET) {
                it.path("${transfersPath(vendorWalletId)}/{transferId}").build(transferId)
            }
        if (response.status == HTTP_NOT_FOUND) return null
        response.requireSuccess()
        val observation = toObservation(parseObject(response), response, scope, vendorWalletId, asset = null)
        if (observation.transferId != transferId) throw response.failure("Dfns transfer id mismatch in read response")
        return observation
    }

    private fun requireAsset(
        scope: NetworkWalletScope,
        vendorAssetId: String,
    ): DfnsAssetKey {
        val asset = DfnsAssetKeys.parse(vendorAssetId)
        require(asset != null) { "Unsupported Dfns asset key" }
        require(asset.vendorNetwork == properties.networks[scope.network]) { "Dfns asset key does not belong to the scope network" }
        return asset
    }

    private fun transferBody(
        asset: DfnsAssetKey,
        request: NetworkTransferRequest,
    ): ByteArray {
        val body = linkedMapOf<String, String>("kind" to asset.kind)
        asset.locatorField?.let { field -> body[field] = checkNotNull(asset.locator) }
        body["to"] = request.destinationAddress
        body["amount"] = request.amountBaseUnits
        body["externalId"] = request.externalId
        return objectMapper.writeValueAsBytes(body)
    }

    private fun duplicateTransferId(response: DfnsHttpResponse): String? {
        val node = runCatching { objectMapper.readTree(response.body) }.getOrNull() ?: return null
        val duplicate =
            node
                .path("error")
                .path("details")
                .path("duplicate")
                .path("id")
        return duplicate.takeIf(JsonNode::isString)?.asString()?.takeIf(String::isNotBlank)
    }

    /** 응답의 지갑·network가 요청과 같아야 하고, `asset`이 있으면 응답 `requestBody`의 자산 지정도 보낸 값과 같아야 한다. */
    private fun toObservation(
        node: JsonNode,
        response: DfnsHttpResponse,
        scope: NetworkWalletScope,
        expectedWalletId: String,
        asset: DfnsAssetKey?,
    ): NetworkTransferObservation {
        val vendorNetwork = requiredText(node, "network", response)
        if (vendorNetwork != properties.networks[scope.network]) throw response.failure("Dfns transfer network mismatch")
        val walletId = requiredText(node, "walletId", response)
        if (walletId != expectedWalletId) throw response.failure("Dfns transfer wallet id mismatch")
        val requestBody = node.path("requestBody")
        if (!requestBody.isObject) throw response.failure("Dfns ${response.operation} 응답 결손: requestBody")
        val kind = requiredText(requestBody, "kind", response)
        // 키 생성 규칙(EVM 주소 형식·Solana base58 32바이트)을 그대로 적용한다 — 형식이 깨진 locator는 수신 바이트를 담아 실패한다.
        val observedLocator = DfnsAssetKeys.locatorField(kind)?.let { requiredText(requestBody, it, response) }
        val observedAsset =
            runCatching { DfnsAssetKeys.of(vendorNetwork, kind, observedLocator) }.getOrNull()
                ?: throw response.failure("Dfns ${response.operation} 응답의 자산 지정을 해석할 수 없다: requestBody")
        if (asset != null && (kind != asset.kind || observedLocator != asset.locator)) {
            throw response.failure("Dfns transfer asset mismatch in response")
        }
        val status =
            NetworkTransferStatus.ofVendorStatus(requiredText(node, "status", response))
                ?: throw response.failure("Dfns ${response.operation} 응답 필드 형식 오류: status")
        return try {
            NetworkTransferObservation(
                transferId = requiredText(node, "id", response),
                network = scope.network,
                vendorWalletId = walletId,
                vendorAssetId = observedAsset,
                status = status,
                externalId = optionalText(node, "externalId", response),
                transactionHash = optionalText(node, "txHash", response),
                requestedAt = requiredText(node, "dateRequested", response),
                failureReason = optionalText(node, "reason", response),
            )
        } catch (exception: IllegalArgumentException) {
            throw response.failure("Dfns ${response.operation} 응답 필드 형식 오류", exception)
        }
    }

    private fun parseObject(response: DfnsHttpResponse): JsonNode {
        val node =
            try {
                objectMapper.readTree(response.body)
            } catch (exception: RuntimeException) {
                throw response.failure("unparseable", exception)
            }
        if (!node.isObject) throw response.failure("Dfns ${response.operation} 응답이 JSON 객체가 아니다")
        return node
    }

    private fun requiredText(
        node: JsonNode,
        field: String,
        response: DfnsHttpResponse,
    ): String {
        val value = node.path(field)
        if (!value.isString || value.asString().isBlank()) throw response.failure("Dfns ${response.operation} 응답 결손: $field")
        return value.asString()
    }

    private fun optionalText(
        node: JsonNode,
        field: String,
        response: DfnsHttpResponse,
    ): String? {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) return null
        if (!value.isString) throw response.failure("Dfns ${response.operation} 응답 필드 형식 오류: $field")
        return value.asString().takeIf(String::isNotBlank)
    }

    private fun requireVendorId(
        value: String,
        label: String,
    ) {
        require(value.isNotBlank() && value == value.trim() && value.all { it.isLetterOrDigit() || it == '-' }) { "Invalid Dfns $label" }
    }

    private fun transfersPath(vendorWalletId: String): String {
        requireVendorId(vendorWalletId, "wallet id")
        return "${DfnsNetworkWalletClient.WALLETS_PATH}/$vendorWalletId/transfers"
    }

    private companion object {
        const val SUBMIT_OPERATION = "dfnsCreateTransfer"
        const val READ_OPERATION = "dfnsGetTransfer"
        const val HTTP_CONFLICT = 409
        const val HTTP_NOT_FOUND = 404
    }
}
