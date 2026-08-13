package com.whatto.bcm.infra.client.evm

import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepBatchCallItem
import com.whatto.bcm.domain.sweep.SweepBatchContractPort
import com.whatto.bcm.domain.sweep.SweepBatchReceipt
import com.whatto.bcm.domain.sweep.SweepBatchReceiptPort
import com.whatto.bcm.domain.sweep.SweepLegObservation
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

@Component
class EvmErc20Client(
    builder: RestClient.Builder,
    properties: EvmRpcProperties,
) : Erc20ContractPort,
    SweepBatchContractPort,
    SweepBatchReceiptPort {
    private val clients = properties.networks.mapValues { (_, network) -> builder.clone().baseUrl(network.url).build() }

    override fun allowance(
        network: String,
        tokenContractAddress: String,
        ownerAddress: String,
        spenderAddress: String,
    ): SweepAllowanceObservation {
        val decimals = decimals(network, tokenContractAddress)
        val allowance =
            uintCall(
                network,
                tokenContractAddress,
                ALLOWANCE_SELECTOR + addressWord(ownerAddress) + addressWord(spenderAddress),
            )
        return SweepAllowanceObservation(
            amount = BigDecimal(allowance, decimals).stripTrailingZeros().toPlainString(),
            decimals = decimals,
        )
    }

    override fun decimals(
        network: String,
        tokenContractAddress: String,
    ): Int =
        uintCall(network, tokenContractAddress, DECIMALS_SELECTOR)
            .intValueExact()
            .also { decimals ->
                require(decimals in 0..MAX_TOKEN_DECIMALS) {
                    "unsupported token decimals: network=$network decimals=$decimals"
                }
            }

    override fun approvalCallData(
        spenderAddress: String,
        amount: String,
        decimals: Int,
    ): String {
        require(decimals in 0..MAX_TOKEN_DECIMALS) { "unsupported token decimals: decimals=$decimals" }
        val units =
            BigDecimal(amount)
                .setScale(decimals)
                .movePointRight(decimals)
                .toBigIntegerExact()
        require(units.signum() >= 0) { "approval amount must not be negative" }
        require(units.bitLength() <= 256) { "approval amount exceeds uint256" }
        return APPROVE_SELECTOR + addressWord(spenderAddress) + units.toString(16).padStart(WORD_HEX_LENGTH, '0')
    }

    override fun batchSweepCallData(
        network: String,
        executionId: String,
        tokenContractAddress: String,
        items: List<SweepBatchCallItem>,
    ): String {
        require(items.isNotEmpty()) { "batch sweep must contain at least one item" }
        val encodedExecutionId = bytes16Word(executionId)
        val owners = items.map { normalizedAddress(it.ownerAddress) }
        require(owners.zipWithNext().all { (left, right) -> left < right }) {
            "batch sweep owners must be unique and sorted"
        }
        val decimals = decimals(network, tokenContractAddress)
        val encodedItems =
            items.zip(owners).joinToString("") { (item, owner) ->
                addressWord(owner) + uintWord(toBaseUnits(item.amount, decimals))
            }
        return BATCH_SWEEP_SELECTOR +
            encodedExecutionId +
            addressWord(tokenContractAddress) +
            uintWord(BigInteger.valueOf(96)) +
            uintWord(BigInteger.valueOf(items.size.toLong())) +
            encodedItems
    }

    override fun receipt(
        network: String,
        transactionHash: String,
        sweepContractAddress: String,
        tokenDecimals: Int,
    ): SweepBatchReceipt? {
        require(tokenDecimals in 0..MAX_TOKEN_DECIMALS) { "unsupported token decimals: decimals=$tokenDecimals" }
        val client = checkNotNull(clients[network]) { "EVM RPC is not configured: network=$network" }
        val response =
            client
                .post()
                .body(
                    JsonRpcRequest(
                        method = "eth_getTransactionReceipt",
                        params = listOf(transactionHash),
                    ),
                ).retrieve()
                .body(JsonRpcReceiptResponse::class.java)
                ?: error("EVM RPC response body is missing: network=$network")
        check(response.error == null) {
            "EVM RPC returned an error: network=$network code=${response.error?.code}"
        }
        val receipt = response.result ?: return null
        val contract = normalizedAddress(sweepContractAddress)
        val legs =
            receipt.logs
                .filter { log ->
                    log.address.equals(contract, ignoreCase = true) &&
                        log.topics.firstOrNull().equals(SWEEP_LEG_TOPIC, ignoreCase = true)
                }.map { decodeSweepLeg(it, tokenDecimals) }
        return SweepBatchReceipt(
            successful = parseHex(receipt.status, "receipt status") == BigInteger.ONE,
            legs = legs,
        )
    }

    private fun decodeSweepLeg(
        log: EvmLogResponse,
        decimals: Int,
    ): SweepLegObservation {
        require(log.topics.size == 4) { "invalid SweepLeg topics" }
        val executionWord = word(log.topics[1], "executionId")
        require(executionWord.drop(32).all { it == '0' }) { "invalid SweepLeg executionId padding" }
        val executionHex = executionWord.take(32)
        val sequence = parseWord(log.topics[2], "itemSequence").intValueExact()
        require(sequence > 0) { "invalid SweepLeg itemSequence" }
        val ownerWord = word(log.topics[3], "owner")
        require(ownerWord.take(24).all { it == '0' }) { "invalid SweepLeg owner padding" }
        val data = hex(log.data, "SweepLeg data")
        require(data.length == WORD_HEX_LENGTH * 4) { "invalid SweepLeg data length" }
        val requestedUnits = BigInteger(data.substring(0, WORD_HEX_LENGTH), 16)
        val actualUnits = BigInteger(data.substring(WORD_HEX_LENGTH, WORD_HEX_LENGTH * 2), 16)
        val successWord = BigInteger(data.substring(WORD_HEX_LENGTH * 2, WORD_HEX_LENGTH * 3), 16)
        require(successWord == BigInteger.ZERO || successWord == BigInteger.ONE) { "invalid SweepLeg success" }
        return SweepLegObservation(
            executionId = uuid(executionHex),
            itemSequence = sequence,
            ownerAddress = "0x${ownerWord.takeLast(ADDRESS_HEX_LENGTH)}",
            requestedAmount = amount(requestedUnits, decimals),
            actualAmount = amount(actualUnits, decimals),
            successful = successWord == BigInteger.ONE,
            failureCode = data.takeLast(WORD_HEX_LENGTH).lowercase(),
            logIndex = parseHex(log.logIndex, "logIndex").intValueExact(),
        )
    }

    private fun uuid(value: String): String =
        "${value.substring(0, 8)}-${value.substring(8, 12)}-${value.substring(12, 16)}-" +
            "${value.substring(16, 20)}-${value.substring(20)}"

    private fun amount(
        units: BigInteger,
        decimals: Int,
    ): String = BigDecimal(units, decimals).stripTrailingZeros().toPlainString()

    private fun parseWord(
        value: String,
        field: String,
    ): BigInteger = BigInteger(word(value, field), 16)

    private fun parseHex(
        value: String,
        field: String,
    ): BigInteger = BigInteger(hex(value, field).ifEmpty { "0" }, 16)

    private fun word(
        value: String,
        field: String,
    ): String = hex(value, field).also { require(it.length == WORD_HEX_LENGTH) { "invalid $field word" } }

    private fun hex(
        value: String,
        field: String,
    ): String {
        require(value.startsWith("0x")) { "invalid $field hex" }
        return value.removePrefix("0x").also { raw ->
            require(raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "invalid $field hex" }
        }
    }

    private fun toBaseUnits(
        amount: String,
        decimals: Int,
    ): BigInteger =
        BigDecimal(amount)
            .movePointRight(decimals)
            .toBigIntegerExact()
            .also { units ->
                require(units.signum() > 0) { "amount must be positive" }
                require(units.bitLength() <= 256) { "amount exceeds uint256" }
            }

    private fun uintWord(value: BigInteger): String = value.toString(16).padStart(WORD_HEX_LENGTH, '0')

    private fun bytes16Word(value: String): String {
        val uuid = UUID.fromString(value)
        require(uuid.toString() == value && uuid.version() == 7 && uuid.variant() == 2) {
            "batch executionId must be a canonical UUID v7"
        }
        return value.replace("-", "").padEnd(WORD_HEX_LENGTH, '0')
    }

    private fun uintCall(
        network: String,
        contractAddress: String,
        data: String,
    ): BigInteger {
        val client = checkNotNull(clients[network]) { "EVM RPC is not configured: network=$network" }
        val response =
            client
                .post()
                .body(
                    JsonRpcRequest(
                        method = "eth_call",
                        params = listOf(mapOf("to" to normalizedAddress(contractAddress), "data" to data), "latest"),
                    ),
                ).retrieve()
                .body(JsonRpcResponse::class.java)
                ?: error("EVM RPC response body is missing: network=$network")
        check(response.error == null) {
            "EVM RPC returned an error: network=$network code=${response.error?.code}"
        }
        val result = checkNotNull(response.result) { "EVM RPC result is missing: network=$network" }
        require(result.startsWith("0x")) { "EVM RPC result is not hex: network=$network" }
        return result.removePrefix("0x").ifEmpty { "0" }.let { BigInteger(it, 16) }
    }

    private fun addressWord(address: String): String = normalizedAddress(address).removePrefix("0x").padStart(WORD_HEX_LENGTH, '0')

    private fun normalizedAddress(address: String): String {
        val raw = address.removePrefix("0x")
        require(raw.length == ADDRESS_HEX_LENGTH && raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "invalid EVM address"
        }
        return "0x${raw.lowercase()}"
    }

    private data class JsonRpcRequest(
        val jsonrpc: String = "2.0",
        val id: Int = 1,
        val method: String,
        val params: List<Any>,
    )

    private data class JsonRpcResponse(
        val result: String? = null,
        val error: JsonRpcError? = null,
    )

    private data class JsonRpcReceiptResponse(
        val result: EvmReceiptResponse? = null,
        val error: JsonRpcError? = null,
    )

    private data class EvmReceiptResponse(
        val status: String,
        val logs: List<EvmLogResponse> = emptyList(),
    )

    private data class EvmLogResponse(
        val address: String,
        val topics: List<String> = emptyList(),
        val data: String,
        val logIndex: String,
    )

    private data class JsonRpcError(
        val code: Int? = null,
    )

    private companion object {
        const val DECIMALS_SELECTOR = "0x313ce567"
        const val ALLOWANCE_SELECTOR = "0xdd62ed3e"
        const val APPROVE_SELECTOR = "0x095ea7b3"
        const val BATCH_SWEEP_SELECTOR = "0x4209ef32"
        const val SWEEP_LEG_TOPIC = "0x018084291c296fa2423afb4bbaad7ec89aad291255cc83c584e3f054c93cabb0"
        const val ADDRESS_HEX_LENGTH = 40
        const val WORD_HEX_LENGTH = 64
        const val MAX_TOKEN_DECIMALS = 36
    }
}
