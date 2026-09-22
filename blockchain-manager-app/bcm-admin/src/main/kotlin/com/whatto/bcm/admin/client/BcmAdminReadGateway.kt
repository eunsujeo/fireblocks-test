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

    fun assetCandidates(
        query: String,
        network: String?,
    ): AdminAssetCandidateSearchResult

    fun adoptNetwork(command: AdoptAdminNetwork): AdminNetwork

    fun registerAssetMapping(command: RegisterAdminAssetMapping): AdminAssetMapping

    fun registerAssetMappings(commands: List<RegisterAdminAssetMapping>): List<AdminAssetMapping>

    fun startVaultReconciliation(query: String?): AdminVaultReconciliationRun

    fun vaultReconciliation(
        runId: String,
        cursor: String?,
        limit: Int,
    ): AdminVaultReconciliation

    fun transactionInvestigation(identifier: String): AdminTransactionInvestigation

    fun sweepRequestInvestigation(identifier: String): AdminSweepRequestInvestigation

    fun sweepOperations(): AdminSweepOperations

    fun contracts(): List<AdminContract>

    fun policies(): List<AdminPolicy>

    fun bandS(): List<AdminBandS>

    fun executionGates(): AdminExecutionGateOverview

    fun runtimeReadiness(): AdminRuntimeReadiness

    fun changeRequest(requestId: String): AdminChangeRequest
}

data class RegisterAdminAssetMapping(
    val network: String,
    val symbol: String,
    val fireblocksAssetId: String,
    val contractAddress: String?,
    val employeeNo: String,
    val branchCode: String,
)

data class AdoptAdminNetwork(
    val code: String,
    val candidateId: String,
    val chainModel: String,
    val employeeNo: String,
    val branchCode: String,
)

class SourceFailure(
    val source: String,
    val status: Int,
    message: String,
    cause: Throwable? = null,
    val code: String? = null,
    val details: SourceFailureDetails? = null,
) : RuntimeException(message, cause)

data class SourceFailureDetails(
    val index: Int,
    val network: String,
    val symbol: String,
    val reason: String,
)
