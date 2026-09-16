package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.vendor.NetworkTransferEventKind
import com.whatto.bcm.domain.vendor.NetworkTransferStatus
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * 공식 `webhooks`의 `wallet.transfer.*`와 `WebhookEnvelopeBase`·`TransferRequest`를 근거로 한 전송 사건 해석(계약13).
 * 전송이 아닌 종류는 관심 밖(null)이고, 전송 사건인데 형식이 다르면 조용히 버리지 않고 거절한다.
 */
class DfnsNetworkTransferEventParserTest {
    private val properties =
        DfnsProperties(networks = mapOf("ETHEREUM_SEPOLIA" to "EthereumSepolia", "SOLANA_DEVNET" to "SolanaDevnet"))
    private val parser = DfnsNetworkTransferEventParser(ObjectMapper(), properties)

    @Test
    fun `전송 사건을 알림 메타와 조회와 같은 관찰값으로 해석한다`() {
        val event = parser.parse(event(deliveryAttempt = "2", retryOf = """"$OTHER_NOTIFICATION_ID"""").toByteArray())

        checkNotNull(event)
        assertThat(event.delivery.notificationId).isEqualTo(NOTIFICATION_ID)
        assertThat(event.kind).isEqualTo(NetworkTransferEventKind.CONFIRMED)
        assertThat(event.delivery.occurredAt).isEqualTo(OCCURRED_AT)
        assertThat(event.delivery.deliveryAttempt).isEqualTo(2)
        assertThat(event.delivery.retryOfNotificationId).isEqualTo(OTHER_NOTIFICATION_ID)
        val observation = event.observation
        assertThat(observation.transferId).isEqualTo(TRANSFER_ID)
        assertThat(observation.network).isEqualTo("ETHEREUM_SEPOLIA")
        assertThat(observation.vendorWalletId).isEqualTo(WALLET_ID)
        assertThat(observation.vendorAssetId).isEqualTo("EthereumSepolia:Erc20:$CONTRACT")
        assertThat(observation.destinationAddress).isEqualTo(DESTINATION)
        assertThat(observation.amountBaseUnits).isEqualTo("1500000")
        assertThat(observation.status).isEqualTo(NetworkTransferStatus.CONFIRMED)
        assertThat(observation.externalId).isEqualTo("wd-ext-1")
        assertThat(observation.transactionHash).isEqualTo(TX_HASH)
        assertThat(observation.requestedAt).isEqualTo("2026-09-16T00:00:00.000Z")
        assertThat(observation.failureReason).isNull()
    }

    @Test
    fun `다섯 종류를 모두 받고 업무 상태는 종류가 아니라 전송 상태에서 읽는다`() {
        NetworkTransferEventKind.entries.forEach { kind ->
            val parsed = parser.parse(event(kind = kind.vendorKind).toByteArray())
            assertThat(parsed?.kind).describedAs(kind.vendorKind).isEqualTo(kind)
        }
        // 종류가 confirmed여도 관찰 상태가 Broadcasted면 상태는 BROADCASTED다 — 종류를 상태로 번역하지 않는다.
        val mixed = parser.parse(event(status = "Broadcasted").toByteArray())
        assertThat(mixed?.kind).isEqualTo(NetworkTransferEventKind.CONFIRMED)
        assertThat(mixed?.observation?.status).isEqualTo(NetworkTransferStatus.BROADCASTED)
    }

    @Test
    fun `전송이 아닌 종류는 이 포트의 관심 밖이다`() {
        listOf(
            "wallet.transaction.confirmed",
            "wallet.blockchainevent.detected",
            "wallet.blockchain_event.transfer.included",
            "wallet.created",
            "wallet.signature.signed",
        ).forEach { kind ->
            assertThat(parser.parse(event(kind = kind).toByteArray())).describedAs(kind).isNull()
        }
    }

    @Test
    fun `Solana mint 사건도 등록과 같은 키 규칙으로 해석하고 형식이 깨진 mint는 거절한다`() {
        val parsed =
            parser.parse(
                event(
                    network = "SolanaDevnet",
                    requestBody = """{"kind":"Spl2022","mint":"$MINT","to":"$OWNER","amount":"250000"}""",
                ).toByteArray(),
            )

        assertThat(parsed?.observation?.vendorAssetId).isEqualTo("SolanaDevnet:Spl2022:$MINT")
        assertThat(parsed?.observation?.network).isEqualTo("SOLANA_DEVNET")
        assertThatThrownBy {
            parser.parse(
                event(
                    network = "SolanaDevnet",
                    requestBody = """{"kind":"Spl","mint":"${MINT}2","to":"$OWNER","amount":"250000"}""",
                ).toByteArray(),
            )
        }.isInstanceOf(WebhookPayloadException::class.java).hasMessageContaining("requestBody")
    }

    @Test
    fun `전송 종류인데 전송 정보가 없거나 설정 밖 네트워크면 조용히 버리지 않고 거절한다`() {
        listOf(
            """{"id":"$NOTIFICATION_ID","date":"$OCCURRED_AT","kind":"wallet.transfer.confirmed","data":{}}""" to "transferRequest",
            """{"id":"$NOTIFICATION_ID","date":"$OCCURRED_AT","kind":"wallet.transfer.confirmed","data":{"transferRequest":"x"}}""" to
                "transferRequest",
            """{"id":"$NOTIFICATION_ID","date":"$OCCURRED_AT","kind":"wallet.transfer.confirmed"}""" to "transferRequest",
            event(network = "EthereumMainnet") to "network",
        ).forEach { (body, field) ->
            assertThatThrownBy { parser.parse(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(WebhookPayloadException::class.java)
                .hasMessageContaining(field)
        }
    }

    @Test
    fun `알림 메타의 결손·형식 오류는 사건으로 받지 않는다`() {
        listOf(
            event(id = null) to "id",
            event(id = """""""") to "id",
            event(date = null) to "date",
            event(date = """"2026-09-16"""") to "date",
            event(date = """"2026-09-16T09:00:00+09:00"""") to "date",
            event(kind = null) to "kind",
            event(deliveryAttempt = "0") to "deliveryAttempt",
            event(deliveryAttempt = """"1"""") to "deliveryAttempt",
            event(deliveryAttempt = "1.5") to "deliveryAttempt",
            // 명세가 필수로 정의한 전달 시도 — 결손을 기본값 1로 지어내지 않는다.
            event(deliveryAttempt = null) to "deliveryAttempt",
            // 수신 envelope의 알림 ID는 `whe-…` 형식이다(조회 모델 WebhookEvent에는 없는 제약).
            event(id = """"wh-1"""") to "id",
            event(id = """"whe-544ul-uqgad-short"""") to "id",
            // 선택 필드지만 있으면 명세 형식이어야 한다 — 빈 값·형식 오류를 결손으로 축소하지 않는다.
            event(retryOf = "7") to "retryOf",
            event(retryOf = """""""") to "retryOf",
            event(retryOf = """"whe-bad"""") to "retryOf",
        ).forEach { (body, field) ->
            assertThatThrownBy { parser.parse(body.toByteArray()) }
                .describedAs(body)
                .isInstanceOf(WebhookPayloadException::class.java)
                .hasMessageContaining(field)
        }
        assertThat(parser.parse(event(retryOf = null).toByteArray())?.delivery?.retryOfNotificationId).isNull()
    }

    @Test
    fun `전송 정보의 명세 필수 필드는 조회와 같은 검사를 거친다`() {
        listOf(
            event(transferId = """"xfr-short"""") to "id",
            event(transferId = null) to "id",
            event(walletId = null) to "walletId",
            event(requester = null) to "requester",
            event(requester = """{"id":"us-1"}""") to "userId",
            event(metadata = null) to "metadata",
            event(status = null) to "status",
            event(status = "COMPLETED") to "status",
            event(dateRequested = null) to "dateRequested",
            event(dateRequested = "2026-09-16T00:00:00+01:00") to "dateRequested",
            event(requestBody = """{"kind":"Erc20","contract":"$CONTRACT","amount":"1500000"}""") to "to",
            event(requestBody = """{"kind":"Erc20","contract":"$CONTRACT","to":"$DESTINATION"}""") to "amount",
            event(requestBody = """{"kind":"Erc20","to":"$DESTINATION","amount":"1500000"}""") to "contract",
            event(requestBody = """{"kind":"Nft","to":"$DESTINATION","amount":"1"}""") to "requestBody",
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
        kind: String? = "wallet.transfer.confirmed",
        id: String? = """"$NOTIFICATION_ID"""",
        date: String? = """"$OCCURRED_AT"""",
        deliveryAttempt: String? = "1",
        retryOf: String? = null,
        network: String = "EthereumSepolia",
        walletId: String? = WALLET_ID,
        transferId: String? = """"$TRANSFER_ID"""",
        requestBody: String = """{"kind":"Erc20","contract":"$CONTRACT","to":"$DESTINATION","amount":"1500000"}""",
        status: String? = "Confirmed",
        dateRequested: String? = "2026-09-16T00:00:00.000Z",
        requester: String? = """{"userId":"us-1"}""",
        metadata: String? = """{"asset":{"symbol":"USDC","decimals":6}}""",
    ): String =
        buildString {
            append("""{"data":{"transferRequest":{"network":"$network","requestBody":$requestBody""")
            append(""","txHash":"$TX_HASH","externalId":"wd-ext-1"""")
            walletId?.let { append(""","walletId":"$it"""") }
            transferId?.let { append(""","id":$it""") }
            requester?.let { append(""","requester":$it""") }
            metadata?.let { append(""","metadata":$it""") }
            status?.let { append(""","status":"$it"""") }
            dateRequested?.let { append(""","dateRequested":"$it"""") }
            append("}}")
            id?.let { append(""","id":$it""") }
            date?.let { append(""","date":$it""") }
            kind?.let { append(""","kind":"$it"""") }
            deliveryAttempt?.let { append(""","deliveryAttempt":$it""") }
            retryOf?.let { append(""","retryOf":$it""") }
            append("}")
        }

    private companion object {
        const val NOTIFICATION_ID = "whe-544ul-uqgad-jkgltj5p6fvd04cj"
        const val OTHER_NOTIFICATION_ID = "whe-544ul-uqgad-aaaaaaaaaaaaaaaa"
        const val OCCURRED_AT = "2026-09-16T00:00:05.000Z"
        const val WALLET_ID = "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx"
        const val TRANSFER_ID = "xfr-20g4k-nsdpo-mg6arrifgvid4orn"
        const val CONTRACT = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
        const val DESTINATION = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47"
        const val TX_HASH = "0x2a6f0c9b6bd0a7b9f4e3e0b33d2ff4c7cf9a7a0f9b4d2a8f8c1b3e5d7a9c0b11"
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val OWNER = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    }
}
