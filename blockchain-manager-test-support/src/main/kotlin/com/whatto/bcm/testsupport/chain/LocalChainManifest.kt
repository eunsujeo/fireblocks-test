package com.whatto.bcm.testsupport.chain

import tools.jackson.databind.ObjectMapper

data class LocalChainManifest(
    val schemaVersion: Int = 1,
    val foundryVersion: String,
    val chainId: Long,
    val deployerAddress: String,
    val operatorAddress: String,
    val omnibusAddress: String,
    val gaslessFeePayerAddress: String,
    val customerAddresses: List<String>,
    val tokenContractAddress: String,
    val tokenCodeHash: String,
    val tokenSymbol: String,
    val tokenDecimals: Int,
    val sweepContractAddress: String,
    val sweepCodeHash: String,
    val gaslessDelegationContractAddress: String,
    val gaslessDelegationCodeHash: String,
    val maximumItems: Int,
    val maximumItemAmount: String,
    val maximumTotalAmount: String,
    val blockchainId: String = "local-evm",
    val networkCode: String = "LOCAL",
    val displayName: String = "Local EVM",
    val assets: List<LocalChainAssetManifest> =
        listOf(
            LocalChainAssetManifest(
                id = "TUSD_LOCAL",
                symbol = tokenSymbol,
                displayName = "Local $tokenSymbol",
                decimals = tokenDecimals,
                contractAddress = tokenContractAddress,
                codeHash = tokenCodeHash,
            ),
        ),
) {
    fun toJson(): String = ObjectMapper().writeValueAsString(this)

    companion object {
        const val LOCAL_CHAIN_ID = 31337L
    }
}

data class LocalChainAssetManifest(
    val id: String,
    val symbol: String,
    val displayName: String,
    val decimals: Int,
    val contractAddress: String,
    val codeHash: String,
)
