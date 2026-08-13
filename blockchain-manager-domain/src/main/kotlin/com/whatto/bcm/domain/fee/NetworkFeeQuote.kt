package com.whatto.bcm.domain.fee

import com.whatto.bcm.domain.vendor.VendorFeeLevel
import java.math.BigDecimal

data class NetworkFeeQuote(
    val network: String,
    val symbol: String,
    val observedAt: String,
    val feeLevel: VendorFeeLevel,
    val vendorAssetId: String,
    val feePerByte: BigDecimal?,
    val gasPrice: BigDecimal?,
    val networkFee: BigDecimal?,
    val baseFee: BigDecimal?,
    val priorityFee: BigDecimal?,
)

interface NetworkFeeQuoteRepository {
    fun saveAll(quotes: List<NetworkFeeQuote>): Int

    fun findLatestAtOrBefore(
        network: String,
        symbol: String,
        feeLevel: VendorFeeLevel,
        requestedAt: String,
    ): NetworkFeeQuote?

    fun findForSubmission(externalTransactionId: String): NetworkFeeQuote?

    fun findForBoost(
        originalTransactionId: String,
        attemptSequence: Int,
    ): NetworkFeeQuote?
}
