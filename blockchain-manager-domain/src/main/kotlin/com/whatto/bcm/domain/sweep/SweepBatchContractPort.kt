package com.whatto.bcm.domain.sweep

data class SweepBatchCallItem(
    val ownerAddress: String,
    val amount: String,
)

interface SweepBatchContractPort {
    fun batchSweepCallData(
        network: String,
        executionId: String,
        tokenContractAddress: String,
        items: List<SweepBatchCallItem>,
    ): String
}
