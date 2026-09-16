package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.vendor.NetworkChainDirection
import com.whatto.bcm.domain.vendor.NetworkChainEventKind
import com.whatto.bcm.domain.vendor.NetworkChainTransferStatus
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * 채택 명세 `webhooks`의 `wallet.blockchainevent.detected`·`wallet.blockchain_event.transfer.included`와
 * `WalletHistoryEvent`·`Wallet`을 근거로 한 온체인 이동 사건 해석(계약13). 두 종류가 아니면 관심 밖이고, 형식이 다르면 조용히 버리지 않는다.
 */
class DfnsNetworkChainEventParserTest {
    private val properties =
        DfnsProperties(networks = mapOf("ETHEREUM_SEPOLIA" to "EthereumSepolia", "SOLANA_DEVNET" to "SolanaDevnet"))
    private val parser = DfnsNetworkChainEventParser(ObjectMapper(), properties)

    @Test
    fun `토큰 입금 사건을 알림 메타와 등록과 같은 자산 키로 해석한다`() {
        val event = parser.parse(event().toByteArray())

        checkNotNull(event)
        assertThat(event.kind).isEqualTo(NetworkChainEventKind.DETECTED)
        assertThat(event.delivery.notificationId).isEqualTo(NOTIFICATION_ID)
        assertThat(event.delivery.deliveryAttempt).isEqualTo(1)
        val observation = event.observation
        assertThat(observation.network).isEqualTo("ETHEREUM_SEPOLIA")
        assertThat(observation.vendorWalletId).isEqualTo(WALLET_ID)
        assertThat(observation.vendorWalletAddress).isEqualTo(WALLET_ADDRESS)
        assertThat(observation.vendorAssetId).isEqualTo("EthereumSepolia:Erc20:$CONTRACT")
        assertThat(observation.vendorAssetKind).isEqualTo("Erc20Transfer")
        assertThat(observation.direction).isEqualTo(NetworkChainDirection.IN)
        assertThat(observation.status).isEqualTo(NetworkChainTransferStatus.CONFIRMED)
        assertThat(observation.amountBaseUnits).isEqualTo("1500000")
        assertThat(observation.fromAddress).isEqualTo(SENDER)
        assertThat(observation.toAddress).isEqualTo(WALLET_ADDRESS)
        assertThat(observation.transactionHash).isEqualTo(TX_HASH)
        assertThat(observation.blockNumber).isEqualTo(8_452_119L)
        assertThat(observation.eventIndex).isEqualTo("3")
        // 형식 서술이 없는 관측 시각은 파싱하지 않고 원문 그대로 둔다.
        assertThat(observation.observedAt).isEqualTo("1758067200")
    }

    @Test
    fun `두 종류를 모두 받고 포함 단계와 확인 단계를 구분한다`() {
        val included =
            parser.parse(
                event(kind = "wallet.blockchain_event.transfer.included", status = "Included").toByteArray(),
            )

        assertThat(included?.kind).isEqualTo(NetworkChainEventKind.TRANSFER_INCLUDED)
        assertThat(included?.observation?.status).isEqualTo(NetworkChainTransferStatus.INCLUDED)
        // 종류와 상태가 고정 대응한다고 보지 않는다 — 상태는 사건 본문에서 읽는다.
        val detectedButIncluded = parser.parse(event(status = "Included").toByteArray())
        assertThat(detectedButIncluded?.kind).isEqualTo(NetworkChainEventKind.DETECTED)
        assertThat(detectedButIncluded?.observation?.status).isEqualTo(NetworkChainTransferStatus.INCLUDED)
    }

    @Test
    fun `네이티브·Solana mint 이동도 등록과 같은 키 규칙으로 해석한다`() {
        val native = parser.parse(event(assetKind = "NativeTransfer", locator = null).toByteArray())
        assertThat(native?.observation?.vendorAssetId).isEqualTo("EthereumSepolia:Native")

        val spl =
            parser.parse(
                event(
                    network = "SolanaDevnet",
                    assetKind = "Spl2022Transfer",
                    locatorField = "mint",
                    locator = MINT,
                    wallet = """{"id":"$WALLET_ID","network":"SolanaDevnet"}""",
                ).toByteArray(),
            )
        assertThat(spl?.observation?.vendorAssetId).isEqualTo("SolanaDevnet:Spl2022:$MINT")
        assertThat(spl?.observation?.network).isEqualTo("SOLANA_DEVNET")
    }

