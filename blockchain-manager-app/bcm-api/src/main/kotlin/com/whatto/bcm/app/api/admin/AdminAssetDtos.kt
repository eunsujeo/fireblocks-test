package com.whatto.bcm.app.api.admin

import com.fasterxml.jackson.annotation.JsonProperty
import com.whatto.bcm.domain.asset.AssetDecimals
import com.whatto.bcm.domain.asset.ChainModel
import com.whatto.bcm.domain.asset.TokenStandard
import com.whatto.bcm.domain.asset.VendorAssetCatalogCandidate
import com.whatto.bcm.domain.asset.VendorAssetCatalogSearchResult
import com.whatto.bcm.domain.asset.VendorAssetCatalogSource
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.provider.ProviderOrigin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
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
    /** 계정·자산 모델. 채택된 행은 반드시 있고, 미채택 후보는 null이다(03 V35). */
    val chainModel: ChainModel?,
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
                chainModel = catalog.chainModel,
            )
    }
}

data class AdoptNetworkRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val candidateId: String?,
    /**
     * 채택하는 체인의 계정·자산 모델. **필수다** — 이 값이 없으면 자산 키를 만들 수 없고
     * 거래 관찰의 주소 동일성 비교도 정확 일치로 내려앉는다(03 V35).
     */
    @field:NotNull
    val chainModel: ChainModel?,
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

/**
 * 등록 요청 — `fireblocksAssetId`는 Fireblocks 원천의 후보 assetId다(그 원천에서 필수, 없으면 관문이 400).
 * Dfns 원천은 자산을 network·contractAddress로 지정하며 이 필드를 보내면 관문이 400으로 거절한다 — Dfns 값을 Fireblocks 필드에 채우지 않는다.
 * `tokenStandard`(SPL·SPL_2022)는 Dfns Solana 토큰(mint)에서만 필수이고 그 밖에서는 보내면 400이다.
 * `decimals`는 Dfns 원천에서 필수이고 Fireblocks 원천에서는 보내면 400이다 — 그쪽은 카탈로그가 정밀도를 소유한다(03 V27).
 */
data class RegisterAssetMappingRequest(
    @field:NotBlank
    @field:Pattern(regexp = AdminAssetController.NETWORK_PATTERN)
    val network: String?,
    @field:NotBlank
    @field:Pattern(regexp = AdminAssetController.SYMBOL_PATTERN)
    val symbol: String?,
    @field:Pattern(regexp = "\\S{1,64}")
    val fireblocksAssetId: String?,
    @field:Size(max = 128)
    @param:JsonProperty(value = "contractAddress", required = true)
    val contractAddress: String?,
    val tokenStandard: TokenStandard? = null,
    @field:Min(0)
    @field:Max(AssetDecimals.MAX.toLong())
    val decimals: Int? = null,
)

data class BulkRegisterAssetMappingsRequest(
    @field:Size(min = 1, max = 20)
    val items: List<@jakarta.validation.Valid RegisterAssetMappingRequest>,
)

/**
 * 현재 매핑 응답 — 벤더 식별자는 벤더 이름을 붙여서만 노출한다(07). 데이터셋 원천이 Fireblocks면 `fireblocksAssetId`, Dfns면 `dfnsAssetKey`가
 * 채워지고 다른 쪽은 null이다. Dfns 키를 Fireblocks 필드에 채우지 않는다(계약13).
 */
data class AssetMappingData(
    val network: String,
    val symbol: String,
    val fireblocksAssetId: String?,
    val dfnsAssetKey: String?,
    val contractAddress: String?,
    val decimals: Int?,
    val registeredAt: String,
) {
    companion object {
        fun from(
            mapping: VendorAssetMapping,
            origin: ProviderOrigin,
        ) = AssetMappingData(
            network = mapping.network,
            symbol = mapping.symbol,
            fireblocksAssetId = mapping.vendorAssetId.takeIf { origin.protocolProvider == FIREBLOCKS },
            dfnsAssetKey = mapping.vendorAssetId.takeIf { origin.protocolProvider == DFNS },
            contractAddress = mapping.contractAddress,
            decimals = mapping.decimals,
            registeredAt = mapping.registeredAt,
        )

        private const val FIREBLOCKS = "fireblocks"
        private const val DFNS = "dfns"
    }
}
