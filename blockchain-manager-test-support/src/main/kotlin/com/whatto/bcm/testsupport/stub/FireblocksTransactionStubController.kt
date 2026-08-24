package com.whatto.bcm.testsupport.stub

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@RestController
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class FireblocksTransactionStubController(
    private val state: FireblocksTransactionState,
) {
    @PostMapping("/v1/transactions")
    fun submit(
        @RequestBody request: CreateTransactionRequest,
    ): CreateTransactionResponse = state.submit(request)

    @GetMapping("/v1/transactions/external_tx_id/{externalTransactionId}")
    fun byExternalTransactionId(
        @PathVariable externalTransactionId: String,
    ): TransactionResponse = state.byExternalTransactionId(externalTransactionId)

    @GetMapping("/v1/transactions/{transactionId}")
    fun byId(
        @PathVariable transactionId: String,
    ): TransactionResponse = state.byId(transactionId)

    @PostMapping("/__stub/transactions/{transactionId}/advance")
    fun advance(
        @PathVariable transactionId: String,
    ): TransactionResponse = state.advance(transactionId)

    @PostMapping("/__stub/deposits")
    fun deposit(
        @RequestBody request: CreateDepositRequest,
    ): TransactionResponse = state.deposit(request)

    @PostMapping("/__stub/faults/transactions/next-response")
    fun configureNextResponseFault(
        @RequestBody request: ConfigureTransactionResponseFaultRequest,
    ) {
        state.configureNextResponseFault(request)
    }

    @PostMapping("/__stub/faults/transactions/next-terminal-state")
    fun configureNextTerminalState(
        @RequestBody request: ConfigureTransactionTerminalStateRequest,
    ) {
        state.configureNextTerminalState(request)
    }

    @PostMapping("/__stub/faults/transactions/next-pending-state")
    fun configureNextPendingState(
        @RequestBody request: ConfigureTransactionPendingStateRequest,
    ) {
        state.configureNextPendingState(request)
    }

    @GetMapping("/v1/transactions")
    fun transactions(
        @RequestParam(required = false) next: String?,
        @RequestParam(required = false) after: Long?,
        @RequestParam(required = false) before: Long?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, defaultValue = "DESC") sort: String,
        @RequestParam(required = false, defaultValue = "200") limit: Int,
        @RequestParam(required = false) sourceType: String?,
        @RequestParam(required = false) sourceId: String?,
    ): ResponseEntity<List<TransactionResponse>> {
        val page = state.transactions(next, after, before, status, sort, limit, sourceType, sourceId)
        val response = ResponseEntity.ok()
        page.next?.let { response.header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, NEXT_PAGE_HEADER).header(NEXT_PAGE_HEADER, it) }
        return response.body(page.data)
    }

    companion object {
        private const val NEXT_PAGE_HEADER = "next-page"
    }
}

@RestController
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class LocalChainFaultController(
    private val chain: LocalStubChainState,
) {
    @PostMapping("/__stub/faults/chain/native-balance")
    fun setNativeBalance(
        @RequestBody request: ConfigureNativeBalanceRequest,
    ) {
        val balanceWei =
            request.balanceWei.toBigIntegerOrNull()
                ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "native balance must be an integer")
        if (balanceWei.signum() < 0) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "native balance must not be negative")
        }
        chain.setNativeBalance(request.address, balanceWei)
    }

    @PostMapping("/__stub/faults/chain/next-transaction-nonce")
    fun configureNextTransactionNonce(
        @RequestBody request: ConfigureTransactionNonceRequest,
    ) {
        val nonce =
            request.nonce.toBigIntegerOrNull()
                ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "transaction nonce must be an integer")
        if (nonce.signum() < 0) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "transaction nonce must not be negative")
        }
        chain.configureNextTransactionNonce(nonce)
    }
}

