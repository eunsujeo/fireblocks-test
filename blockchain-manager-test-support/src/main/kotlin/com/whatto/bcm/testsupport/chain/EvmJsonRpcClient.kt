package com.whatto.bcm.testsupport.chain

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

internal data class LocalTransactionReceipt(
    val transactionHash: String,
    val successful: Boolean,
    val contractAddress: String?,
    val blockNumber: BigInteger,
    val logs: List<LocalEvmLog>,
)

internal data class LocalEvmLog(
    val address: String,
    val topics: List<String>,
    val data: String,
)

internal class EvmJsonRpcClient(
    val rpcUrl: String,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    private val client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private val requestId = AtomicLong()

    fun awaitReady() {
        val deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos()
        var lastFailure: Exception? = null
        while (System.nanoTime() < deadline) {
            try {
                chainId()
                return
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Anvil RPC startup wait was interrupted", exception)
            } catch (exception: Exception) {
                lastFailure = exception
                try {
                    Thread.sleep(POLL_INTERVAL.toMillis())
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Anvil RPC startup wait was interrupted", interrupted)
                }
            }
        }
        throw IllegalStateException("Anvil RPC did not become ready", lastFailure)
    }

    fun chainId(): Long = quantity(call("eth_chainId")).longValueExact()

    fun accounts(): List<String> =
        call("eth_accounts")
            .iterator()
            .asSequence()
            .map { normalizedAddress(it.asString()) }
            .toList()

    fun transactionCount(address: String): BigInteger =
        quantity(call("eth_getTransactionCount", listOf(normalizedAddress(address), "latest")))

    fun nativeBalance(address: String): BigInteger = quantity(call("eth_getBalance", listOf(normalizedAddress(address), "latest")))

    fun setNativeBalance(
        address: String,
        balanceWei: BigInteger,
    ) {
        require(balanceWei.signum() >= 0) { "native balance must not be negative" }
        call(
            "anvil_setBalance",
            listOf(normalizedAddress(address), "0x${balanceWei.toString(16)}"),
        )
        check(nativeBalance(address) == balanceWei) { "Anvil did not apply native balance update" }
    }

    fun blockNumber(): BigInteger = quantity(call("eth_blockNumber"))

    fun mineBlock() {
        call("evm_mine")
    }

    fun tokenBalance(
        contractAddress: String,
        ownerAddress: String,
    ): BigInteger {
        val data = BALANCE_OF_SELECTOR + normalizedAddress(ownerAddress).removePrefix("0x").padStart(64, '0')
        val result = callContract(contractAddress, data)
        require(result.startsWith("0x")) { "EVM contract result is not hex" }
        return BigInteger(result.removePrefix("0x").ifEmpty { "0" }, 16)
    }

    fun deploy(
        from: String,
        data: String,
    ): LocalTransactionReceipt {
        val hash = sendTransaction(from = from, data = data)
        return waitForReceipt(hash).also { receipt ->
            check(receipt.successful && receipt.contractAddress != null) { "contract deployment failed" }
        }
    }

    fun sendTransaction(
        from: String,
        to: String? = null,
        data: String,
    ): String {
        val transaction = linkedMapOf<String, String>("from" to normalizedAddress(from), "data" to normalizedHex(data))
        if (to != null) transaction["to"] = normalizedAddress(to)
        return call("eth_sendTransaction", listOf(transaction)).asString()
    }

    fun sendRawTransaction(signedTransaction: String): String =
        call("eth_sendRawTransaction", listOf(normalizedHex(signedTransaction))).asString()

    fun callContract(
        to: String,
        data: String,
    ): String =
        call(
            "eth_call",
            listOf(mapOf("to" to normalizedAddress(to), "data" to normalizedHex(data)), "latest"),
        ).asString()

    fun waitForReceipt(transactionHash: String): LocalTransactionReceipt {
        val deadline = System.nanoTime() + RECEIPT_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            val result = call("eth_getTransactionReceipt", listOf(transactionHash))
            if (!result.isNull) {
                return LocalTransactionReceipt(
                    transactionHash = transactionHash,
                    successful = quantity(result.required("status")).let { it == BigInteger.ONE },
                    contractAddress =
                        result
                            .get("contractAddress")
                            ?.takeUnless(JsonNode::isNull)
                            ?.asString()
                            ?.let(::normalizedAddress),
                    blockNumber = quantity(result.required("blockNumber")),
                    logs =
                        result
                            .required("logs")
                            .iterator()
                            .asSequence()
                            .map { log ->
                                LocalEvmLog(
                                    address = normalizedAddress(log.required("address").asString()),
                                    topics =
                                        log
                                            .required("topics")
                                            .iterator()
                                            .asSequence()
                                            .map { it.asString().lowercase() }
                                            .toList(),
                                    data = normalizedHex(log.required("data").asString()),
                                )
                            }.toList(),
                )
            }
            Thread.sleep(POLL_INTERVAL.toMillis())
        }
        throw IllegalStateException("EVM receipt timed out")
    }

    fun code(address: String): String = call("eth_getCode", listOf(normalizedAddress(address), "latest")).asString()

    fun codeHash(address: String): String = call("web3_sha3", listOf(code(address))).asString().lowercase()

    fun snapshot(): String = call("evm_snapshot").asString()

    fun revert(snapshotId: String): Boolean = call("evm_revert", listOf(snapshotId)).asBoolean()

    private fun call(
        method: String,
        params: List<Any> = emptyList(),
    ): JsonNode {
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to requestId.incrementAndGet(),
                    "method" to method,
                    "params" to params,
                ),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create(rpcUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "EVM RPC HTTP error: method=$method status=${response.statusCode()}" }
        val document = objectMapper.readTree(response.body())
        val error = document.get("error")
        check(error == null || error.isNull) {
            "EVM RPC error: method=$method code=${error?.get("code")?.asInt()}"
        }
        return checkNotNull(document.get("result")) { "EVM RPC result is missing: method=$method" }
    }

    private fun JsonNode.required(field: String): JsonNode = checkNotNull(get(field)) { "EVM receipt field is missing: $field" }

    private fun quantity(node: JsonNode): BigInteger {
        val value = node.asString()
        require(value.startsWith("0x")) { "EVM quantity is not hex" }
        return BigInteger(value.removePrefix("0x").ifEmpty { "0" }, 16)
    }

    private fun normalizedHex(value: String): String {
        require(value.startsWith("0x") && value.length % 2 == 0) { "invalid EVM hex" }
        require(value.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "invalid EVM hex" }
        return value.lowercase()
    }

    private fun normalizedAddress(value: String): String {
        val raw = value.removePrefix("0x")
        require(raw.length == 40 && raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "invalid EVM address" }
        return "0x${raw.lowercase()}"
    }

    companion object {
        private const val BALANCE_OF_SELECTOR = "0x70a08231"
        private val REQUEST_TIMEOUT = Duration.ofSeconds(5)
        private val STARTUP_TIMEOUT = Duration.ofSeconds(10)
        private val RECEIPT_TIMEOUT = Duration.ofSeconds(10)
        private val POLL_INTERVAL = Duration.ofMillis(25)
    }
}
