package com.whatto.bcm.domain.vendor

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 온체인 이동 사건의 순수 계약(계약13 "웹훅 온체인 이동 사건 관찰") — 종류·방향·상태 대응과 관찰값 불변식이다. */
class NetworkChainEventContractTest {
    @Test
    fun `지갑 대상으로 문서화된 두 종류와 방향·상태 원어만 옮긴다`() {
        assertThat(NetworkChainEventKind.ofVendorKind("wallet.blockchainevent.detected")).isEqualTo(NetworkChainEventKind.DETECTED)
        assertThat(NetworkChainEventKind.ofVendorKind("wallet.blockchain_event.transfer.included"))
            .isEqualTo(NetworkChainEventKind.TRANSFER_INCLUDED)
        assertThat(NetworkChainEventKind.entries).hasSize(2)
        // 감시 주소 사건은 지갑이 아니라 등록 주소가 대상이고, 전송 요청 알림도 이 포트의 종류가 아니다.
        listOf(
            "address_watch.blockchain_event.transfer.confirmed",
            "wallet.transfer.confirmed",
            "wallet.transaction.confirmed",
            "",
        ).forEach { assertThat(NetworkChainEventKind.ofVendorKind(it)).describedAs(it).isNull() }

        assertThat(NetworkChainDirection.ofVendorValue("In")).isEqualTo(NetworkChainDirection.IN)
        assertThat(NetworkChainDirection.ofVendorValue("Out")).isEqualTo(NetworkChainDirection.OUT)
        listOf("in", "IN", "Deposit", "").forEach { assertThat(NetworkChainDirection.ofVendorValue(it)).describedAs(it).isNull() }

        assertThat(NetworkChainTransferStatus.ofVendorValue("Included")).isEqualTo(NetworkChainTransferStatus.INCLUDED)
        assertThat(NetworkChainTransferStatus.ofVendorValue("Confirmed")).isEqualTo(NetworkChainTransferStatus.CONFIRMED)
        listOf("confirmed", "COMPLETED", "Finalized", "").forEach {
            assertThat(NetworkChainTransferStatus.ofVendorValue(it)).describedAs(it).isNull()
        }
    }

    @Test
    fun `모델 밖 이동 종류는 대조 키와 금액 없이 원어만 남긴다`() {
        val transfer = transfer(vendorAssetId = null, vendorAssetKind = "Erc721Transfer", amount = null)

        assertThat(transfer.vendorAssetId).isNull()
        assertThat(transfer.vendorAssetKind).isEqualTo("Erc721Transfer")
        assertThat(transfer.amountBaseUnits).isNull()
    }

    @Test
    fun `금액은 최소 단위 정수만 받고 음수 블록·빈 식별자는 관찰로 받지 않는다`() {
        assertThat(transfer(amount = "0").amountBaseUnits).isEqualTo("0")

        listOf<Pair<String, () -> NetworkChainTransfer>>(
            "amountBaseUnits" to { transfer(amount = "01") },
            "amountBaseUnits" to { transfer(amount = "1.5") },
            "amountBaseUnits" to { transfer(amount = "-1") },
            "amountBaseUnits" to { transfer(amount = " 1") },
            "amountBaseUnits" to { transfer(amount = "") },
            "blockNumber" to { transfer(blockNumber = -1) },
            "network" to { transfer(network = " ") },
            "vendorWalletId" to { transfer(vendorWalletId = "") },
            "vendorAssetKind" to { transfer(vendorAssetKind = "") },
            "transactionHash" to { transfer(transactionHash = "") },
            "observedAt" to { transfer(observedAt = "") },
            "toAddress" to { transfer(to = "") },
        ).forEach { (field, build) ->
            assertThatThrownBy { build() }
                .describedAs(field)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(field)
        }
    }

    private fun transfer(
        network: String = "ETHEREUM_SEPOLIA",
        vendorWalletId: String = "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx",
        vendorAssetId: String? = "EthereumSepolia:Native",
        vendorAssetKind: String = "NativeTransfer",
        amount: String? = "1500000",
        to: String? = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47",
        transactionHash: String = "0xabc",
        blockNumber: Long = 12,
        observedAt: String = "2026-09-16T00:00:00.000Z",
    ) = NetworkChainTransfer(
        network = network,
        vendorWalletId = vendorWalletId,
        vendorWalletAddress = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47",
        vendorAssetId = vendorAssetId,
        vendorAssetKind = vendorAssetKind,
        direction = NetworkChainDirection.IN,
        status = NetworkChainTransferStatus.CONFIRMED,
        amountBaseUnits = amount,
        fromAddress = null,
        toAddress = to,
        transactionHash = transactionHash,
        blockNumber = blockNumber,
        eventIndex = "0",
        observedAt = observedAt,
    )
}
