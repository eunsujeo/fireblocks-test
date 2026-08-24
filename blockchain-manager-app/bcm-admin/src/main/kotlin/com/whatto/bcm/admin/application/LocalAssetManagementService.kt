package com.whatto.bcm.admin.application

import com.whatto.bcm.admin.client.AdminAssetCandidateSearchResult
import com.whatto.bcm.admin.client.AdminAssetMapping
import com.whatto.bcm.admin.client.AdminNetwork
import com.whatto.bcm.admin.client.AdoptAdminNetwork
import com.whatto.bcm.admin.client.BcmAdminReadGateway
import com.whatto.bcm.admin.client.RegisterAdminAssetMapping
import com.whatto.bcm.admin.config.AdminProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(prefix = "bcm.admin.local-asset-management", name = ["enabled"], havingValue = "true")
class LocalAssetManagementService(
    private val gateway: BcmAdminReadGateway,
    private val properties: AdminProperties,
) {
    fun candidates(
        query: String,
        network: String?,
    ): AdminAssetCandidateSearchResult = gateway.assetCandidates(query, network)

    fun register(request: LocalAssetRegistration): AdminAssetMapping =
        gateway.registerAssetMapping(
            RegisterAdminAssetMapping(
                network = request.network,
                symbol = request.symbol,
                fireblocksAssetId = request.fireblocksAssetId,
                contractAddress = request.contractAddress,
                employeeNo = properties.localAssetManagement.employeeNo,
                branchCode = properties.localAssetManagement.branchCode,
            ),
        )

    fun registerAll(requests: List<LocalAssetRegistration>): List<AdminAssetMapping> =
        gateway.registerAssetMappings(
            requests.map { request ->
                RegisterAdminAssetMapping(
                    network = request.network,
                    symbol = request.symbol,
                    fireblocksAssetId = request.fireblocksAssetId,
                    contractAddress = request.contractAddress,
                    employeeNo = properties.localAssetManagement.employeeNo,
                    branchCode = properties.localAssetManagement.branchCode,
                )
            },
        )

    fun adoptNetwork(request: LocalNetworkAdoption): AdminNetwork =
        gateway.adoptNetwork(
            AdoptAdminNetwork(
                code = request.code,
                candidateId = request.candidateId,
                employeeNo = properties.localAssetManagement.employeeNo,
                branchCode = properties.localAssetManagement.branchCode,
            ),
        )
}

data class LocalNetworkAdoption(
    val code: String,
    val candidateId: String,
)

data class LocalAssetRegistration(
    val network: String,
    val symbol: String,
    val fireblocksAssetId: String,
    val contractAddress: String?,
)
