package com.whatto.bcm.app.api.admin

import com.fasterxml.jackson.annotation.JsonProperty
import com.whatto.bcm.domain.asset.VendorAssetCatalogCandidate
import com.whatto.bcm.domain.asset.VendorAssetCatalogSearchResult
import com.whatto.bcm.domain.asset.VendorAssetCatalogSource
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
    val network: String?,
    val networkDisplayName: String,
    val chainId: Long?,
    val testnet: Boolean,
    val symbol: String,
    val displayName: String?,
    val fireblocksAssetId: String,
    val assetClass: String?,
    val decimals: Int?,
    val contractAddress: String?,
    val catalogSyncedAt: String,
    val registrationAllowed: Boolean,
    val registrationDisabledReason: String?,
) {
    companion object {
        fun from(candidate: VendorAssetCatalogCandidate) =
            AssetCandidateData(
                candidate.network,
                candidate.networkDisplayName,
                candidate.chainId,
                candidate.testnet,
                candidate.symbol,
                candidate.displayName,
                candidate.fireblocksAssetId,
                candidate.assetClass,
                candidate.decimals,
                candidate.contractAddress,
                candidate.catalogSyncedAt,
                candidate.registrationAllowed,
                candidate.registrationDisabledReason,
            )
    }
}

data class AssetCatalogSourceData(
    val network: String?,
    val networkDisplayName: String,
    val state: String,
    val catalogSyncedAt: String?,
) {
    companion object {
        fun from(source: VendorAssetCatalogSource) =
            AssetCatalogSourceData(
                source.network,
                source.networkDisplayName,
                source.state.name,
                source.catalogSyncedAt,
            )
    }
}

data class AssetCandidateSearchData(
    val items: List<AssetCandidateData>,
    val sources: List<AssetCatalogSourceData>,
) {
    companion object {
        fun from(result: VendorAssetCatalogSearchResult) =
            AssetCandidateSearchData(
                result.items.map(AssetCandidateData::from),
                result.sources.map(AssetCatalogSourceData::from),
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
    @field:NotBlank
    @field:Size(max = 64)
    val fireblocksAssetId: String?,
    @field:Size(max = 128)
    @param:JsonProperty(value = "contractAddress", required = true)
    val contractAddress: String?,
)

data class BulkRegisterAssetMappingsRequest(
    @field:Size(min = 1, max = 20)
    val items: List<@jakarta.validation.Valid RegisterAssetMappingRequest>,
)

data class AssetMappingData(
    val network: String,
    val symbol: String,
    val fireblocksAssetId: String,
    val contractAddress: String?,
    val registeredAt: String,
) {
    companion object {
        fun from(mapping: VendorAssetMapping) =
            AssetMappingData(
                mapping.network,
                mapping.symbol,
                mapping.vendorAssetId,
                mapping.contractAddress,
                mapping.registeredAt,
            )
    }
}
