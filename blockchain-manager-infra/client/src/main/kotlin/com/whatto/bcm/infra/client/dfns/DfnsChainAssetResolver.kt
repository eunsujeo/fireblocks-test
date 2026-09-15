package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.vendor.ChainAssetLocator
import com.whatto.bcm.domain.vendor.ChainAssetResolution
import com.whatto.bcm.domain.vendor.ChainAssetResolver
import com.whatto.bcm.domain.vendor.ResolvedChainAsset

/**
 * ChainAssetResolver의 Dfns 구현 — Dfns 데이터셋의 자산 등록 관문(계약13 "Dfns 데이터셋의 자산 매핑").
 * Dfns 공개 명세에는 자산 카탈로그 API가 없고 채택 명세의 Call Function 응답은 형식이 정해져 있지 않아 벤더 재해소로 컨트랙트를 검증하지 못한다.
 * 따라서 여기서 검증하는 것은 (1) 데이터셋 네트워크 행(`bcm_blkc_m.vndr_blkc_id`)과 실행 설정 `bcm.dfns.networks`의 Dfns network 일치,
 * (2) 자산 모델 — EVM(`chain_id` 있는 네트워크)만 등록 가능하며 Native/ERC-20으로 해소, (3) 명세의 EVM 컨트랙트 주소 형식,
 * (4) 저장 가능한 키 길이다. 온체인 존재·decimals·발행사 대조는 운영자의 발행사 공식 자료와 별도 수용 항목이며 코드가 추정하지 않는다.
 * Fireblocks 후보 assetId는 이 원천에 적용되지 않으므로 값이 있으면 거절한다 — Dfns 값을 Fireblocks 필드에 채우지 않는다.
 */
class DfnsChainAssetResolver(
    private val properties: DfnsProperties,
) : ChainAssetResolver {
    override fun resolveAll(
        blockchain: VendorBlockchainCatalog,
        locators: List<ChainAssetLocator>,
    ): List<ChainAssetResolution> = locators.map { resolve(blockchain, it) }

    private fun resolve(
        blockchain: VendorBlockchainCatalog,
        locator: ChainAssetLocator,
    ): ChainAssetResolution {
        val network = locator.network
        if (locator.fireblocksAssetId != null) return rejected(network, "fireblocksAssetIdNotApplicable")
        val vendorNetwork = properties.networks[network] ?: return rejected(network, "networkBindingMismatch")
        if (blockchain.network != network || blockchain.candidateId != vendorNetwork) return rejected(network, "networkBindingMismatch")
        // EVM 계정 모델만 등록한다 — Solana(mint·Token Program·token account)는 자산 locator 모델 확정 뒤다(07·계획).
        if (blockchain.chainId == null) return rejected(network, "assetModelUnsupported")
        val contract = locator.contractAddress
        val key =
            if (contract == null) {
                DfnsAssetKeys.native(vendorNetwork)
            } else {
                if (!DfnsAssetKeys.EVM_CONTRACT_PATTERN.matches(contract)) return rejected(network, "contractAddressInvalid")
                DfnsAssetKeys.erc20(vendorNetwork, contract)
            }
        if (key.length > VendorAssetMapping.VENDOR_ASSET_ID_MAX_LENGTH) return rejected(network, "vendorAssetIdTooLong")
        return ChainAssetResolution.Resolved(ResolvedChainAsset(key, contract, null))
    }

    private fun rejected(
        network: String,
        reason: String,
    ) = ChainAssetResolution.Rejected(InvalidAssetMappingException(network, reason))
}