@Component
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class FireblocksTransactionState(
    private val chain: LocalStubChainState,
    private val vaults: FireblocksVaultState,
    private val clock: Clock,
    private val webhookDelivery: LocalWebhookDeliveryService,
) {
    private val lock = ReentrantLock()
    private val transactionsById = linkedMapOf<String, StoredTransaction>()
    private val transactionIdByExternalId = mutableMapOf<String, String>()
    private var nextResponseFault: ConfigureTransactionResponseFaultRequest? = null
    private var nextVendorState: String? = null

    fun configureNextResponseFault(request: ConfigureTransactionResponseFaultRequest) {
        lock.withLock {
            if (request.status !in 400..599) unprocessable("fault response status must be between 400 and 599")
            if (request.effectiveDelayMillis !in 0..MAX_FAULT_DELAY_MILLIS) {
                unprocessable("fault response delay must be between 0 and $MAX_FAULT_DELAY_MILLIS milliseconds")
            }
            if (nextResponseFault != null) conflict("next transaction response fault is already configured")
            nextResponseFault = request
        }
    }

    fun configureNextTerminalState(request: ConfigureTransactionTerminalStateRequest) {
        lock.withLock {
            if (request.status !in TERMINAL_POLICY_STATUSES) {
                unprocessable("terminal vendor status must be BLOCKED or REJECTED")
            }
            configureNextVendorState(request.status)
        }
    }

    fun configureNextPendingState(request: ConfigureTransactionPendingStateRequest) {
        lock.withLock {
            if (request.status !in PENDING_VENDOR_STATUSES) {
                unprocessable("pending vendor status must be PENDING_SIGNATURE or QUEUED")
            }
            configureNextVendorState(request.status)
        }
    }

    fun reset() {
        lock.withLock {
            transactionsById.clear()
            transactionIdByExternalId.clear()
            nextResponseFault = null
            nextVendorState = null
        }
    }

    fun submit(request: CreateTransactionRequest): CreateTransactionResponse =
        lock.withLock {
            val prepared = prepare(request)
            transactionIdByExternalId[prepared.externalTransactionId]?.let { transactionId ->
                val existing = checkNotNull(transactionsById[transactionId])
                if (existing.canonicalRequest != prepared.canonicalRequest) {
                    conflict("externalTxId was already used for another fund movement")
                }
                return@withLock CreateTransactionResponse(existing.response.id, existing.response.status)
            }
            nextResponseFault?.takeUnless { it.afterCommit }?.let { fault ->
                nextResponseFault = null
                throwResponseFault(fault, "injected transaction response before commit")
            }

            val simulatedVendorState = nextVendorState.also { nextVendorState = null }
            val result =
                if (simulatedVendorState == null) {
                    if (prepared.useGasless) {
                        chain.submitGaslessTransaction(
                            sourceAddress = prepared.sourceAddress,
                            destinationAddress = prepared.onchainDestination,
                            rawAmount = prepared.rawAmount,
                            callData = prepared.callData,
                        )
                    } else {
                        chain.submitRawTransaction(
                            sourceAddress = prepared.sourceAddress,
                            destinationAddress = prepared.onchainDestination,
                            rawAmount = prepared.rawAmount,
                            callData = prepared.callData,
                            gasLimit = prepared.gasLimit,
                        )
                    }
                } else {
                    null
                }
            val now = clock.millis()
            val transactionId = "tx-local-${(transactionsById.size + 1).toString().padStart(6, '0')}"
            val response =
                TransactionResponse(
                    id = transactionId,
                    externalTxId = prepared.externalTransactionId,
                    status = simulatedVendorState ?: "SUBMITTED",
                    txHash = result?.transactionHash,
                    assetId = prepared.asset.id,
                    source = TransactionPeerResponse(type = "VAULT_ACCOUNT", id = prepared.sourceVaultId),
                    sourceAddress = prepared.sourceAddress,
                    destination = prepared.destinationPeer,
                    destinationAddress = prepared.destinationAddress,
                    amountInfo = TransactionAmountInfoResponse(prepared.displayAmount),
                    createdAt = now,
                    lastUpdated = now,
                    numOfConfirmations = 0,
                    operation = prepared.operation,
                    extraParameters = prepared.contractCallData?.let(::TransactionExtraParametersResponse),
                )
            transactionsById[transactionId] =
                StoredTransaction(
                    canonicalRequest = prepared.canonicalRequest,
                    successful = result?.successful ?: false,
                    receiptBlockNumber = result?.blockNumber ?: BigInteger.ZERO,
                    response = response,
                )
            transactionIdByExternalId[prepared.externalTransactionId] = transactionId
            webhookDelivery.publish("transaction.created", response)
            nextResponseFault?.takeIf { it.afterCommit }?.let { fault ->
                nextResponseFault = null
                throwResponseFault(fault, "injected transaction response loss after commit")
            }
            CreateTransactionResponse(transactionId, response.status)
        }

    fun deposit(request: CreateDepositRequest): TransactionResponse =
        lock.withLock {
            require(request.externalTxId.isNotBlank()) { "externalTxId must not be blank" }
            val asset = chain.asset(request.assetId) ?: notFound("local asset does not exist")
            val destinationAddress = vaults.walletAddress(request.destinationVaultId, asset.id)
            val amount = decimalAmount(request.amount, asset.decimals, positive = true)
            val canonicalRequest =
                CanonicalTransactionRequest(
                    operation = "TRANSFER",
                    assetId = asset.id,
                    sourceVaultId = null,
                    destinationType = "VAULT_ACCOUNT",
                    destinationId = request.destinationVaultId,
                    destinationAddress = destinationAddress,
                    rawAmount = amount.raw,
                    callData = null,
                    useGasless = false,
                    replaceTxByHash = null,
                )
            transactionIdByExternalId[request.externalTxId]?.let { transactionId ->
                val existing = checkNotNull(transactionsById[transactionId])
                if (existing.canonicalRequest != canonicalRequest) {
                    conflict("externalTxId was already used for another fund movement")
                }
                return@withLock existing.response
            }

            val result = chain.submitExternalTransfer(destinationAddress, asset, amount.raw)
            val now = clock.millis()
            val transactionId = "tx-local-${(transactionsById.size + 1).toString().padStart(6, '0')}"
            val response =
                TransactionResponse(
                    id = transactionId,
                    externalTxId = request.externalTxId,
                    status = "SUBMITTED",
                    txHash = result.transactionHash,
                    assetId = asset.id,
                    source = TransactionPeerResponse(type = "UNKNOWN", id = null),
                    sourceAddress = chain.externalSourceAddress(),
                    destination = TransactionPeerResponse(type = "VAULT_ACCOUNT", id = request.destinationVaultId),
                    destinationAddress = destinationAddress,
                    amountInfo = TransactionAmountInfoResponse(amount.display),
                    createdAt = now,
                    lastUpdated = now,
                    numOfConfirmations = 0,
                    operation = "TRANSFER",
                )
            transactionsById[transactionId] =
                StoredTransaction(
                    canonicalRequest = canonicalRequest,
                    successful = result.successful,
                    receiptBlockNumber = result.blockNumber,
                    response = response,
                )
            transactionIdByExternalId[request.externalTxId] = transactionId
            webhookDelivery.publish("transaction.created", response)
            response
        }

    fun byId(transactionId: String): TransactionResponse =
        lock.withLock {
            transactionsById[transactionId]?.response ?: notFound("transaction does not exist")
        }

    fun byExternalTransactionId(externalTransactionId: String): TransactionResponse =
        lock.withLock {
            val transactionId = transactionIdByExternalId[externalTransactionId] ?: notFound("transaction does not exist")
            checkNotNull(transactionsById[transactionId]).response
        }

    fun advance(transactionId: String): TransactionResponse =
        lock.withLock {
            val transaction = transactionsById[transactionId] ?: notFound("transaction does not exist")
            val previous = transaction.response
            val next =
                when (previous.status) {
                    "SUBMITTED" -> {
                        if (transaction.successful) {
                            previous.copy(
                                status = "CONFIRMING",
                                lastUpdated = nextUpdatedAt(previous.lastUpdated),
                                numOfConfirmations = confirmationCount(transaction.receiptBlockNumber),
                            )
                        } else {
                            previous.copy(
                                status = "FAILED",
                                lastUpdated = nextUpdatedAt(previous.lastUpdated),
                            )
                        }
                    }

                    "CONFIRMING" -> {
                        chain.mineBlock()
                        val confirmations = confirmationCount(transaction.receiptBlockNumber)
                        val completed = confirmations >= FINALITY_CONFIRMATIONS
                        previous.copy(
                            status = if (completed) "COMPLETED" else "CONFIRMING",
                            lastUpdated = nextUpdatedAt(previous.lastUpdated),
                            numOfConfirmations = confirmations,
                            networkRecords = if (completed) networkRecords(previous) else previous.networkRecords,
                        )
                    }

                    else -> previous
                }
            transaction.response = next
            if (next != previous) {
                webhookDelivery.publish("transaction.status.updated", next)
                if (next.status == "COMPLETED") {
                    webhookDelivery.publish("transaction.network_records.processing_completed", next)
                }
            }
            next
        }

    private fun networkRecords(transaction: TransactionResponse): List<TransactionNetworkRecordResponse> =
        when (transaction.operation) {
            "TRANSFER" -> {
                val transactionHash = checkNotNull(transaction.txHash) { "completed transfer has no transaction hash" }
                listOf(
                    TransactionNetworkRecordResponse(
                        type = "TRANSFER",
                        source = transaction.source,
                        destination = transaction.destination,
                        destinationAddress = transaction.destinationAddress,
                        txHash = transactionHash,
                        assetId = transaction.assetId,
                        netAmount = transaction.amountInfo.amount,
                        isDropped = false,
                    ),
                )
            }

            "CONTRACT_CALL" -> {
                val transactionHash = checkNotNull(transaction.txHash) { "completed contract call has no transaction hash" }
                chain.tokenTransfers(transactionHash).map { transfer ->
                    val sourceVaultId = vaults.vaultIdByAddress(transfer.sourceAddress, transfer.assetId)
                    val destinationVaultId = vaults.vaultIdByAddress(transfer.destinationAddress, transfer.assetId)
                    TransactionNetworkRecordResponse(
                        type = "TOKEN_TRANSFER",
                        source =
                            sourceVaultId
                                ?.let { TransactionPeerResponse("VAULT_ACCOUNT", it) }
                                ?: TransactionPeerResponse("UNKNOWN", null),
                        destination =
                            destinationVaultId
                                ?.let { TransactionPeerResponse("VAULT_ACCOUNT", it) }
                                ?: TransactionPeerResponse("ONE_TIME_ADDRESS", null),
                        destinationAddress = transfer.destinationAddress,
                        txHash = transactionHash,
                        assetId = transfer.assetId,
                        netAmount = transfer.amount,
                        isDropped = false,
                    )
                }
            }

            else -> emptyList()
        }

    fun transactions(
        next: String?,
        after: Long?,
        before: Long?,
        status: String?,
        sort: String,
        limit: Int,
        sourceType: String?,
        sourceId: String?,
    ): TransactionPage =
        lock.withLock {
            if (limit !in 1..500) badRequest("transaction page limit must be between 1 and 500")
            if (sort != "ASC" && sort != "DESC") badRequest("transaction sort must be ASC or DESC")
            val ordered =
                transactionsById.values
                    .asSequence()
                    .map(StoredTransaction::response)
                    .filter { after == null || it.createdAt > after }
                    .filter { before == null || it.createdAt < before }
                    .filter { status == null || it.status == status }
                    .filter { sourceType == null || it.source.type == sourceType }
                    .filter { sourceId == null || it.source.id == sourceId }
                    .let { values -> if (sort == "ASC") values else values.toList().asReversed().asSequence() }
                    .toList()
            val startIndex =
                next?.let { cursor ->
                    val transactionId = decodeCursor(cursor)
                    val cursorIndex = ordered.indexOfFirst { it.id == transactionId }
                    if (cursorIndex < 0) badRequest("transaction page cursor does not belong to this result")
                    cursorIndex + 1
                } ?: 0
            val page = ordered.drop(startIndex).take(limit + 1)
            val hasNext = page.size > limit
            val data = if (hasNext) page.dropLast(1) else page
            TransactionPage(
                data = data,
                next =
                    data
                        .lastOrNull()
                        ?.id
                        ?.takeIf { hasNext }
                        ?.let(::encodeCursor),
            )
        }

    private fun prepare(request: CreateTransactionRequest): PreparedTransaction {
        require(request.externalTxId.isNotBlank()) { "externalTxId must not be blank" }
        val sourceVaultId = request.source.id ?: unprocessable("source vault id is required")
        if (request.source.type != "VAULT_ACCOUNT") unprocessable("source must be VAULT_ACCOUNT")
        val asset = chain.asset(request.assetId) ?: notFound("local asset does not exist")
        val sourceAddress = vaults.walletAddress(sourceVaultId, asset.id)
        return when (request.operation) {
            "TRANSFER" -> prepareTransfer(request, sourceVaultId, sourceAddress, asset)
            "CONTRACT_CALL" -> prepareContractCall(request, sourceVaultId, sourceAddress, asset)
            else -> unprocessable("unsupported transaction operation")
        }
    }

    private fun confirmationCount(receiptBlockNumber: BigInteger): Int =
        (chain.blockNumber() - receiptBlockNumber + BigInteger.ONE).intValueExact()

    private fun nextUpdatedAt(previous: Long): Long = maxOf(clock.millis(), previous + 1)

    private fun prepareTransfer(
        request: CreateTransactionRequest,
        sourceVaultId: String,
        sourceAddress: String,
        asset: LocalStubAsset,
    ): PreparedTransaction {
        val amount = decimalAmount(request.amount, asset.decimals, positive = true)
        val destination = resolveDestination(request.destination, asset.id)
        val tokenAddress = asset.contractAddress
        val (onchainDestination, rawAmount, callData, gasLimit) =
            if (tokenAddress == null) {
                TransactionExecution(destination.address, amount.raw, "0x", NATIVE_TRANSFER_GAS_LIMIT)
            } else {
                TransactionExecution(
                    tokenAddress,
                    BigInteger.ZERO,
                    TRANSFER_SELECTOR + addressWord(destination.address) + uintWord(amount.raw),
                    TOKEN_TRANSFER_GAS_LIMIT,
                )
            }
        return PreparedTransaction(
            operation = request.operation,
            externalTransactionId = request.externalTxId,
            asset = asset,
            sourceVaultId = sourceVaultId,
            sourceAddress = sourceAddress,
            destinationPeer = destination.peer,
            destinationAddress = destination.address,
            onchainDestination = onchainDestination,
            rawAmount = rawAmount,
            displayAmount = amount.display,
            callData = callData,
            contractCallData = null,
            gasLimit = gasLimit,
            useGasless = request.useGasless,
            canonicalRequest =
                CanonicalTransactionRequest(
                    operation = request.operation,
                    assetId = asset.id,
                    sourceVaultId = sourceVaultId,
                    destinationType = destination.peer.type,
                    destinationId = destination.peer.id,
                    destinationAddress = destination.address,
                    rawAmount = amount.raw,
                    callData = null,
                    useGasless = request.useGasless,
                    replaceTxByHash = request.replaceTxByHash,
                ),
        )
    }

    private fun prepareContractCall(
        request: CreateTransactionRequest,
        sourceVaultId: String,
        sourceAddress: String,
        asset: LocalStubAsset,
    ): PreparedTransaction {
        if (asset.contractAddress != null) unprocessable("CONTRACT_CALL asset must be the native gas asset")
        val amount = decimalAmount(request.amount, asset.decimals, positive = false)
        if (amount.raw != BigInteger.ZERO) unprocessable("CONTRACT_CALL amount must be zero")
        val destination = resolveDestination(request.destination, asset.id)
        if (destination.peer.type != "ONE_TIME_ADDRESS") unprocessable("CONTRACT_CALL destination must be a contract address")
        val callData = request.extraParameters?.contractCallData ?: unprocessable("contractCallData is required")
        normalizedHex(callData)
        return PreparedTransaction(
            operation = request.operation,
            externalTransactionId = request.externalTxId,
            asset = asset,
            sourceVaultId = sourceVaultId,
            sourceAddress = sourceAddress,
            destinationPeer = destination.peer,
            destinationAddress = destination.address,
            onchainDestination = destination.address,
            rawAmount = BigInteger.ZERO,
            displayAmount = "0",
            callData = callData,
            contractCallData = callData,
            gasLimit = CONTRACT_CALL_GAS_LIMIT,
            useGasless = request.useGasless,
            canonicalRequest =
                CanonicalTransactionRequest(
                    operation = request.operation,
                    assetId = asset.id,
                    sourceVaultId = sourceVaultId,
                    destinationType = destination.peer.type,
                    destinationId = destination.peer.id,
                    destinationAddress = destination.address,
                    rawAmount = BigInteger.ZERO,
                    callData = callData.lowercase(),
                    useGasless = request.useGasless,
                    replaceTxByHash = request.replaceTxByHash,
                ),
        )
    }

    private fun resolveDestination(
        destination: TransactionPeerRequest,
        assetId: String,
    ): ResolvedDestination =
        when (destination.type) {
            "ONE_TIME_ADDRESS" -> {
                val address = destination.oneTimeAddress?.address ?: unprocessable("one-time destination address is required")
                ResolvedDestination(TransactionPeerResponse("ONE_TIME_ADDRESS", null), normalizedAddress(address))
            }

            "VAULT_ACCOUNT" -> {
                val vaultId = destination.id ?: unprocessable("destination vault id is required")
                ResolvedDestination(TransactionPeerResponse("VAULT_ACCOUNT", vaultId), vaults.walletAddress(vaultId, assetId))
            }

            "EXTERNAL_WALLET" -> {
                val walletId = destination.id ?: unprocessable("external wallet id is required")
                ResolvedDestination(TransactionPeerResponse("EXTERNAL_WALLET", walletId), normalizedAddress(walletId))
            }
            else -> unprocessable("unsupported transaction destination type")
        }

    private fun decimalAmount(
        value: String,
        decimals: Int,
        positive: Boolean,
    ): DecimalAmount {
        val decimal =
            try {
                BigDecimal(value)
            } catch (exception: NumberFormatException) {
                throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "amount must be decimal", exception)
            }
        if (positive && decimal.signum() <= 0) unprocessable("TRANSFER amount must be positive")
        if (!positive && decimal.signum() < 0) unprocessable("amount must not be negative")
        val raw =
            try {
                decimal.movePointRight(decimals).toBigIntegerExact()
            } catch (exception: ArithmeticException) {
                throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "amount exceeds asset decimals", exception)
            }
        return DecimalAmount(raw, decimal.stripTrailingZeros().toPlainString())
    }

    private fun normalizedAddress(value: String): String {
        val raw = value.removePrefix("0x")
        if (raw.length != 40 || raw.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
            unprocessable("invalid EVM address")
        }
        return "0x${raw.lowercase()}"
    }

    private fun normalizedHex(value: String): String {
        if (!value.startsWith("0x") || value.length % 2 != 0 || value.drop(2).any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
            unprocessable("invalid EVM calldata")
        }
        return value.lowercase()
    }

    private fun addressWord(address: String): String = normalizedAddress(address).removePrefix("0x").padStart(64, '0')

    private fun uintWord(value: BigInteger): String = value.toString(16).padStart(64, '0')

    private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)

    private fun configureNextVendorState(status: String) {
        if (nextVendorState != null) conflict("next vendor state is already configured")
        nextVendorState = status
    }

    private fun throwResponseFault(
        fault: ConfigureTransactionResponseFaultRequest,
        message: String,
    ): Nothing {
        if (fault.effectiveDelayMillis > 0) {
            try {
                Thread.sleep(fault.effectiveDelayMillis)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "injected response delay was interrupted", exception)
            }
        }
        throw ResponseStatusException(HttpStatus.valueOf(fault.status), message)
    }

    private fun unprocessable(message: String): Nothing = throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message)

    private fun encodeCursor(transactionId: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$CURSOR_PREFIX$transactionId".toByteArray(StandardCharsets.UTF_8))

    private fun decodeCursor(cursor: String): String {
        val value =
            try {
                String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
            } catch (exception: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid transaction page cursor", exception)
            }
        if (!value.startsWith(CURSOR_PREFIX)) badRequest("invalid transaction page cursor")
        return value.removePrefix(CURSOR_PREFIX).takeIf(String::isNotBlank)
            ?: badRequest("invalid transaction page cursor")
    }

    private data class StoredTransaction(
        val canonicalRequest: CanonicalTransactionRequest,
        val successful: Boolean,
        val receiptBlockNumber: BigInteger,
        var response: TransactionResponse,
    )

    private data class PreparedTransaction(
        val operation: String,
        val externalTransactionId: String,
        val asset: LocalStubAsset,
        val sourceVaultId: String,
        val sourceAddress: String,
        val destinationPeer: TransactionPeerResponse,
        val destinationAddress: String,
        val onchainDestination: String,
        val rawAmount: BigInteger,
        val displayAmount: String,
        val callData: String,
        val contractCallData: String?,
        val gasLimit: BigInteger,
        val useGasless: Boolean,
        val canonicalRequest: CanonicalTransactionRequest,
    )

    private data class CanonicalTransactionRequest(
        val operation: String,
        val assetId: String,
        val sourceVaultId: String?,
        val destinationType: String,
        val destinationId: String?,
        val destinationAddress: String,
        val rawAmount: BigInteger,
        val callData: String?,
        val useGasless: Boolean,
        val replaceTxByHash: String?,
    )

    private data class ResolvedDestination(
        val peer: TransactionPeerResponse,
        val address: String,
    )

    private data class DecimalAmount(
        val raw: BigInteger,
        val display: String,
    )

    private data class TransactionExecution(
        val onchainDestination: String,
        val rawAmount: BigInteger,
        val callData: String,
        val gasLimit: BigInteger,
    )

    companion object {
        private const val TRANSFER_SELECTOR = "0xa9059cbb"
        private const val CURSOR_PREFIX = "bcm-local-transaction:"
        private const val FINALITY_CONFIRMATIONS = 2
        private const val MAX_FAULT_DELAY_MILLIS = 30_000L
        private val TERMINAL_POLICY_STATUSES = setOf("BLOCKED", "REJECTED")
        private val PENDING_VENDOR_STATUSES = setOf("PENDING_SIGNATURE", "QUEUED")
        private val NATIVE_TRANSFER_GAS_LIMIT = BigInteger.valueOf(21_000)
        private val TOKEN_TRANSFER_GAS_LIMIT = BigInteger.valueOf(100_000)
        private val CONTRACT_CALL_GAS_LIMIT = BigInteger.valueOf(1_000_000)
    }
}

