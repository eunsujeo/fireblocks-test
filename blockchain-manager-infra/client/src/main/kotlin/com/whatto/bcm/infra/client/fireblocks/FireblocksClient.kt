package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.VendorAsset
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.domain.vendor.VendorBalance
import com.whatto.bcm.domain.vendor.VendorBlockchain
import com.whatto.bcm.domain.vendor.VendorBlockchainOnchain
import com.whatto.bcm.domain.vendor.VendorContractCall
import com.whatto.bcm.domain.vendor.VendorContractCallPort
import com.whatto.bcm.domain.vendor.VendorContractCallRequest
import com.whatto.bcm.domain.vendor.VendorDepositAddress
import com.whatto.bcm.domain.vendor.VendorNetworkRecord
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionLifecycleStage
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletVendorPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper

/**
 * WalletVendorPort 의 Fireblocks 구현 — 인증(JWT+X-API-Key)·멱등 헤더·429 백오프·도메인 예외 변환.
 * 엔드포인트 근거: fireblocks-openapi-spec api-spec-v2.yaml (2026-08-05 확인).
 * 429 는 Retry-After 존중 + 지수 백오프 보조 (90-QnA rate limit 확답).
 */
@Component
class FireblocksClient(
    restClientBuilder: RestClient.Builder,
    private val properties: FireblocksProperties,
    private val signer: FireblocksJwtSigner,
    restClientFactory: FireblocksRestClientFactory,
) : WalletVendorPort,
    VendorAssetCatalogPort,
    VendorTransactionPort,
    VendorContractCallPort {
    private val restClient = restClientFactory.create(restClientBuilder, properties)
    private val objectMapper = ObjectMapper()

    override fun createVault(
        name: String,
        idempotencyKey: String,
    ): VendorVault {
        val response =
            exchange(
                operation = "createVault",
                method = HttpMethod.POST,
                path = "/v1/vault/accounts",
                body = mapOf("name" to name),
                idempotencyKey = idempotencyKey,
                responseType = VaultAccountResponse::class.java,
            )
        return VendorVault(
            vaultId = requireNotNull(response.id) { "createVault 응답 결손: id" },
            name = requireNotNull(response.name) { "createVault 응답 결손: name" },
        )
    }

    override fun createDepositAddress(
        vaultId: String,
        assetSymbol: String,
        idempotencyKey: String,
    ): VendorDepositAddress {
        val response =
            exchange(
                operation = "createDepositAddress",
                method = HttpMethod.POST,
                path = "/v1/vault/accounts/$vaultId/$assetSymbol",
                body = null,
                idempotencyKey = idempotencyKey,
                responseType = CreateVaultAssetResponse::class.java,
            )
        return VendorDepositAddress(
            address = requireNotNull(response.address) { "createDepositAddress 응답 결손: address" },
            tag = response.tag,
        )
    }

    override fun balanceOf(
        vaultId: String,
        assetSymbol: String,
    ): VendorBalance {
        val response =
            exchange(
                operation = "balanceOf",
                method = HttpMethod.GET,
                path = "/v1/vault/accounts/$vaultId/$assetSymbol",
                body = null,
                idempotencyKey = null,
                responseType = VaultAssetResponse::class.java,
            )
        return VendorBalance(
            total = requireNotNull(response.total) { "balanceOf 응답 결손: total" },
            available = requireNotNull(response.available) { "balanceOf 응답 결손: available" },
            pending = requireNotNull(response.pending) { "balanceOf 응답 결손: pending" },
            frozen = requireNotNull(response.frozen) { "balanceOf 응답 결손: frozen" },
            lockedAmount = requireNotNull(response.lockedAmount) { "balanceOf 응답 결손: lockedAmount" },
        )
    }

    override fun blockchains(pageCursor: String?): VendorPage<VendorBlockchain> {
        val path =
            UriComponentsBuilder
                .fromPath("/v1/blockchains")
                .queryParam("pageSize", BLOCKCHAIN_PAGE_SIZE)
                .apply { pageCursor?.let { queryParam("pageCursor", it) } }
                .build()
                .encode()
                .toUriString()
        val response =
            exchange(
                operation = "listBlockchains",
                method = HttpMethod.GET,
                path = path,
                body = null,
                idempotencyKey = null,
                responseType = BlockchainListResponse::class.java,
            )
        return VendorPage(response.data.map(::toDomain), response.next)
    }

    override fun assets(
        blockchainId: String,
        symbol: String?,
        pageCursor: String?,
    ): VendorPage<VendorAsset> {
        val path =
            UriComponentsBuilder
                .fromPath("/v1/assets")
                .queryParam("blockchainId", blockchainId)
                .queryParam("pageSize", ASSET_PAGE_SIZE)
                .apply {
                    symbol?.let { queryParam("symbol", it) }
                    pageCursor?.let { queryParam("pageCursor", it) }
                }.build()
                .encode()
                .toUriString()
        val response =
            exchange(
                operation = "listAssets",
                method = HttpMethod.GET,
                path = path,
                body = null,
                idempotencyKey = null,
                responseType = AssetListResponse::class.java,
            )
        return VendorPage(response.data.map(::toDomain), response.next)
    }

    override fun submitTransaction(request: VendorTransactionRequest): VendorTransactionSubmission =
        try {
            val response =
                exchange(
                    operation = "submitTransaction",
                    method = HttpMethod.POST,
                    path = TRANSACTIONS_PATH,
                    body = transactionBody(request),
                    idempotencyKey = null,
                    responseType = CreateTransactionResponse::class.java,
                )
            VendorTransactionSubmission.Accepted(
                transactionId = requireNotNull(response.id) { "submitTransaction 응답 결손: id" },
            )
        } catch (exception: VendorApiException) {
            // 400 은 중복 제출과 그 밖의 검증 실패를 오류 코드 번호로 가르지 않는다 — 확답받은 근거가 없다.
            // 판정은 호출 쪽이 externalTxId 조회로 한다 (02-bcm-flow 벤더 응답별 처리).
            when {
                exception.httpStatus == HttpStatus.BAD_REQUEST.value() -> {
                    VendorTransactionSubmission.BadRequestNeedsLookup(exception)
                }

                isDefinitiveTransactionRejection(exception.httpStatus) -> {
                    throw RelayRejectedException("transaction rejected", exception)
                }

                else -> {
                    throw exception
                }
            }
        }

    override fun submitContractCall(request: VendorContractCallRequest): VendorTransactionSubmission =
        try {
            val response =
                exchange(
                    operation = "submitContractCall",
                    method = HttpMethod.POST,
                    path = TRANSACTIONS_PATH,
                    body = contractCallBody(request),
                    idempotencyKey = null,
                    responseType = CreateTransactionResponse::class.java,
                )
            VendorTransactionSubmission.Accepted(
                transactionId = requireNotNull(response.id) { "submitContractCall 응답 결손: id" },
            )
        } catch (exception: VendorApiException) {
            when {
                exception.httpStatus == HttpStatus.BAD_REQUEST.value() ->
                    VendorTransactionSubmission.BadRequestNeedsLookup(exception)

                isDefinitiveTransactionRejection(exception.httpStatus) ->
                    throw RelayRejectedException("contract call rejected", exception)

                else -> throw exception
            }
        }

    override fun contractCallByExternalTransactionId(externalTransactionId: String): VendorContractCall? =
        transactionResponseOrNull(
            operation = "contractCallByExternalTransactionId",
            path = encodedPath("$TRANSACTIONS_PATH/external_tx_id/{id}", externalTransactionId),
        )?.let { response ->
            VendorContractCall(
                transactionId = requireNotNull(response.id) { "contract call 응답 결손: id" },
                externalTransactionId = response.externalTxId?.takeIf(String::isNotBlank),
                sourceVaultId = response.source?.id?.takeIf(String::isNotBlank),
                contractAddress = response.destinationAddress?.takeIf(String::isNotBlank),
                callData = response.extraParameters?.contractCallData?.takeIf(String::isNotBlank),
            )
        }

    override fun transaction(transactionId: String): VendorTransaction? =
        transactionOrNull(
            operation = "transaction",
            path = encodedPath("$TRANSACTIONS_PATH/{id}", transactionId),
        )

    override fun transactionByExternalTransactionId(externalTransactionId: String): VendorTransaction? =
        transactionOrNull(
            operation = "transactionByExternalTransactionId",
            path = encodedPath("$TRANSACTIONS_PATH/external_tx_id/{id}", externalTransactionId),
        )

    override fun transactions(request: VendorTransactionPageRequest): VendorPage<VendorTransaction> {
        val path = transactionPagePath(request)
        val response =
            exchangeEntity(
                operation = "transactions",
                method = HttpMethod.GET,
                path = path,
                body = null,
                idempotencyKey = null,
                responseType = Array<TransactionResponse>::class.java,
            )
        val body = checkNotNull(response.body) { "transactions 응답 본문 없음" }
        val transactions = body.map(::toDomain)
        val outOfScope =
            transactions.firstOrNull {
                it.source.type != "VAULT_ACCOUNT" || it.source.id != request.sourceVaultId
            }
        if (outOfScope != null) {
            throw VendorApiException(
                operation = "transactions",
                httpStatus = null,
                cause =
                    IllegalStateException(
                        "transaction source outside requested vault: transactionId=${outOfScope.transactionId}",
                    ),
            )
        }
        return VendorPage(
            data = transactions,
            next = response.headers.getFirst(NEXT_PAGE_HEADER),
        )
    }

    private fun transactionOrNull(
        operation: String,
        path: String,
    ): VendorTransaction? = transactionResponseOrNull(operation, path)?.let(::toDomain)

    private fun transactionResponseOrNull(
        operation: String,
        path: String,
    ): TransactionResponse? =
        try {
            exchange(
                operation = operation,
                method = HttpMethod.GET,
                path = path,
                body = null,
                idempotencyKey = null,
                responseType = TransactionResponse::class.java,
            )
        } catch (exception: VendorApiException) {
            if (exception.httpStatus == HttpStatus.NOT_FOUND.value()) null else throw exception
        }

    private fun transactionBody(request: VendorTransactionRequest): Map<String, Any> =
        linkedMapOf<String, Any>(
            "operation" to "TRANSFER",
            "externalTxId" to request.externalTransactionId,
            "assetId" to request.vendorAssetId,
            "source" to mapOf("type" to "VAULT_ACCOUNT", "id" to request.sourceVaultId),
            "destination" to transactionDestination(request.destination),
            "amount" to request.amount,
            "useGasless" to request.useGasless,
        ).apply {
            request.note?.let { put("note", it) }
            request.travelRuleMessage?.let { put("travelRuleMessage", it) }
            request.replaceTransactionHash?.let { put("replaceTxByHash", it) }
            request.feeLevel?.let { put("feeLevel", it.name) }
        }

    private fun contractCallBody(request: VendorContractCallRequest): Map<String, Any> =
        linkedMapOf(
            "operation" to "CONTRACT_CALL",
            "externalTxId" to request.externalTransactionId,
            "assetId" to
                checkNotNull(properties.contractCallGasAssetIds[request.network]) {
                    "Fireblocks CONTRACT_CALL gas assetId is not configured: network=${request.network}"
                },
            "source" to mapOf("type" to "VAULT_ACCOUNT", "id" to request.sourceVaultId),
            "destination" to
                mapOf(
                    "type" to "ONE_TIME_ADDRESS",
                    "oneTimeAddress" to mapOf("address" to request.contractAddress),
                ),
            "amount" to "0",
            "useGasless" to request.useGasless,
            "extraParameters" to mapOf("contractCallData" to request.callData),
        )

    private fun transactionDestination(destination: VendorTransactionDestination): Map<String, Any> =
        when (destination) {
            is VendorTransactionDestination.Address -> {
                mapOf(
                    "type" to "ONE_TIME_ADDRESS",
                    "oneTimeAddress" to mapOf("address" to destination.address),
                )
            }

            is VendorTransactionDestination.Account -> {
                mapOf("type" to "VAULT_ACCOUNT", "id" to destination.vaultId)
            }

            is VendorTransactionDestination.Whitelisted -> {
                mapOf("type" to "EXTERNAL_WALLET", "id" to destination.walletId)
            }
        }

    private fun transactionPagePath(request: VendorTransactionPageRequest): String =
        UriComponentsBuilder
            .fromPath(TRANSACTIONS_PATH)
            .apply {
                request.cursor?.let { queryParam("next", it) }
                request.afterEpochMillis?.let { queryParam("after", it) }
                request.beforeEpochMillis?.let { queryParam("before", it) }
                request.vendorStatus?.let { queryParam("status", it) }
                queryParam("sort", request.order.name)
                queryParam("limit", request.limit)
                queryParam("sourceType", "VAULT_ACCOUNT")
                queryParam("sourceId", request.sourceVaultId)
            }.build()
            .encode()
            .toUriString()

    private fun encodedPath(
        template: String,
        id: String,
    ): String =
        UriComponentsBuilder
            .fromPath(template)
            .buildAndExpand(id)
            .encode()
            .toUriString()

    private fun isDefinitiveTransactionRejection(httpStatus: Int?): Boolean = httpStatus in DEFINITIVE_TRANSACTION_REJECTION_STATUSES

    private fun toDomain(response: BlockchainResponse): VendorBlockchain =
        VendorBlockchain(
            id = requireNotNull(response.id) { "blockchain 응답 결손: id" },
            displayName = requireNotNull(response.displayName) { "blockchain 응답 결손: displayName" },
            deprecated = response.metadata?.deprecated ?: false,
            onchain =
                response.onchain?.let {
                    VendorBlockchainOnchain(
                        protocol = it.protocol,
                        chainId = it.chainId,
                        test = it.test,
                        signingAlgo = it.signingAlgo,
                    )
                },
        )

    private fun toDomain(response: AssetResponse): VendorAsset =
        VendorAsset(
            id = requireNotNull(response.id) { "asset 응답 결손: id" },
            blockchainId = requireNotNull(response.blockchainId) { "asset 응답 결손: blockchainId" },
            displayName = response.displayName,
            displaySymbol = requireNotNull(response.displaySymbol) { "asset 응답 결손: displaySymbol" },
            decimals = response.onchain?.decimals ?: response.decimals,
            assetClass = response.assetClass,
            contractAddress = response.onchain?.address,
        )

    private fun toDomain(response: TransactionResponse): VendorTransaction =
        VendorTransaction(
            transactionId = requireNotNull(response.id) { "transaction 응답 결손: id" },
            externalTransactionId = response.externalTxId?.takeIf(String::isNotBlank),
            vendorAssetId = requireNotNull(response.assetId) { "transaction 응답 결손: assetId" },
            rawStatus = requireNotNull(response.status) { "transaction 응답 결손: status" },
            subStatus = response.subStatus?.takeIf(String::isNotBlank),
            transactionHash = response.txHash?.takeIf(String::isNotBlank),
            source = toDomain(requireNotNull(response.source) { "transaction 응답 결손: source" }),
            destination =
                toDomain(requireNotNull(response.destination) { "transaction 응답 결손: destination" }),
            sourceAddress = response.sourceAddress?.takeIf(String::isNotBlank),
            destinationAddress = response.destinationAddress?.takeIf(String::isNotBlank),
            amount =
                requireNotNull(response.amountInfo?.amount) {
                    "transaction 응답 결손: amountInfo.amount"
                },
            confirmationCount = response.numOfConfirmations ?: 0,
            createdAtEpochMillis = requireNotNull(response.createdAt) { "transaction 응답 결손: createdAt" },
            lastUpdatedEpochMillis =
                requireNotNull(response.lastUpdated) { "transaction 응답 결손: lastUpdated" },
            lifecycleStage = lifecycleStage(response.status),
            networkRecords = response.networkRecords.orEmpty().map(::toDomain),
        )

    private fun toDomain(response: TransactionNetworkRecordResponse): VendorNetworkRecord =
        VendorNetworkRecord(
            type = requireNotNull(response.type) { "network record 응답 결손: type" },
            source = toDomain(requireNotNull(response.source) { "network record 응답 결손: source" }),
            destination =
                toDomain(requireNotNull(response.destination) { "network record 응답 결손: destination" }),
            destinationAddress = response.destinationAddress?.takeIf(String::isNotBlank),
            transactionHash = response.txHash?.takeIf(String::isNotBlank),
            vendorAssetId = requireNotNull(response.assetId) { "network record 응답 결손: assetId" },
            netAmount = requireNotNull(response.netAmount) { "network record 응답 결손: netAmount" },
            dropped = requireNotNull(response.isDropped) { "network record 응답 결손: isDropped" },
        )

    private fun lifecycleStage(status: String?): VendorTransactionLifecycleStage =
        when (status) {
            "SUBMITTED", "PENDING_SIGNATURE", "QUEUED", "BROADCASTING" ->
                VendorTransactionLifecycleStage.PRE_CHAIN

            "CONFIRMING" -> VendorTransactionLifecycleStage.CONFIRMING
            "COMPLETED", "FAILED", "REJECTED", "BLOCKED" -> VendorTransactionLifecycleStage.TERMINAL
            else -> VendorTransactionLifecycleStage.UNKNOWN
        }

    private fun toDomain(response: TransactionPeerResponse): VendorTransactionPeer =
        VendorTransactionPeer(
            type = requireNotNull(response.type) { "transaction peer 응답 결손: type" },
            id = response.id?.takeIf(String::isNotBlank),
        )

    private fun <T : Any> exchange(
        operation: String,
        method: HttpMethod,
        path: String,
        body: Any?,
        idempotencyKey: String?,
        responseType: Class<T>,
    ): T =
        checkNotNull(
            exchangeEntity(operation, method, path, body, idempotencyKey, responseType).body,
        ) { "$operation 응답 본문 없음" }

    private fun <T : Any> exchangeEntity(
        operation: String,
        method: HttpMethod,
        path: String,
        body: Any?,
        idempotencyKey: String?,
        responseType: Class<T>,
    ): ResponseEntity<T> {
        val bodyBytes = body?.let(objectMapper::writeValueAsBytes)
        var attempt = 1
        while (true) {
            try {
                return call(method, path, bodyBytes, idempotencyKey, responseType)
            } catch (exception: HttpClientErrorException.TooManyRequests) {
                if (attempt >= properties.maxAttempts) {
                    throw VendorApiException(operation, exception.statusCode.value(), exception)
                }
                Thread.sleep(retryDelayMillis(exception, attempt))
                attempt++
            } catch (exception: RestClientResponseException) {
                throw VendorApiException(operation, exception.statusCode.value(), exception)
            } catch (exception: RestClientException) {
                throw VendorApiException(operation, null, exception)
            }
        }
    }

    private fun <T : Any> call(
        method: HttpMethod,
        path: String,
        bodyBytes: ByteArray?,
        idempotencyKey: String?,
        responseType: Class<T>,
    ): ResponseEntity<T> {
        // 시도마다 새로 서명한다 — nonce 는 요청마다 유일해야 한다 (벤더 JWT 계약)
        val jwt = signer.sign(path, bodyBytes ?: ByteArray(0))
        var spec: RestClient.RequestBodySpec =
            restClient
                .method(method)
                .uri(path)
                .header("X-API-Key", properties.apiKey)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $jwt")
        if (idempotencyKey != null) {
            spec = spec.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        }
        if (bodyBytes != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(bodyBytes)
        }
        return spec.retrieve().toEntity(responseType)
    }

    private fun retryDelayMillis(
        exception: RestClientResponseException,
        attempt: Int,
    ): Long {
        val retryAfterSeconds =
            exception.responseHeaders
                ?.getFirst(HttpHeaders.RETRY_AFTER)
                ?.toLongOrNull()
        val delayMillis = retryAfterSeconds?.times(1000) ?: (properties.retryBackoffMillis shl (attempt - 1))
        return delayMillis.coerceAtMost(properties.maxBackoffMillis)
    }

    companion object {
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        private const val BLOCKCHAIN_PAGE_SIZE = 500
        private const val ASSET_PAGE_SIZE = 1000
        private const val TRANSACTIONS_PATH = "/v1/transactions"
        private const val NEXT_PAGE_HEADER = "next-page"
        private val DEFINITIVE_TRANSACTION_REJECTION_STATUSES = setOf(409, 422)
    }
}
