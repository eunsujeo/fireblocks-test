package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.vendor.NetworkWalletProvisioningPort
import com.whatto.bcm.domain.vendor.NetworkWalletResponse
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * NetworkWalletProvisioningPort의 Dfns 구현 — 공식 OpenAPI 1.1018.3의 `POST /wallets`·`GET /wallets/{walletId}`·`GET /wallets`.
 * 응답은 상태와 무관하게 받은 바이트 그대로 서비스에 넘기고(V24 증적), 정규화 값은 같은 바이트에서 해석한다.
 * 실행 빈으로 등록하지 않는다 — `BCM_PROVIDER=dfns` 기동 차단과 Baseline 수용은 별개다(계약13).
 *
 * - create: 생성 의도에 고정한 submission의 vendorNetwork·correlationId로 본문을 만들고, 그 SHA-256이 저장된 requestHash와 같아야 보낸다.
 *   `externalId`는 상관관계 값이며 멱등 보장이 아니다. 실패·응답 유실 뒤 자동 재호출은 하지 않는다(서비스 계약).
 * - read: 404는 본문을 보존한 미관찰(null)이다. 그 밖의 오류는 전파한다.
 * - candidates: 서버에 externalId 필터가 없으므로 페이지 항목을 `externalId == correlationId`로 좁힌다. 오류를 빈 페이지로 바꾸지 않는다.
 * - 소유: 명세의 `custodial`(조직 소유=true)·`signingKey.delegatedTo`·`vaultId`(Vault 통제 지갑)·`status`(Active만 사용 가능 자원)로
 *   조직이 사용할 수 있는 소유 자원인지 판정한다. 확인할 수 없으면 UNVERIFIED다.
 * - 네트워크: 명세 `network` 값을 설정 매핑으로 BCM 코드로 되돌리고, 매핑에 없으면 원문 값을 그대로 둬 판정에서 불일치로 드러나게 한다.
 */
