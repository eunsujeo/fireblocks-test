package com.whatto.bcm.domain.vendor

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 온체인 이동 관찰의 귀속 판정(계약13 "온체인 이동의 귀속") — 저장·발행·상태 번역 없이 업무 대상만 가른다. */
class NetworkChainAttributionTest {
    @Test
    fun `발급 주소로 들어온 등록 자산은 관리 입금이다`() {
        val result = NetworkChainAttribution.attribute(transfer(), ledger())

        assertThat(result).isEqualTo(
            NetworkChainAttributionResult.Deposit(transfer(), ACCOUNT_ID, NETWORK, "USDC", 6),
        )
    }

    @Test
    fun `우리 지갑 발신은 주소가 아니라 제출 원장으로 가릴 대상이다`() {
        // 등록 자산·발급 주소와 무관하게 방향이 먼저다 — 발신은 입금 귀속 경로로 보내지 않는다.
        val outgoing = transfer(direction = NetworkChainDirection.OUT, to = "0xsomewhere")
        val result = NetworkChainAttribution.attribute(outgoing, ledger(asset = null, accountId = null))

        assertThat(result).isEqualTo(NetworkChainAttributionResult.Outgoing(outgoing))
    }

    @Test
    fun `미지원 이동 종류와 미등록 자산은 예외가 아니라 결과로 가른다`() {
        val unsupported = transfer(vendorAssetId = null, vendorAssetKind = "Erc721Transfer")
        assertThat(NetworkChainAttribution.attribute(unsupported, ledger()))
            .isEqualTo(NetworkChainAttributionResult.UnsupportedAsset(unsupported))

        val unmapped = transfer()
        assertThat(NetworkChainAttribution.attribute(unmapped, ledger(asset = null)))
            .isEqualTo(NetworkChainAttributionResult.UnmappedAsset(unmapped))
    }

    @Test
    fun `목적지가 없거나 우리 주소가 아니면 미귀속으로 가른다`() {
        val noDestination = transfer(to = null)
        assertThat(NetworkChainAttribution.attribute(noDestination, ledger()))
            .isEqualTo(
                NetworkChainAttributionResult.Unattributed(noDestination, NetworkChainAttributionMiss.MISSING_DESTINATION),
            )

        val unknown = transfer()
        assertThat(NetworkChainAttribution.attribute(unknown, ledger(accountId = null)))
            .isEqualTo(
                NetworkChainAttributionResult.Unattributed(unknown, NetworkChainAttributionMiss.UNKNOWN_ADDRESS),
            )
    }

    @Test
    fun `등록 정밀도는 판정 결과에 함께 실리고 값이 없는 매핑도 귀속은 된다`() {
        // 정밀도가 없으면 이벤트 금액을 만들 수 없지만 그 판단은 워커의 몫이다 — 귀속 자체는 성립한다.
        val result = NetworkChainAttribution.attribute(transfer(), ledger(asset = LedgerAsset(NETWORK, "USDC", null)))

        assertThat((result as NetworkChainAttributionResult.Deposit).decimals).isNull()
    }

    @Test
    fun `등록 매핑의 네트워크가 관찰과 어긋나면 데이터 결함으로 중단한다`() {
        assertThatThrownBy {
            NetworkChainAttribution.attribute(transfer(), ledger(asset = LedgerAsset("BASE_SEPOLIA", "USDC", 6)))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("drift")
    }

    @Test
    fun `주소 조회는 등록 매핑의 네트워크·심볼로 한다`() {
        val asked = mutableListOf<Triple<String, String, String>>()
        val ledger =
            object : NetworkChainLedgerLookup {
                override fun assetOf(vendorAssetId: String) = LedgerAsset(NETWORK, "USDC", 6)

                override fun accountOfDepositAddress(
                    address: String,
                    network: String,
                    symbol: String,
                ): String? {
                    asked += Triple(address, network, symbol)
                    return ACCOUNT_ID
                }
            }

        NetworkChainAttribution.attribute(transfer(), ledger)

        assertThat(asked).containsExactly(Triple(DESTINATION, NETWORK, "USDC"))
    }

    private fun ledger(
        asset: LedgerAsset? = LedgerAsset(NETWORK, "USDC", 6),
        accountId: String? = ACCOUNT_ID,
    ) = object : NetworkChainLedgerLookup {
        override fun assetOf(vendorAssetId: String) = asset

        override fun accountOfDepositAddress(
            address: String,
            network: String,
            symbol: String,
        ) = accountId
    }

    private fun transfer(
        direction: NetworkChainDirection = NetworkChainDirection.IN,
        vendorAssetId: String? = "EthereumSepolia:Erc20:0x1c7d4b196cb0c7b01d743fbc6116a902379c7238",
        vendorAssetKind: String = "Erc20Transfer",
        to: String? = DESTINATION,
    ) = NetworkChainTransfer(
        network = NETWORK,
        vendorWalletId = "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx",
        vendorWalletAddress = DESTINATION,
        vendorAssetId = vendorAssetId,
        vendorAssetKind = vendorAssetKind,
        direction = direction,
        status = NetworkChainTransferStatus.CONFIRMED,
        amountBaseUnits = "1500000",
        fromAddress = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238",
        toAddress = to,
        transactionHash = "0xabc",
        blockNumber = 8_452_119,
        eventIndex = "3",
        observedAt = "1758067200",
    )

    private companion object {
        const val NETWORK = "ETHEREUM_SEPOLIA"
        const val ACCOUNT_ID = "acct_dfns_1"
        const val DESTINATION = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47"
    }
}
