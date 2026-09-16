package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkTransferObservation
import com.whatto.bcm.domain.vendor.NetworkTransferPort
import com.whatto.bcm.domain.vendor.NetworkTransferRequest
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
 * - 409는 공식 문서가 규정한 표식(`error.details.duplicate`)이 있을 때만 멱등 충돌로 보고 조회 없이 `Conflict`로 돌려주며 자동 재제출하지 않는다.
 *   표식 없는 409와 그 밖의 오류는 원인을 단정하지 않고 상태·수신 바이트를 담아 전파한다.
 * - 응답은 명세 필수 필드(`id` 형식·`requester.userId`·`metadata`·`requestBody.to`/`amount`·UTC `dateRequested`)를 검사하고,
 *   제출 응답은 `walletId`·`network`·자산 키·목적지·금액·되돌아온 `externalId`가 모두 보낸 요청과 같아야 정규화한다.
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
        // 공식 문서가 규정한 멱등 충돌 표식이 있는 409만 충돌로 판정한다 — 표식 없는 409는 원인을 단정하지 않고 일반 오류로 전파한다.
        if (response.status == HTTP_CONFLICT) {
            idempotencyDuplicate(response)?.let { duplicate ->
                return NetworkTransferSubmission.Conflict(duplicate.takeIf(String::isNotBlank), response.body)
            }
        }
        response.requireSuccess()
        return NetworkTransferSubmission.Accepted(toObservation(parseObject(response), response, request))
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
        val observation = toObservation(parseObject(response), response, scope, vendorWalletId)
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

    /** 멱등 충돌 표식이 있으면 기존 전송 ID(없으면 빈 문자열)를, 표식이 없으면 null을 돌려준다. */
    private fun idempotencyDuplicate(response: DfnsHttpResponse): String? {
        val node = runCatching { objectMapper.readTree(response.body) }.getOrNull() ?: return null
        if (!node.isObject) return null
        val duplicate = node.path("error").path("details").path("duplicate")
        if (!duplicate.isObject) return null
        return duplicate
            .path("id")
            .takeIf(JsonNode::isString)
            ?.asString()
            ?.trim()
            .orEmpty()
    }

    /** 제출 응답 정규화 — 보낸 요청과 자산·목적지·금액·제출 키까지 대조한다. */
    private fun toObservation(
        node: JsonNode,
        response: DfnsHttpResponse,
        request: NetworkTransferRequest,
    ): NetworkTransferObservation {
        val observation = toObservation(node, response, request.scope, request.vendorWalletId)
        if (observation.vendorAssetId != request.vendorAssetId ||
            observation.destinationAddress != request.destinationAddress ||
            observation.amountBaseUnits != request.amountBaseUnits
        ) {
            throw response.failure("Dfns transfer request mismatch in response")
        }
        // 우리가 보낸 제출 키를 되돌려주지 않으면 이 응답이 우리 요청의 것이라는 결속을 증명하지 못한다(계약13 수용 항목).
        if (observation.externalId != request.externalId) throw response.failure("Dfns transfer external id mismatch in response")
        return observation
    }

    /** 응답의 지갑·network가 요청 scope와 같아야 하고 명세 필수 필드가 형식대로 있어야 한다. 검사는 웹훅 사건과 같은 규칙을 쓴다. */
    private fun toObservation(
        node: JsonNode,
        response: DfnsHttpResponse,
        scope: NetworkWalletScope,
        expectedWalletId: String,
    ): NetworkTransferObservation =
        DfnsTransferRequests.normalize(
            node = node,
            network = scope.network,
            vendorNetwork = properties.networks[scope.network].orEmpty(),
            expectedWalletId = expectedWalletId,
        ) { reason, cause -> response.failure("Dfns ${response.operation} 응답 $reason", cause) }

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
