package com.whatto.bcm.app.application.asset

import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.ChainAssetLocator
import com.whatto.bcm.domain.vendor.ChainAssetResolution
import com.whatto.bcm.domain.vendor.ChainAssetResolver
import com.whatto.bcm.domain.vendor.ResolvedChainAsset
import com.whatto.bcm.domain.vendor.VendorAsset
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort

/**
 * ChainAssetResolver의 Fireblocks 구현 — 07 관문의 "그 체인에서 assetId + 주소로 자산 재해소, 캐시를 신뢰하지 않음".
 * 채택 네트워크의 자산을 끝까지 페이징(네트워크마다 한 번)한 뒤 항목마다 blockchainId·assetId·컨트랙트 주소(네이티브는 `assetClass=NATIVE`)가
 * 모두 일치하는 자산 하나를 고른다. 없으면 400, 둘 이상이면 409로 항목 실패다. 페이지 조회 실패는 네트워크 전체 실패로 전파한다.
 */
class FireblocksChainAssetResolver(
    private val vendorCatalog: VendorAssetCatalogPort,
) : ChainAssetResolver {
    override fun resolveAll(
        blockchain: VendorBlockchainCatalog,
        locators: List<ChainAssetLocator>,
    ): List<ChainAssetResolution> {
        if (locators.isEmpty()) return emptyList()
        val assets = allVendorAssets(blockchain.candidateId)
        return locators.map { resolve(blockchain, assets, it) }
    }

    private fun resolve(
        blockchain: VendorBlockchainCatalog,
        assets: List<VendorAsset>,
        locator: ChainAssetLocator,
    ): ChainAssetResolution {
        val fireblocksAssetId =
            locator.fireblocksAssetId
                ?: return ChainAssetResolution.Rejected(InvalidAssetMappingException(locator.network, "fireblocksAssetIdRequired"))
        val matches =
            assets.filter { asset ->
                asset.blockchainId == blockchain.candidateId &&
                    asset.id == fireblocksAssetId &&
                    when (locator.contractAddress) {
                        null -> asset.assetClass == NATIVE_ASSET_CLASS
                        else -> asset.contractAddress?.equals(locator.contractAddress, ignoreCase = true) == true
                    }
            }
        if (matches.isEmpty()) return ChainAssetResolution.Rejected(InvalidAssetMappingException(locator.network, "assetNotFound"))
        if (matches.size > 1) return ChainAssetResolution.Rejected(ConflictException("assetCandidate", "${locator.network}:ambiguous"))
        val vendorAsset = matches.single()
        return ChainAssetResolution.Resolved(
            ResolvedChainAsset(vendorAsset.id, locator.contractAddress?.let { vendorAsset.contractAddress }, vendorAsset.decimals),
        )
    }

    private fun allVendorAssets(candidateId: String): List<VendorAsset> {
        val assets = mutableListOf<VendorAsset>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = vendorCatalog.assets(candidateId, null, cursor)
            assets += page.data
            cursor = page.next
            if (cursor != null && !seenCursors.add(cursor)) {
                throw VendorApiException("listAssetsPagination", null)
            }
        } while (cursor != null)
        return assets
    }

    companion object {
        private const val NATIVE_ASSET_CLASS = "NATIVE"
    }
}
