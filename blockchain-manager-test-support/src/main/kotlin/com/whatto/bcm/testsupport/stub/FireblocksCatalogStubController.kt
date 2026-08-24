package com.whatto.bcm.testsupport.stub

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** T11.1의 Client 조립 확인용 최소 endpoint. 상태형 카탈로그는 T11.3에서 확장한다. */
@RestController
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class FireblocksCatalogStubController(
    private val chain: LocalStubChainState,
) {
    @GetMapping("/v1/blockchains")
    fun blockchains(
        @RequestParam pageSize: Int,
        @RequestParam(required = false) pageCursor: String?,
    ): BlockchainListResponse {
        check(pageSize == FIREBLOCKS_PAGE_SIZE) { "unsupported blockchain page size" }
        check(pageCursor == null) { "bootstrap catalog has no next page" }
        return BlockchainListResponse(
            data =
                chain.blockchains().map { blockchain ->
                    BlockchainResponse(
                        id = blockchain.id,
                        displayName = blockchain.displayName,
                        metadata = BlockchainMetadataResponse(deprecated = false),
                        onchain =
                            BlockchainOnchainResponse(
                                protocol = "EVM",
                                chainId = blockchain.chainId.toString(),
                                test = true,
                                signingAlgo = "MPC_ECDSA_SECP256K1",
                            ),
                    )
                },
        )
    }

    @GetMapping("/v1/assets")
    fun assets(
        @RequestParam blockchainId: String,
        @RequestParam pageSize: Int,
        @RequestParam(required = false) symbol: String?,
        @RequestParam(required = false) pageCursor: String?,
    ): AssetListResponse {
        check(chain.blockchains().any { it.id == blockchainId }) { "unsupported blockchain id" }
        check(pageSize == FIREBLOCKS_ASSET_PAGE_SIZE) { "unsupported asset page size" }
        check(pageCursor == null) { "local asset catalog has no next page" }
        val assets =
            chain.assets(blockchainId).filter { asset ->
                symbol == null ||
                    asset.displaySymbol.equals(symbol, ignoreCase = true) ||
                    asset.id.equals(symbol, ignoreCase = true)
            }
        return AssetListResponse(
            data =
                assets.map { asset ->
                    AssetResponse(
                        id = asset.id,
                        blockchainId = asset.blockchainId,
                        displayName = asset.displayName,
                        displaySymbol = asset.displaySymbol,
                        decimals = asset.decimals,
                        assetClass = asset.assetClass,
                        onchain = AssetOnchainResponse(address = asset.contractAddress, decimals = asset.decimals),
                    )
                },
        )
    }

    companion object {
        private const val FIREBLOCKS_PAGE_SIZE = 500
        private const val FIREBLOCKS_ASSET_PAGE_SIZE = 1000
    }
}

data class BlockchainListResponse(
    val data: List<BlockchainResponse>,
    val next: String? = null,
)

data class BlockchainResponse(
    val id: String,
    val displayName: String,
    val metadata: BlockchainMetadataResponse,
    val onchain: BlockchainOnchainResponse,
)

data class BlockchainMetadataResponse(
    val deprecated: Boolean,
)

data class BlockchainOnchainResponse(
    val protocol: String,
    val chainId: String,
    val test: Boolean,
    val signingAlgo: String,
)

data class AssetListResponse(
    val data: List<AssetResponse>,
    val next: String? = null,
)

data class AssetResponse(
    val id: String,
    val blockchainId: String,
    val displayName: String,
    val displaySymbol: String,
    val decimals: Int,
    val assetClass: String,
    val onchain: AssetOnchainResponse,
)

data class AssetOnchainResponse(
    val address: String?,
    val decimals: Int,
)
