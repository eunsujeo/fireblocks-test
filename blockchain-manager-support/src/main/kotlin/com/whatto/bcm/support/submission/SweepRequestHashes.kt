package com.whatto.bcm.support.submission

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SweepRequestHashItem(
    val accountId: String,
    val sourceEventIds: List<String>,
)

data class SweepRequestFingerprint(
    val requestHash: String,
    val items: List<SweepRequestHashItem>,
)

object SweepRequestHashes {
    fun requestV1(
        network: String,
        symbol: String,
        items: List<SweepRequestHashItem>,
    ): SweepRequestFingerprint {
        require(network.isNotBlank()) { "sweep request network must not be blank" }
        require(symbol.isNotBlank()) { "sweep request symbol must not be blank" }
        require(items.isNotEmpty()) { "sweep request must contain at least one item" }
        require(items.all { it.accountId.isNotBlank() && it.sourceEventIds.isNotEmpty() }) {
            "sweep request account and source events must not be empty"
        }
        require(items.map { it.accountId }.distinct().size == items.size) {
            "sweep request account ids must be unique"
        }
        val allEventIds = items.flatMap { it.sourceEventIds }
        require(allEventIds.distinct().size == allEventIds.size) {
            "sweep request source event ids must be unique"
        }

        val normalizedItems =
            items
                .map { it.copy(sourceEventIds = it.sourceEventIds.sorted()) }
                .sortedBy { it.accountId }
        val canonical =
            buildList {
                add("sweep-request-v1")
                add(network)
                add(symbol)
                normalizedItems.forEach { item ->
                    add("${item.accountId}|${item.sourceEventIds.joinToString(",")}")
                }
            }.joinToString("\n")

        return SweepRequestFingerprint(sha256(canonical), normalizedItems)
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
