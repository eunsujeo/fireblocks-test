package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.asset.AssetDecimals
import com.whatto.bcm.domain.asset.ChainModel
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.BcmException
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.vendor.ChainAssetLocator
import com.whatto.bcm.domain.vendor.ChainAssetResolution
import com.whatto.bcm.domain.vendor.ChainAssetResolver
import com.whatto.bcm.domain.vendor.ResolvedChainAsset

/**
 * ChainAssetResolver의 Dfns 구현 — Dfns 데이터셋의 자산 등록 관문(계약13 "Dfns 데이터셋의 자산 매핑").
 * Dfns 공개 명세에는 자산 카탈로그 API가 없고 채택 명세의 Call Function 응답은 형식이 정해져 있지 않아 벤더 재해소로 컨트랙트를 검증하지 못한다.
 * 따라서 여기서 검증하는 것은 (1) 데이터셋 네트워크 행(`bcm_blkc_m.vndr_blkc_id`)과 실행 설정 `bcm.dfns.networks`의 Dfns network 일치,
 * (2) 데이터셋 행의 계정·자산 모델(`chain_mdl_dvcd`) — EVM은 Native/ERC-20(명세 EVM 주소 형식), SOLANA는 Native/SPL·Token-2022
 * (mint는 base58 32바이트, Token Program은 운영자가 `tokenStandard`로 명시), 모델이 없는 행은 거절, (3) 저장 가능한 키 길이다.
 * 온체인 존재·decimals·발행사 대조는 운영자의 발행사 공식 자료와 별도 수용 항목이며 코드가 추정하지 않는다.
 * Fireblocks 후보 assetId는 이 원천에 적용되지 않으므로 값이 있으면 거절한다 — Dfns 값을 Fireblocks 필드에 채우지 않는다.
 * 정밀도는 카탈로그가 없어 해소로 얻을 수 없으므로 운영자 등록값을 요구한다.
 */
class DfnsChainAssetResolver(
    private val properties: DfnsProperties,
) : ChainAssetResolver {
    /** 모든 검사가 벤더 호출 없이 끝나므로 inspect와 해소의 판정은 같다. */
    override fun inspect(
        blockchain: VendorBlockchainCatalog,
        locator: ChainAssetLocator,
    ): BcmException? = (resolve(blockchain, locator) as? ChainAssetResolution.Rejected)?.failure

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
        val key =
            when (blockchain.chainModel) {
                null -> return rejected(network, "assetModelUnsupported")
                ChainModel.EVM -> evmKey(vendorNetwork, locator) ?: return rejected(network, evmFailure(locator))
                ChainModel.SOLANA -> solanaKey(vendorNetwork, locator) ?: return rejected(network, solanaFailure(locator))
            }
        if (key.length > VendorAssetMapping.VENDOR_ASSET_ID_MAX_LENGTH) return rejected(network, "vendorAssetIdTooLong")
        // Dfns에는 자산 카탈로그가 없어 정밀도를 해소로 얻을 수 없다 — 운영자가 발행사 자료와 대조해 등록해야 한다(03 V27·07).
        val decimals = locator.decimals ?: return rejected(network, "decimalsRequired")
        if (!AssetDecimals.isValid(decimals)) return rejected(network, "decimalsOutOfRange")
        return ChainAssetResolution.Resolved(ResolvedChainAsset(key, locator.contractAddress, decimals))
    }

    private fun evmKey(
        vendorNetwork: String,
        locator: ChainAssetLocator,
    ): String? {
        if (locator.tokenStandard != null) return null
        val contract = locator.contractAddress ?: return DfnsAssetKeys.native(vendorNetwork)
        if (!DfnsAssetKeys.EVM_CONTRACT_PATTERN.matches(contract)) return null
        return DfnsAssetKeys.erc20(vendorNetwork, contract)
    }

    private fun evmFailure(locator: ChainAssetLocator) =
        if (locator.tokenStandard !=
            null
        ) {
            "tokenStandardNotApplicable"
        } else {
            "contractAddressInvalid"
        }

    private fun solanaKey(
        vendorNetwork: String,
        locator: ChainAssetLocator,
    ): String? {
        val mint = locator.contractAddress
        if (mint == null) return if (locator.tokenStandard == null) DfnsAssetKeys.native(vendorNetwork) else null
        val standard = locator.tokenStandard ?: return null
        if (!DfnsAssetKeys.isSolanaPublicKey(mint)) return null
        return DfnsAssetKeys.spl(vendorNetwork, standard, mint)
    }

    private fun solanaFailure(locator: ChainAssetLocator) =
        when {
            locator.contractAddress == null -> "tokenStandardNotApplicable"
            locator.tokenStandard == null -> "tokenStandardRequired"
            else -> "mintAddressInvalid"
        }

    private fun rejected(
        network: String,
        reason: String,
    ) = ChainAssetResolution.Rejected(InvalidAssetMappingException(network, reason))
}