internal data class CreateDepositRequest(
    val externalTxId: String,
    val assetId: String,
    val destinationVaultId: String,
    val amount: String,
)

internal data class ConfigureTransactionResponseFaultRequest(
    val status: Int,
    val afterCommit: Boolean,
    val delayMillis: Long? = null,
) {
    val effectiveDelayMillis: Long
        get() = delayMillis ?: 0
}

internal data class ConfigureTransactionTerminalStateRequest(
    val status: String,
)

internal data class ConfigureTransactionPendingStateRequest(
    val status: String,
)

internal data class ConfigureNativeBalanceRequest(
    val address: String,
    val balanceWei: String,
)

internal data class ConfigureTransactionNonceRequest(
    val nonce: String,
)

internal data class CreateTransactionRequest(
    val operation: String,
    val externalTxId: String,
    val assetId: String,
    val source: TransactionPeerRequest,
    val destination: TransactionPeerRequest,
    val amount: String,
    val useGasless: Boolean,
    val replaceTxByHash: String? = null,
    val extraParameters: TransactionExtraParametersRequest? = null,
)

internal data class TransactionPeerRequest(
    val type: String,
    val id: String? = null,
    val oneTimeAddress: OneTimeAddressRequest? = null,
)

internal data class OneTimeAddressRequest(
    val address: String,
)

