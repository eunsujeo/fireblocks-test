package com.whatto.bcm.domain.vendor

import java.math.BigDecimal

interface VendorNetworkFeePort {
    fun estimateNetworkFee(vendorAssetId: String): VendorNetworkFeeEstimate
}

data class VendorNetworkFeeEstimate(
    val low: VendorNetworkFee,
    val medium: VendorNetworkFee,
    val high: VendorNetworkFee,
) {
    fun byLevel(): Map<VendorFeeLevel, VendorNetworkFee> =
        linkedMapOf(
            VendorFeeLevel.LOW to low,
            VendorFeeLevel.MEDIUM to medium,
            VendorFeeLevel.HIGH to high,
        )
}

data class VendorNetworkFee(
    val feePerByte: BigDecimal? = null,
    val gasPrice: BigDecimal? = null,
    val networkFee: BigDecimal? = null,
    val baseFee: BigDecimal? = null,
    val priorityFee: BigDecimal? = null,
) {
    init {
        require(listOf(feePerByte, gasPrice, networkFee, baseFee, priorityFee).any { it != null }) {
            "network fee estimate must contain at least one value"
        }
    }
}
