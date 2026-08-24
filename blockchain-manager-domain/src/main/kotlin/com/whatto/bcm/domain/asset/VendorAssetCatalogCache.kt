package com.whatto.bcm.domain.asset

data class VendorAssetCatalogSnapshot(
    val vendorBlockchainId: String,
    val syncedAt: String,
    val assets: List<VendorAssetCatalogSnapshotAsset>,
)

data class VendorAssetCatalogSnapshotAsset(
    val vendorAssetId: String,
    val symbol: String,
    val displayName: String?,
    val assetClass: String?,
    val decimals: Int?,
    val contractAddress: String?,
)

data class VendorAssetCatalogCandidate(
    val network: String,
    val symbol: String,
    val displayName: String?,
    val assetClass: String?,
    val decimals: Int?,
    val contractAddress: String?,
    val catalogSyncedAt: String,
)

data class VendorAssetCatalogSource(
    val network: String,
    val state: VendorAssetCatalogCacheState,
    val catalogSyncedAt: String?,
)

enum class VendorAssetCatalogCacheState {
    READY,
    STALE,
    NEVER_SYNCED,
}

data class VendorAssetCatalogSearchResult(
    val items: List<VendorAssetCatalogCandidate>,
    val sources: List<VendorAssetCatalogSource>,
)

interface VendorAssetCatalogCacheRepository {
    fun replaceSnapshot(snapshot: VendorAssetCatalogSnapshot)

    fun search(
        query: String,
        network: String?,
        staleBefore: String,
        limit: Int,
    ): VendorAssetCatalogSearchResult
}