internal data class TransactionExtraParametersRequest(
    val contractCallData: String? = null,
)

internal data class CreateTransactionResponse(
    val id: String,
    val status: String,
)

internal data class TransactionResponse(
    val id: String,
    val externalTxId: String,
    val status: String,
    val subStatus: String? = null,
    val txHash: String?,
    val assetId: String,
    val source: TransactionPeerResponse,
    val sourceAddress: String,
    val destination: TransactionPeerResponse,
    val destinationAddress: String,
    val amountInfo: TransactionAmountInfoResponse,
    val createdAt: Long,
    val lastUpdated: Long,
    val numOfConfirmations: Int,
    val operation: String,
    val extraParameters: TransactionExtraParametersResponse? = null,
    val networkRecords: List<TransactionNetworkRecordResponse> = emptyList(),
)

internal data class TransactionNetworkRecordResponse(
    val type: String,
    val source: TransactionPeerResponse,
    val destination: TransactionPeerResponse,
    val destinationAddress: String,
    val txHash: String,
    val assetId: String,
    val netAmount: String,
    @get:JsonProperty("isDropped")
    val isDropped: Boolean,
)

internal data class TransactionPeerResponse(
    val type: String,
    val id: String?,
)

internal data class TransactionAmountInfoResponse(
    val amount: String,
)

internal data class TransactionExtraParametersResponse(
    val contractCallData: String,
)

internal data class TransactionPage(
    val data: List<TransactionResponse>,
    val next: String?,
)
