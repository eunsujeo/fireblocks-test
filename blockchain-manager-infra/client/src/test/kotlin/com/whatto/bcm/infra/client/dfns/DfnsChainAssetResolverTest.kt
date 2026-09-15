package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.vendor.ChainAssetLocator
import com.whatto.bcm.domain.vendor.ChainAssetResolution
import com.whatto.bcm.domain.vendor.ResolvedChainAsset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Dfns 데이터셋의 자산 등록 관문(계약13) — 벤더 HTTP 호출 없이 설정·네트워크 행·자산 모델·주소 형식을 대조하고
 * 자산 매핑의 vendorAssetId를 지갑 자산 관찰과 같은 규칙의 키로 만든다.
 */
class DfnsChainAssetResolverTest {
    private val resolver =
        DfnsChainAssetResolver(
            DfnsProperties(
                baseUrl = "https://baseline.dfns.internal.test",
                authToken = "t",
                credentialId = "cr-1",
                credentialPrivateKeyPem = "unused",
                networks =
                    mapOf(
                        "ETHEREUM_SEPOLIA" to "EthereumSepolia",
                        "SOLANA_DEVNET" to "SolanaDevnet",
                        "ETHEREUM_HOODI" to "EthereumHoodi",
                    ),
            ),
        )
    private val sepolia =
        VendorBlockchainCatalog("EthereumSepolia", "ETHEREUM_SEPOLIA", 11155111, "Ethereum Sepolia", true, false, "20260915000000")
    private val solana = VendorBlockchainCatalog("SolanaDevnet", "SOLANA_DEVNET", null, "Solana Devnet", true, false, "20260915000000")
    private val contract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"

    @Test
    fun `EVM 네트워크의 ERC-20은 소문자 컨트랙트 키로, 네이티브는 Native 키로 해소하고 등록 주소는 그대로 보존한다`() {
        val results =
            resolver.resolveAll(
                sepolia,
                listOf(ChainAssetLocator("ETHEREUM_SEPOLIA", null, contract), ChainAssetLocator("ETHEREUM_SEPOLIA", null, null)),
            )

        assertThat(results).containsExactly(
            ChainAssetResolution.Resolved(ResolvedChainAsset("EthereumSepolia:Erc20:${contract.lowercase()}", contract, null)),
            ChainAssetResolution.Resolved(ResolvedChainAsset("EthereumSepolia:Native", null, null)),
        )
    }

    @Test
    fun `Fireblocks 후보 assetId가 실리면 Dfns 원천에 적용되지 않는 값이라 거절한다`() {
        assertThat(
            reason(sepolia, ChainAssetLocator("ETHEREUM_SEPOLIA", "USDC_ETH_TEST5_1ZOU", contract)),
        ).isEqualTo("fireblocksAssetIdNotApplicable")
    }

    @Test
    fun `데이터셋 네트워크 행과 실행 설정의 Dfns network가 다르거나 설정에 없는 네트워크는 등록하지 않는다`() {
        assertThat(
            reason(sepolia.copy(candidateId = "Ethereum"), ChainAssetLocator("ETHEREUM_SEPOLIA", null, contract)),
        ).isEqualTo("networkBindingMismatch")
        val unconfigured = VendorBlockchainCatalog("Base", "BASE", 8453, "Base", false, false, "20260915000000")
        assertThat(reason(unconfigured, ChainAssetLocator("BASE", null, contract))).isEqualTo("networkBindingMismatch")
        assertThat(reason(sepolia, ChainAssetLocator("ETHEREUM_HOODI", null, contract))).isEqualTo("networkBindingMismatch")
    }

    @Test
    fun `chain_id가 없는 비EVM 네트워크는 자산 locator 모델 확정 전이라 네이티브·토큰 모두 거절한다`() {
        assertThat(reason(solana, ChainAssetLocator("SOLANA_DEVNET", null, null))).isEqualTo("assetModelUnsupported")
        assertThat(
            reason(solana, ChainAssetLocator("SOLANA_DEVNET", null, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")),
        ).isEqualTo("assetModelUnsupported")
    }

    @Test
    fun `명세의 EVM 컨트랙트 형식이 아닌 주소는 거절한다`() {
        listOf(
            "0xabc",
            "A0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
            "0xZZb86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
            " $contract",
            "",
        ).forEach { address ->
            assertThat(
                reason(sepolia, ChainAssetLocator("ETHEREUM_SEPOLIA", null, address)),
            ).describedAs(address).isEqualTo("contractAddressInvalid")
        }
    }

    @Test
    fun `저장 컬럼 길이를 넘는 키는 자르지 않고 거절한다`() {
        val longNetwork = "X".repeat(30)
        val properties =
            DfnsProperties(
                baseUrl = "https://b",
                authToken = "t",
                credentialId = "c",
                credentialPrivateKeyPem = "u",
                networks =
                    mapOf(
                        "LONG" to longNetwork,
                    ),
            )
        val blockchain = VendorBlockchainCatalog(longNetwork, "LONG", 1, "Long", false, false, "20260915000000")

        val result = DfnsChainAssetResolver(properties).resolveAll(blockchain, listOf(ChainAssetLocator("LONG", null, contract))).single()

        assertThat((result as ChainAssetResolution.Rejected).failure).isInstanceOfSatisfying(InvalidAssetMappingException::class.java) {
            assertThat(it.reason).isEqualTo("vendorAssetIdTooLong")
        }
    }

    private fun reason(
        blockchain: VendorBlockchainCatalog,
        locator: ChainAssetLocator,
    ): String {
        val result = resolver.resolveAll(blockchain, listOf(locator)).single()
        val failure = (result as ChainAssetResolution.Rejected).failure as InvalidAssetMappingException
        assertThat(failure.network).isEqualTo(locator.network)
        return failure.reason
    }
}
