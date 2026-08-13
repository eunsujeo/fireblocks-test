package com.whatto.bcm.domain.sweep

data class SweepBatchReceipt(
    val successful: Boolean,
    val legs: List<SweepLegObservation>,
)

data class SweepLegObservation(
    val executionId: String,
    val itemSequence: Int,
    val ownerAddress: String,
    val requestedAmount: String,
    val actualAmount: String,
    val successful: Boolean,
    /** 0x 접두사 없는 64자 소문자 bytes32. */
    val failureCode: String,
    val logIndex: Int,
)

interface SweepBatchReceiptPort {
    /** 아직 영수증이 없으면 null. */
    fun receipt(
        network: String,
        transactionHash: String,
        sweepContractAddress: String,
        tokenDecimals: Int,
    ): SweepBatchReceipt?
}
