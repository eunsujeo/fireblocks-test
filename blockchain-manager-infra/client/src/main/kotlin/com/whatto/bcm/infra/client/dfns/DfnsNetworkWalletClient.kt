package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletAssetBalance
import com.whatto.bcm.domain.vendor.NetworkWalletAssetPort
import com.whatto.bcm.domain.vendor.NetworkWalletAssetSnapshot
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.vendor.NetworkWalletProvisioningPort
import com.whatto.bcm.domain.vendor.NetworkWalletResponse
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.vendor.NetworkWalletSubmissionPort
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * NetworkWalletProvisioningPort·NetworkWalletAssetPort의 Dfns 구현 — 공식 OpenAPI 1.1018.3의 `POST /wallets`·`GET /wallets/{walletId}`·`GET /wallets`·
 * `GET /wallets/{walletId}/assets`.
 * 응답은 상태와 무관하게 받은 바이트 그대로 서비스에 넘기고(V24 증적), 정규화 값은 같은 바이트에서 해석한다.
 * `DfnsClientConfig`가 `BCM_PROVIDER=dfns`에서만 실행 빈으로 등록한다 — 조건부 조립은 Baseline 수용이 아니며
 * `ProviderConfiguration`의 전체 기동 차단이 유지되므로 운영에서 실행되지 않는다(계약13).
 *
 * - create: 생성 의도에 고정한 submission의 vendorNetwork·correlationId로 본문을 만들고, 그 SHA-256이 저장된 requestHash와 같아야 보낸다.
 *   `externalId`는 상관관계 값이며 멱등 보장이 아니다. 실패·응답 유실 뒤 자동 재호출은 하지 않는다(서비스 계약).
 * - read: 404는 본문을 보존한 미관찰(null)이다. 그 밖의 오류는 수신 바이트와 함께 전파한다.
 * - candidates: 서버에 externalId 필터가 없으므로 페이지 항목을 `externalId == correlationId`로 좁힌다. 오류를 빈 페이지로 바꾸지 않고,
 *   목록 항목은 필터 전에 객체·`externalId` 형식을 검사하고, `nextPageToken`이 있으면 비어 있지 않은 문자열이어야 한다(그 밖은 오류).
 * - 정규화: 명세 `Wallet`의 필수 `id`·`network`·`signingKey.id`·`status`·`custodial`이 형식대로 있어야 한다. 선택 문자열은 없거나 문자열이어야 하며
 *   `address`·`externalId`의 빈 문자열은 null(주소 대기·상관관계 없음), minLength 1인 `delegatedTo`·`vaultId`의 빈 문자열은 오류다.
 *   소유는 `custodial=true`(명세: 조직 소유)·`signingKey.delegatedTo` 없음·`vaultId`(Vault 통제 지갑) 없음·`status=Active`일 때만 ORGANIZATION이고
 *   그 밖은 OTHER다. 판정에 필요한 필드가 결손이면 지갑을 정규화하지 않고 오류다.
 * - 네트워크: 명세 `network` 값을 설정 매핑으로 BCM 코드로 되돌리고, 매핑에 없으면 원문 값을 그대로 둬 판정에서 불일치로 드러나게 한다.
 * - assets: 명세 응답의 `walletId`가 요청 지갑과, `network` 원문이 scope 네트워크의 설정 매핑값과 같아야 하고(역변환 fallback 없음) 항목마다 필수
 *   `kind`·`decimals`·`balance`를 형식대로 검사한다.
 *   `Native`·`Erc20`·`Spl`·`Spl2022`는 자산 매핑과 같은 규칙의 키(`DfnsAssetKeys`)로 정규화하고 그 밖의 kind는 대조 대상이 아니라 제외한다.
 *   `balance`는 최소 단위 정수 문자열, `decimals`는 같은 항목의 소수 자릿수로 읽으며 다른 단위로 추정하지 않는다(계약13 수용 항목).
 */