class DfnsNetworkWalletClient(
    restClientBuilder: RestClient.Builder,
    private val properties: DfnsProperties,
    private val origin: ProviderOrigin,
    signer: DfnsCredentialSigner,
    metrics: OperationalMetricsPort,
    restClientFactory: DfnsRestClientFactory,
) : NetworkWalletProvisioningPort {
    private val objectMapper = ObjectMapper()
    private val http = DfnsHttp(restClientFactory.create(restClientBuilder, properties), properties, metrics)
    private val userActions = DfnsUserActionClient(http, signer, objectMapper)

    init {
        require(origin.protocolProvider == "dfns") { "DfnsNetworkWalletClient requires the Dfns origin" }
    }

    override fun create(
        request: NetworkWalletCreationRequest,
        submission: NetworkWalletSubmissionSpec,
    ): NetworkWalletResponse<NetworkWalletObservation> {
        origin.requireMatch(request.scope.origin)
        val body = createWalletBody(submission.vendorNetwork, request.correlationId)
        check(requestHash(body) == submission.requestHash) { "Dfns create body does not match the stored intent request hash" }
        val userAction = userActions.userAction(HttpMethod.POST, WALLETS_PATH, body)
        val response = http.call(CREATE_OPERATION, HttpMethod.POST, WALLETS_PATH, body, userAction)
        val bytes = response.requireSuccess(CREATE_OPERATION)
        return NetworkWalletResponse(toObservation(parse(CREATE_OPERATION, bytes), CREATE_OPERATION), bytes)
    }

    override fun read(
        scope: NetworkWalletScope,
        vendorWalletId: String,
    ): NetworkWalletResponse<NetworkWalletObservation?> {
        origin.requireMatch(scope.origin)
        require(
            vendorWalletId.isNotBlank() &&
                vendorWalletId == vendorWalletId.trim() &&
                vendorWalletId.all { it.isLetterOrDigit() || it == '-' },
        ) {
            "Invalid Dfns wallet id"
        }
        val response = http.call(READ_OPERATION, HttpMethod.GET, "$WALLETS_PATH/$vendorWalletId")
        if (response.status == HTTP_NOT_FOUND) return NetworkWalletResponse(null, response.body)
        val bytes = response.requireSuccess(READ_OPERATION)
        val wallet = toObservation(parse(READ_OPERATION, bytes), READ_OPERATION)
        if (wallet.vendorWalletId != vendorWalletId) {
            throw VendorApiException(READ_OPERATION, response.status, IllegalStateException("Dfns wallet id mismatch in read response"))
        }
        return NetworkWalletResponse(wallet, bytes)
    }

    override fun candidates(
        request: NetworkWalletCreationRequest,
        pageCursor: String?,
    ): NetworkWalletResponse<VendorPage<NetworkWalletObservation>> {
        origin.requireMatch(request.scope.origin)
        val path =
            UriComponentsBuilder
                .fromPath(WALLETS_PATH)
                .queryParam("limit", properties.candidatePageSize)
                .apply { pageCursor?.let { queryParam("paginationToken", it) } }
                .build()
                .encode()
                .toUriString()
        val bytes = http.call(DISCOVER_OPERATION, HttpMethod.GET, path).requireSuccess(DISCOVER_OPERATION)
        val node = parse(DISCOVER_OPERATION, bytes)
        val items = node.path("items")
        if (!items.isArray) throw VendorApiException(DISCOVER_OPERATION, HTTP_OK, IllegalStateException("Dfns listWallets 응답 결손: items"))
        val matches =
            items
                .filter { it.path("externalId").let { value -> value.isString && value.asString() == request.correlationId } }
                .map { toObservation(it, DISCOVER_OPERATION) }
        val next = node.path("nextPageToken").takeIf { it.isString && it.asString().isNotBlank() }?.asString()
        return NetworkWalletResponse(VendorPage(matches, next), bytes)
    }

    private fun toObservation(
        node: JsonNode,
        operation: String,
    ): NetworkWalletObservation {
        val id = requiredText(node, "id", operation)
        val vendorNetwork = requiredText(node, "network", operation)
        val custodial = node.path("custodial").takeIf(JsonNode::isBoolean)?.asBoolean()
        val status = node.path("status").takeIf(JsonNode::isString)?.asString()
        val delegated = node.path("signingKey").path("delegatedTo").isString
        val vaultControlled = node.path("vaultId").isString
        val ownership =
            when {
                custodial == null || status == null -> NetworkWalletOwnership.UNVERIFIED
                custodial && !delegated && !vaultControlled && status == ACTIVE_STATUS -> NetworkWalletOwnership.ORGANIZATION
                else -> NetworkWalletOwnership.OTHER
            }
        return try {
            NetworkWalletObservation(
                origin,
                properties.networks.entries
                    .firstOrNull { it.value == vendorNetwork }
                    ?.key ?: vendorNetwork,
                id,
                node
                    .path("externalId")
                    .takeIf(JsonNode::isString)
                    ?.asString()
                    ?.takeIf(String::isNotBlank),
                ownership,
                node
                    .path("address")
                    .takeIf(JsonNode::isString)
                    ?.asString()
                    ?.takeIf(String::isNotBlank),
            )
        } catch (exception: IllegalArgumentException) {
            throw VendorApiException(operation, HTTP_OK, exception)
        }
    }

    private fun parse(
        operation: String,
        body: ByteArray,
    ): JsonNode {
        val node =
            try {
                objectMapper.readTree(body)
            } catch (exception: RuntimeException) {
                throw VendorApiException(operation, HTTP_OK, exception)
            }
        if (!node.isObject) throw VendorApiException(operation, HTTP_OK, IllegalStateException("Dfns $operation 응답이 JSON 객체가 아니다"))
        return node
    }

    private fun requiredText(
        node: JsonNode,
        field: String,
        operation: String,
    ): String {
        val value = node.path(field)
        if (!value.isString || value.asString().isBlank()) {
            throw VendorApiException(operation, HTTP_OK, IllegalStateException("Dfns $operation 응답 결손: $field"))
        }
        return value.asString()
    }

    companion object {
        const val WALLETS_PATH = "/wallets"
        private const val CREATE_OPERATION = "dfnsCreateWallet"
        private const val READ_OPERATION = "dfnsGetWallet"
        private const val DISCOVER_OPERATION = "dfnsListWallets"
        private const val ACTIVE_STATUS = "Active"
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404

        /** 생성 의도에 저장할 요청 본문 — 명세 Create Wallet의 `network`(필수)·`externalId`(≤100자). 실행 시 같은 바이트를 보낸다. */
        fun createWalletBody(
            vendorNetwork: String,
            correlationId: String,
        ): ByteArray {
            require(vendorNetwork.isNotBlank() && vendorNetwork == vendorNetwork.trim()) { "Invalid Dfns network" }
            require(correlationId.isNotBlank() && correlationId == correlationId.trim() && correlationId.length <= EXTERNAL_ID_MAX_LENGTH) {
                "Invalid Dfns externalId"
            }
            return ObjectMapper().writeValueAsBytes(linkedMapOf("network" to vendorNetwork, "externalId" to correlationId))
        }

        /** 저장 의도의 requestHash — 위 본문 바이트의 SHA-256 소문자 hex. */
        fun requestHash(body: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

        private const val EXTERNAL_ID_MAX_LENGTH = 100
    }
}
