package com.whatto.bcm.support.submission

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SubmissionRequestFingerprint(
    val requestHash: String,
    val hashVersion: String,
    val normalizedAmount: String,
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
        val normalizedAmount = BigDecimal(amount).stripTrailingZeros().toPlainString()
        val canonical =
            listOf(
                "CONTRACT_CALL",
                senderAccountId,
                contractAddress.lowercase(),
                network,
                symbol,
                normalizedAmount,
                callData.lowercase(),
            ).joinToString("\n")
        return SubmissionRequestFingerprint(
            requestHash = sha256(canonical),
            hashVersion = "cc-v1",
            normalizedAmount = normalizedAmount,
        )
    }

    private fun sha256(canonical: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