class DfnsNetworkWalletClient(
    restClientBuilder: RestClient.Builder,
    private val properties: DfnsProperties,
    private val origin: ProviderOrigin,
    signer: DfnsCredentialSigner,
    metrics: OperationalMetricsPort,
    restClientFactory: DfnsRestClientFactory,
) : NetworkWalletProvisioningPort,
    NetworkWalletSubmissionPort,
    NetworkWalletAssetPort {
    private val objectMapper = ObjectMapper()
    private val http = DfnsHttp(restClientFactory.create(restClientBuilder, properties), properties, metrics)
    private val userActions = DfnsUserActionClient(http, signer, objectMapper)

    init {
        require(origin.protocolProvider == "dfns") { "DfnsNetworkWalletClient requires the Dfns origin" }
    }

    /** 의도에 고정할 제출 snapshot — 같은 요청에는 같은 값이라 재요청이 기존 의도에 합류한다. 매핑 없는 네트워크는 null이다. */
    override fun submission(request: NetworkWalletCreationRequest): NetworkWalletSubmissionSpec? {
        val vendorNetwork = properties.networks[request.scope.network] ?: return null
        return NetworkWalletSubmissionSpec(
            requestHash(createWalletBody(vendorNetwork, request.correlationId)),
            REQUEST_VERSION,
            vendorNetwork,
        )
    }

    override fun create(
        request: NetworkWalletCreationRequest,
        submission: NetworkWalletSubmissionSpec,
    ): NetworkWalletResponse<NetworkWalletObservation> {
        origin.requireMatch(request.scope.origin)
        val body = createWalletBody(submission.vendorNetwork, request.correlationId)
        check(requestHash(body) == submission.requestHash) { "Dfns create body does not match the stored intent request hash" }
        val userAction = userActions.userAction(HttpMethod.POST, WALLETS_PATH, body)
        val response = http.call(CREATE_OPERATION, HttpMethod.POST, body, userAction) { it.path(WALLETS_PATH).build() }
        val bytes = response.requireSuccess()
        return NetworkWalletResponse(toObservation(parseObject(response), response), bytes)
    }

    override fun read(
        scope: NetworkWalletScope,
        vendorWalletId: String,
    ): NetworkWalletResponse<NetworkWalletObservation?> {
        origin.requireMatch(scope.origin)
        requireWalletId(vendorWalletId)
        val response = http.call(READ_OPERATION, HttpMethod.GET) { it.path("$WALLETS_PATH/{walletId}").build(vendorWalletId) }
        if (response.status == HTTP_NOT_FOUND) return NetworkWalletResponse(null, response.body)
        val bytes = response.requireSuccess()
        val wallet = toObservation(parseObject(response), response)
        if (wallet.vendorWalletId != vendorWalletId) throw response.failure("Dfns wallet id mismatch in read response")
        return NetworkWalletResponse(wallet, bytes)
    }

    override fun candidates(
        request: NetworkWalletCreationRequest,
        pageCursor: String?,
    ): NetworkWalletResponse<VendorPage<NetworkWalletObservation>> {
        origin.requireMatch(request.scope.origin)
        pageCursor?.let { require(it.isNotBlank() && it == it.trim()) { "Invalid Dfns pagination token" } }
        val response =
            http.call(DISCOVER_OPERATION, HttpMethod.GET) { builder ->
                builder.path(WALLETS_PATH).queryParam("limit", "{limit}")
                if (pageCursor != null) builder.queryParam("paginationToken", "{token}")
                val variables: Array<Any> =
                    if (pageCursor == null) arrayOf(properties.candidatePageSize) else arrayOf(properties.candidatePageSize, pageCursor)
                builder.build(*variables)
            }
        val bytes = response.requireSuccess()
        val node = parseObject(response)
        val items = node.path("items")
        if (!items.isArray) throw response.failure("Dfns $DISCOVER_OPERATION 응답 결손: items")
        // 항목 형식은 필터 전에 검사한다 — 형식이 깨진 항목을 버리고 나머지로 완료 연결하지 않는다(계약13).
        items.forEach { item ->
            if (!item.isObject) throw response.failure("Dfns $DISCOVER_OPERATION 응답 필드 형식 오류: items[]")
            optionalText(item, "externalId", response)
        }
        val matches =
            items
                .filter { optionalText(it, "externalId", response) == request.correlationId }
                .map { toObservation(it, response) }
        // 응답 nextPageToken은 재요청 paginationToken(minLength 1)이 되므로 빈 값은 재개할 수 없다 — 조회 끝으로 오해하지 않고 오류다.
        return NetworkWalletResponse(VendorPage(matches, nonBlankText(node, "nextPageToken", response)), bytes)
    }

    override fun assets(
        scope: NetworkWalletScope,
        vendorWalletId: String,
    ): NetworkWalletAssetSnapshot {
        origin.requireMatch(scope.origin)
        requireWalletId(vendorWalletId)
        val response =
            http.call(ASSETS_OPERATION, HttpMethod.GET) { it.path("$WALLETS_PATH/{walletId}/assets").build(vendorWalletId) }
        response.requireSuccess()
        val node = parseObject(response)
        val walletId = requiredText(node, "walletId", response)
        if (walletId != vendorWalletId) throw response.failure("Dfns wallet id mismatch in assets response")
        // scope의 설정 매핑값과 응답 network 원문을 직접 대조한다 — 매핑 없는 원문을 BCM 코드로 되돌리는 관찰용 fallback을 여기서는 쓰지 않는다.
        val vendorNetwork = requiredText(node, "network", response)
        if (vendorNetwork != properties.networks[scope.network]) throw response.failure("Dfns wallet network mismatch in assets response")
        val items = node.path("assets")
        if (!items.isArray) throw response.failure("Dfns $ASSETS_OPERATION 응답 결손: assets")
        val balances =
            items.mapNotNull { item ->
                if (!item.isObject) throw response.failure("Dfns $ASSETS_OPERATION 응답 필드 형식 오류: assets[]")
                val kind = requiredText(item, "kind", response)
                if (!DfnsAssetKeys.isModeled(kind)) return@mapNotNull null
                val locator = DfnsAssetKeys.locatorField(kind)?.let { requiredText(item, it, response) }
                val decimals = item.path("decimals")
                if (!decimals.isIntegralNumber || !decimals.canConvertToInt()) {
                    throw response.failure("Dfns $ASSETS_OPERATION 응답 필드 형식 오류: decimals")
                }
                val verified = item.path("verified")
                val verifiedValue =
                    when {
                        verified.isMissingNode || verified.isNull -> null
                        verified.isBoolean -> verified.asBoolean()
                        else -> throw response.failure("Dfns $ASSETS_OPERATION 응답 필드 형식 오류: verified")
                    }
                try {
                    NetworkWalletAssetBalance(
                        checkNotNull(DfnsAssetKeys.of(vendorNetwork, kind, locator)),
                        optionalText(item, "symbol", response),
                        decimals.asInt(),
                        requiredText(item, "balance", response),
                        verifiedValue,
                    )
                } catch (exception: IllegalArgumentException) {
                    throw response.failure("Dfns $ASSETS_OPERATION 응답 필드 형식 오류", exception)
                }
            }
        return NetworkWalletAssetSnapshot(walletId, scope.network, balances)
    }

    private fun requireWalletId(vendorWalletId: String) {
        require(
            vendorWalletId.isNotBlank() &&
                vendorWalletId == vendorWalletId.trim() &&
                vendorWalletId.all { it.isLetterOrDigit() || it == '-' },
        ) {
            "Invalid Dfns wallet id"
        }
    }

    /** 명세 network 값 → BCM 코드. 매핑에 없으면 원문 값을 그대로 둬 판정에서 불일치로 드러나게 한다. */
    private fun bcmNetwork(vendorNetwork: String): String =
        properties.networks.entries
            .firstOrNull { it.value == vendorNetwork }
            ?.key ?: vendorNetwork

    private fun toObservation(
        node: JsonNode,
        response: DfnsHttpResponse,
    ): NetworkWalletObservation {
        val id = requiredText(node, "id", response)
        val vendorNetwork = requiredText(node, "network", response)
        val signingKey = node.path("signingKey")
        if (!signingKey.isObject) throw response.failure("Dfns ${response.operation} 응답 결손: signingKey")
        requiredText(signingKey, "id", response)
        val status = requiredText(node, "status", response)
        val custodialNode = node.path("custodial")
        if (!custodialNode.isBoolean) throw response.failure("Dfns ${response.operation} 응답 결손: custodial")
        val delegatedTo = nonBlankText(signingKey, "delegatedTo", response)
        val vaultId = nonBlankText(node, "vaultId", response)
        val ownership =
            if (custodialNode.asBoolean() && delegatedTo == null && vaultId == null && status == ACTIVE_STATUS) {
                NetworkWalletOwnership.ORGANIZATION
            } else {
                NetworkWalletOwnership.OTHER
            }
        return try {
            NetworkWalletObservation(
                origin,
                bcmNetwork(vendorNetwork),
                id,
                optionalText(node, "externalId", response),
                ownership,
                optionalText(node, "address", response),
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

    /**
     * 선택 문자열(`address`·`externalId` — 명세에 minLength 없음). 없거나 null이거나 빈 문자열이면 null(주소 대기·상관관계 없음),
     * 문자열이 아니면 형식 오류다.
     */
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

    /** 명세가 minLength 1을 두는 선택 문자열(`delegatedTo`·`vaultId`)과 재요청 토큰 — 있으면 비어 있지 않은 문자열이어야 한다. */
    private fun nonBlankText(
        node: JsonNode,
        field: String,
        response: DfnsHttpResponse,
    ): String? {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) return null
        if (!value.isString || value.asString().isBlank()) throw response.failure("Dfns ${response.operation} 응답 필드 형식 오류: $field")
        return value.asString()
    }

    companion object {
        const val WALLETS_PATH = "/wallets"

        /** 생성 의도에 저장하는 요청 계약 버전 — 채택한 공식 OpenAPI 버전(계약13). */
        const val REQUEST_VERSION = "dfns-openapi-1.1018.3"
        private const val CREATE_OPERATION = "dfnsCreateWallet"
        private const val READ_OPERATION = "dfnsGetWallet"
        private const val DISCOVER_OPERATION = "dfnsListWallets"
        private const val ASSETS_OPERATION = "dfnsGetWalletAssets"
        private const val ACTIVE_STATUS = "Active"
        private const val HTTP_NOT_FOUND = 404
        private const val EXTERNAL_ID_MAX_LENGTH = 100

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
    }
}
