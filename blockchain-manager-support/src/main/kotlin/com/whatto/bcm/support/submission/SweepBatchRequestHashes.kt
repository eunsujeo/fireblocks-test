package com.whatto.bcm.support.submission

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class SweepBatchHashItem(
    val sourceAddress: String,
    val amount: String,
)

data class SweepBatchRequestFingerprint(
    val requestHash: String,
    val items: List<SweepBatchHashItem>,
    val totalAmount: String,
)

object SweepBatchRequestHashes {
    private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

    fun batchV1(
        network: String,
        symbol: String,
        tokenContractAddress: String,
        sweepContractAddress: String,
        executionId: String,
        items: List<SweepBatchHashItem>,
    ): SweepBatchRequestFingerprint {
        require(network.isNotBlank()) { "batch network must not be blank" }
        require(symbol.isNotBlank()) { "batch symbol must not be blank" }
        val normalizedExecutionId = normalizedExecutionId(executionId)
        val tokenContract = normalizedAddress(tokenContractAddress)
        val sweepContract = normalizedAddress(sweepContractAddress)
        val normalizedItems =
            items
                .map { SweepBatchHashItem(normalizedAddress(it.sourceAddress), positiveAmount(it.amount)) }
                .sortedBy { it.sourceAddress }
        require(normalizedItems.isNotEmpty()) { "batch must contain at least one item" }
        require(normalizedItems.map { it.sourceAddress }.distinct().size == normalizedItems.size) {
            "batch source addresses must be unique"
        }
        val canonical =
            buildList {
                add("BATCH_V1")
                add(network)
                add(symbol)
                add(tokenContract)
                add(sweepContract)
                add(normalizedExecutionId)
                normalizedItems.forEachIndexed { index, item ->
                    add("${index + 1}|${item.sourceAddress}|${item.amount}")
                }
            }.joinToString("\n")
        val total = normalizedItems.map { BigDecimal(it.amount) }.fold(BigDecimal.ZERO, BigDecimal::add)
        return SweepBatchRequestFingerprint(
            requestHash = sha256(canonical),
            items = normalizedItems,
            totalAmount = total.normalized(),
        )
    }

    private fun normalizedAddress(value: String): String {
        require(EVM_ADDRESS.matches(value)) { "invalid EVM address" }
        return value.lowercase()
    }

    private fun normalizedExecutionId(value: String): String {
        val normalized = value.lowercase()
        val uuid =
            runCatching { UUID.fromString(normalized) }
                .getOrElse { throw IllegalArgumentException("batch executionId must be a canonical UUID v7", it) }
        require(uuid.toString() == normalized && uuid.version() == 7 && uuid.variant() == 2) {
            "batch executionId must be a canonical UUID v7"
        }
        return normalized
    }

    private fun positiveAmount(value: String): String =
        BigDecimal(value)
            .also { require(it.signum() > 0) { "batch item amount must be positive" } }
            .normalized()

    private fun sha256(canonical: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun BigDecimal.normalized(): String = stripTrailingZeros().toPlainString()
}
