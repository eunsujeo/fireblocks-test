package com.whatto.bcm.domain.sweep

interface Erc20ContractPort {
    fun decimals(
        network: String,
        tokenContractAddress: String,
    ): Int

    fun allowance(
        network: String,
        tokenContractAddress: String,
        ownerAddress: String,
        spenderAddress: String,
    ): SweepAllowanceObservation

    fun approvalCallData(
        spenderAddress: String,
        amount: String,
        decimals: Int,
    ): String
}
