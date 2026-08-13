package com.whatto.bcm.support.submission

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SubmissionRequestFingerprint(
    val requestHash: String,
    val hashVersion: String,
    val normalizedAmount: String,
    val normalizedCallData: String? = null,
)

object SubmissionRequestHashes {
    private const val HASH_VERSION = "v1"

    fun v1(
        senderType: String,
        senderAccountId: String,
        recipientType: String,
        recipientValue: String,
        network: String,
        symbol: String,
        amount: String,
    ): SubmissionRequestFingerprint {
        val normalizedAmount = BigDecimal(amount).stripTrailingZeros().toPlainString()
        val canonical =
            listOf(
                senderType,
                senderAccountId,
                recipientType,
                recipientValue,
                network,
                symbol,
                normalizedAmount,
            ).joinToString("\n")
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }

        return SubmissionRequestFingerprint(
            requestHash = digest,
            hashVersion = HASH_VERSION,
            normalizedAmount = normalizedAmount,
        )
    }

    fun contractCallV1(
        senderAccountId: String,
        contractAddress: String,
        network: String,
        symbol: String,
        amount: String,
        callData: String,
    ): SubmissionRequestFingerprint {
        require(SubmissionAmounts.isNonNegativeAndFits(amount)) {
            "contract call amount must be non-negative and fit NUMERIC(36,18) without rounding"
        }
        val normalizedAmount = BigDecimal(amount).stripTrailingZeros().toPlainString()
        val normalizedCallData = callData.lowercase()
        require(normalizedCallData.matches(CALL_DATA_PATTERN)) {
            "contract call data must be non-empty even-byte 0x hex"
        }
        val canonical =
            listOf(
                "CONTRACT_CALL",
                senderAccountId,
                contractAddress.lowercase(),
                network,
                symbol,
                normalizedAmount,
                normalizedCallData,
            ).joinToString("\n")
        return SubmissionRequestFingerprint(
            requestHash = sha256(canonical),
            hashVersion = "cc-v1",
            normalizedAmount = normalizedAmount,
            normalizedCallData = normalizedCallData,
        )
    }

    private fun sha256(canonical: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private val CALL_DATA_PATTERN = Regex("^0x(?:[0-9a-f]{2})+$")
}
