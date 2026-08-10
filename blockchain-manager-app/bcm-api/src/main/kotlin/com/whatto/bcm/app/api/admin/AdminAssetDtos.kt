package com.whatto.bcm.app.api.admin

import com.fasterxml.jackson.annotation.JsonProperty
import com.whatto.bcm.app.application.asset.AssetCandidate
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** Admin 계약에는 벤더 id를 담지 않는다. candidateId도 해석하지 않는 일회성 불투명 손잡이다. */
data class NetworkData(
    val candidateId: String,
    val code: String?,
    val displayName: String,
    val chainId: Long?,
    val testnet: Boolean,
    val deprecated: Boolean,
    val syncedAt: String,
) {
    companion object {
        fun from(catalog: VendorBlockchainCatalog) =
            NetworkData(
                candidateId = catalog.candidateId,
                code = catalog.network,
                displayName = catalog.displayName,
                chainId = catalog.chainId,
                testnet = catalog.testnet,
                deprecated = catalog.deprecated,
                syncedAt = catalog.syncedAt,
            )
    }
}

data class AdoptNetworkRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val candidateId: String?,
)

data class AssetCandidateData(
    val network: String,
    val symbol: String,
    val displayName: String?,
    val decimals: Int?,
    val contractAddress: String?,
    val native: Boolean,
) {
    companion object {
        fun from(candidate: AssetCandidate) =
            AssetCandidateData(
                candidate.network,
                candidate.symbol,
                candidate.displayName,
                candidate.decimals,
                candidate.contractAddress,
                candidate.native,
            )
    }
}

data class RegisterAssetMappingRequest(
    @field:NotBlank
    @field:Pattern(regexp = AdminAssetController.NETWORK_PATTERN)
    val network: String?,
    @field:NotBlank
    @field:Pattern(regexp = AdminAssetController.SYMBOL_PATTERN)
    val symbol: String?,
    @field:Size(max = 128)
    @param:JsonProperty(value = "contractAddress", required = true)
    val contractAddress: String?,
)

data class AssetMappingData(
    val network: String,
    val symbol: String,
    val contractAddress: String?,
    val registeredAt: String,
) {
    companion object {
        fun from(mapping: VendorAssetMapping) =
            AssetMappingData(
                mapping.network,
                mapping.symbol,
                mapping.contractAddress,
                mapping.registeredAt,
            )
    }
}
