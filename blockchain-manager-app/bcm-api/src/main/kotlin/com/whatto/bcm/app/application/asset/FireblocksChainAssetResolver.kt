package com.whatto.bcm.app.application.asset

import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.BcmException
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
 * 필수값(후보 assetId) 누락은 `inspect`에서 카탈로그를 읽기 전에 거절한다 — 입력 오류가 외부 호출·카탈로그 장애에 가려지지 않게 한다.
 */
class FireblocksChainAssetResolver(
    private val vendorCatalog: VendorAssetCatalogPort,
) : ChainAssetResolver {
    override fun inspect(
        blockchain: VendorBlockchainCatalog,
        locator: ChainAssetLocator,
    ): BcmException? = if (locator.fireblocksAssetId == null) missingId(locator) else null

    override fun resolveAll(
        blockchain: VendorBlockchainCatalog,
        locators: List<ChainAssetLocator>,
    ): List<ChainAssetResolution> {
        // inspect를 거치지 않은 호출자를 위해 — 해소할 항목이 없으면 카탈로그를 읽지 않는다.
        if (locators.none { it.fireblocksAssetId != null }) return locators.map { ChainAssetResolution.Rejected(missingId(it)) }
        val assets = allVendorAssets(blockchain.candidateId)
        return locators.map { locator ->
            if (locator.fireblocksAssetId == null) {
                ChainAssetResolution.Rejected(missingId(locator))
            } else {
                resolve(blockchain, assets, locator)
            }
        }
    }

    private fun missingId(locator: ChainAssetLocator) = InvalidAssetMappingException(locator.network, "fireblocksAssetIdRequired")

    private fun resolve(
        blockchain: VendorBlockchainCatalog,
        assets: List<VendorAsset>,
        locator: ChainAssetLocator,
    ): ChainAssetResolution {
        val fireblocksAssetId = checkNotNull(locator.fireblocksAssetId)
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
