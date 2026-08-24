package com.whatto.bcm.admin.client

interface BcmAdminReadGateway {
    fun networks(
        q: String?,
        chainId: Long?,
        adopted: Boolean?,
        testnet: Boolean?,
    ): List<AdminNetwork>

    fun assetMappings(
        network: String?,
        symbol: String?,
    ): List<AdminAssetMapping>

    fun transactionInvestigation(identifier: String): AdminTransactionInvestigation

    fun contracts(): List<AdminContract>

    fun policies(): List<AdminPolicy>

    fun bandS(): List<AdminBandS>

    fun executionGates(): AdminExecutionGateOverview

    fun changeRequest(requestId: String): AdminChangeRequest
}

class SourceFailure(
    val source: String,
    val status: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