    @Test
    fun `문서화된 미지원 이동 종류는 사건을 버리지 않고 대조 키와 금액 없이 원어만 남긴다`() {
        listOf("Erc721Transfer", "UtxoTransfer", "Trc20Transfer", "HtsTransfer", "Sep41Transfer").forEach { assetKind ->
            val parsed = parser.parse(event(assetKind = assetKind, locator = null).toByteArray())
            assertThat(parsed?.observation?.vendorAssetId).describedAs(assetKind).isNull()
            assertThat(parsed?.observation?.vendorAssetKind).describedAs(assetKind).isEqualTo(assetKind)
            assertThat(parsed?.observation?.amountBaseUnits).describedAs(assetKind).isNull()
        }
        // 명세에 없는 종류는 미지원으로 받아들이지 않는다 — 어느 변형도 만족하지 않는 본문이다.
        listOf("FooTransfer", "Erc20", "erc20transfer", "Erc20Transfer ").forEach { assetKind ->
            assertThatThrownBy { parser.parse(event(assetKind = assetKind).toByteArray()) }
                .describedAs(assetKind)
                .isInstanceOf(WebhookPayloadException::class.java)
                .hasMessageContaining("kind")
        }
    }

    @Test
    fun `모델 대상 변형이 요구하는 필드는 관찰에 담지 않아도 결손을 거절한다`() {
        listOf(
            event(from = null) to "from",
            event(to = null) to "to",
            event(decimals = null) to "decimals",
            event(decimals = """"6"""") to "decimals",
            event(assetKind = "NativeTransfer", locator = null, symbol = null) to "symbol",
            event(assetKind = "NativeTransfer", locator = null, decimals = null) to "decimals",
            event(metadata = null) to "metadata",
            event(metadata = """{"fee":{"symbol":"ETH"}}""") to "metadata.asset",
        ).forEach { (body, field) ->
            assertThatThrownBy { parser.parse(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(WebhookPayloadException::class.java)
                .hasMessageContaining(field)
        }
        // Solana 변형은 명세상 from·to·decimals를 요구하지 않는다.
        val spl =
            parser.parse(
                event(
                    network = "SolanaDevnet",
                    assetKind = "SplTransfer",
                    locatorField = "mint",
                    locator = MINT,
                    from = null,
                    to = null,
                    symbol = null,
                    decimals = null,
                    wallet = """{"id":"$WALLET_ID","network":"SolanaDevnet"}""",
                ).toByteArray(),
            )
        assertThat(spl?.observation?.toAddress).isNull()
        assertThat(spl?.observation?.amountBaseUnits).isEqualTo("1500000")
    }

    @Test
    fun `온체인 이동이 아닌 종류는 이 포트의 관심 밖이다`() {
        listOf(
            "wallet.transfer.confirmed",
            "wallet.transaction.confirmed",
            "address_watch.blockchain_event.transfer.confirmed",
            "wallet.created",
        ).forEach { kind ->
            assertThat(parser.parse(event(kind = kind).toByteArray())).describedAs(kind).isNull()
        }
    }

    @Test
    fun `사건 본문·지갑 결손과 지갑 ID 불일치·설정 밖 네트워크는 거절한다`() {
        listOf(
            """{"id":"$NOTIFICATION_ID","date":"$DATE","deliveryAttempt":1,"kind":"wallet.blockchainevent.detected","data":{}}""" to
                "data.blockchainEvent",
            event(wallet = null) to "data.wallet",
            event(wallet = """{"id":"wa-other-00000-xxxxxxxxxxxxxxxx"}""") to "data.wallet.id",
            event(wallet = """{"network":"EthereumSepolia","address":"$WALLET_ADDRESS"}""") to "id",
            event(network = "EthereumMainnet", wallet = """{"id":"$WALLET_ID","network":"EthereumMainnet"}""") to "network",
            event(wallet = """{"id":"$WALLET_ID","network":"Ethereum"}""") to "data.wallet.network",
        ).forEach { (body, field) ->
            assertThatThrownBy { parser.parse(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(WebhookPayloadException::class.java)
                .hasMessageContaining(field)
        }
    }

    @Test
    fun `명세 필수 필드 결손과 형식 오류는 사건으로 받지 않는다`() {
        listOf(
            event(walletId = null) to "walletId",
            event(assetKind = null) to "kind",
            event(direction = null) to "direction",
            event(direction = "Deposit") to "direction",
            event(status = null) to "status",
            event(status = "Finalized") to "status",
            event(txHash = null) to "txHash",
            event(timestamp = null) to "timestamp",
            event(blockNumber = null) to "blockNumber",
            event(blockNumber = "\"8452119\"") to "blockNumber",
            event(blockNumber = "1.5") to "blockNumber",
            event(value = null) to "value",
            // locator 형식이 깨지면 등록과 같은 검사에서 걸러 키를 만들지 않는다.
            event(locator = "0xnot-an-address") to "data.blockchainEvent",
            event(
                network = "SolanaDevnet",
                assetKind = "SplTransfer",
                locatorField = "mint",
                locator = "${MINT}2",
                wallet = """{"id":"$WALLET_ID","network":"SolanaDevnet"}""",
            ) to "data.blockchainEvent",
        ).forEach { (body, field) ->
            assertThatThrownBy { parser.parse(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(WebhookPayloadException::class.java)
                .hasMessageContaining(field)
        }
    }

    @Test
    fun `금액·블록 번호의 값 범위는 도메인 불변식대로 거절하고 알림 메타는 전송 사건과 같은 규칙을 쓴다`() {
        listOf(
            event(value = "0123"),
            event(value = "1.5"),
            event(blockNumber = "-1"),
        ).forEach { body ->
            assertThatThrownBy { parser.parse(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(WebhookPayloadException::class.java)
                .hasMessageContaining("형식 오류")
        }
        listOf(
            event(deliveryAttempt = null) to "deliveryAttempt",
            event(deliveryAttempt = "0") to "deliveryAttempt",
            event(id = """"wh-1"""") to "id",
            event(date = """"2026-09-16T09:00:00+09:00"""") to "date",
        ).forEach { (body, field) ->
            assertThatThrownBy { parser.parse(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(WebhookPayloadException::class.java)
                .hasMessageContaining(field)
        }
    }

    @Test
    fun `JSON이 아니거나 객체가 아닌 원문은 해석하지 않는다`() {
        listOf("not json", "[]", "\"x\"", "").forEach { body ->
            assertThatThrownBy { parser.parse(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(WebhookPayloadException::class.java)
        }
    }

    private fun event(
        kind: String? = "wallet.blockchainevent.detected",
        id: String? = """"$NOTIFICATION_ID"""",
        date: String? = """"$DATE"""",
        deliveryAttempt: String? = "1",
        network: String = "EthereumSepolia",
        walletId: String? = WALLET_ID,
        assetKind: String? = "Erc20Transfer",
        locatorField: String = "contract",
        locator: String? = CONTRACT,
        direction: String? = "In",
        status: String? = "Confirmed",
        value: String? = "1500000",
        symbol: String? = "USDC",
        decimals: String? = "6",
        from: String? = SENDER,
        to: String? = WALLET_ADDRESS,
        txHash: String? = TX_HASH,
        timestamp: String? = "1758067200",
        blockNumber: String? = "8452119",
        metadata: String? = """{"asset":{"symbol":"USDC","decimals":6}}""",
        wallet: String? = """{"id":"$WALLET_ID","network":"EthereumSepolia","address":"$WALLET_ADDRESS"}""",
    ): String =
        buildString {
            append("""{"data":{"blockchainEvent":{"network":"$network","direction":""")
            append(if (direction == null) "null" else """"$direction"""")
            append(""","index":"3"""")
            metadata?.let { append(""","metadata":$it""") }
            from?.let { append(""","from":"$it"""") }
            to?.let { append(""","to":"$it"""") }
            symbol?.let { append(""","symbol":"$it"""") }
            decimals?.let { append(""","decimals":$it""") }
            walletId?.let { append(""","walletId":"$it"""") }
            assetKind?.let { append(""","kind":"$it"""") }
            locator?.let { append(""","$locatorField":"$it"""") }
            status?.let { append(""","status":"$it"""") }
            value?.let { append(""","value":"$it"""") }
            txHash?.let { append(""","txHash":"$it"""") }
            timestamp?.let { append(""","timestamp":"$it"""") }
            blockNumber?.let { append(""","blockNumber":$it""") }
            append("}")
            wallet?.let { append(""","wallet":$it""") }
            append("}")
            id?.let { append(""","id":$it""") }
            date?.let { append(""","date":$it""") }
            kind?.let { append(""","kind":"$it"""") }
            deliveryAttempt?.let { append(""","deliveryAttempt":$it""") }
            append("}")
        }

    private companion object {
        const val NOTIFICATION_ID = "whe-544ul-uqgad-jkgltj5p6fvd04cj"
        const val DATE = "2026-09-16T00:00:05.000Z"
        const val WALLET_ID = "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx"
        const val WALLET_ADDRESS = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47"
        const val SENDER = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
        const val CONTRACT = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
        const val TX_HASH = "0x2a6f0c9b6bd0a7b9f4e3e0b33d2ff4c7cf9a7a0f9b4d2a8f8c1b3e5d7a9c0b11"
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    }
}
