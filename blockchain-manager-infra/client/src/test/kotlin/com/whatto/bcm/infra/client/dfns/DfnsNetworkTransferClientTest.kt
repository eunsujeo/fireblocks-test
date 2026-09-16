package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkTransferRequest
import com.whatto.bcm.domain.vendor.NetworkTransferStatus
import com.whatto.bcm.domain.vendor.NetworkTransferSubmission
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.infra.client.dfns.fixture.DfnsTestKeyFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.security.KeyPair

/**
 * Dfns 전송 어댑터 계약 — 공식 OpenAPI 1.1018.3의 `/wallets/{id}/transfers`와 공식 Idempotency 문서(계약13).
 * 벤더 실호출 없음(MockRestServiceServer). 응답 JSON은 명세 schema 필드로 만든 표기이며 Baseline 실측 payload가 아니다.
 */
class DfnsNetworkTransferClientTest {
    private val keyPair: KeyPair = DfnsTestKeyFixture.ecKeyPair()
    private val scope = NetworkWalletScope(ORIGIN, "acct_dfns_1", "ETHEREUM_SEPOLIA")
    private val request =
        NetworkTransferRequest(scope, WALLET_ID, "EthereumSepolia:Erc20:$CONTRACT", DESTINATION, "1500000", "wd-ext-1")

    private fun fixture(): Pair<DfnsNetworkTransferClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val properties =
            DfnsProperties(
                baseUrl = BASE,
                authToken = AUTH_TOKEN,
                credentialId = DfnsTestKeyFixture.CREDENTIAL_ID,
                credentialPrivateKeyPem = DfnsTestKeyFixture.pem(keyPair),
                networks = mapOf("ETHEREUM_SEPOLIA" to "EthereumSepolia", "SOLANA_DEVNET" to "SolanaDevnet"),
            )
        val client =
            DfnsNetworkTransferClient(
                builder,
                properties,
                ORIGIN,
                DfnsCredentialSigner(properties.credentialId, properties.credentialPrivateKeyPem),
                NoOpOperationalMetricsPort,
                DfnsRestClientFactory { testBuilder, testProperties -> testBuilder.baseUrl(testProperties.baseUrl).build() },
            )
        return client to server
    }

    private fun expectUserAction(
        server: MockRestServiceServer,
        path: String,
    ) {
        server
            .expect(requestTo("$BASE/auth/action/init"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.userActionServerKind").value("Api"))
            .andExpect(jsonPath("$.userActionHttpMethod").value("POST"))
            .andExpect(jsonPath("$.userActionHttpPath").value(path))
            .andRespond(withSuccess(CHALLENGE, MediaType.APPLICATION_JSON))
        server.expect(requestTo("$BASE/auth/action")).andRespond(withSuccess(USER_ACTION, MediaType.APPLICATION_JSON))
    }

    @Test
    fun `제출은 지갑 경로를 서명하고 등록 자산 키로 만든 kind·locator와 금액·제출 키만 보낸다`() {
        val (client, server) = fixture()
        expectUserAction(server, "/wallets/$WALLET_ID/transfers")
        server
            .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer $AUTH_TOKEN"))
            .andExpect(header("X-DFNS-USERACTION", USER_ACTION_TOKEN))
            .andExpect(
                content().json(
                    """{"kind":"Erc20","contract":"$CONTRACT","to":"$DESTINATION","amount":"1500000","externalId":"wd-ext-1"}""",
                    true,
                ),
            ).andRespond(withSuccess(transferResponse("Broadcasted"), MediaType.APPLICATION_JSON))

        val submission = client.submit(request)

        assertThat(submission).isInstanceOfSatisfying(NetworkTransferSubmission.Accepted::class.java) { accepted ->
            assertThat(accepted.observation.transferId).isEqualTo(TRANSFER_ID)
            assertThat(accepted.observation.network).isEqualTo("ETHEREUM_SEPOLIA")
            assertThat(accepted.observation.vendorWalletId).isEqualTo(WALLET_ID)
            assertThat(accepted.observation.vendorAssetId).isEqualTo("EthereumSepolia:Erc20:$CONTRACT")
            assertThat(accepted.observation.status).isEqualTo(NetworkTransferStatus.BROADCASTED)
            assertThat(accepted.observation.destinationAddress).isEqualTo(DESTINATION)
            assertThat(accepted.observation.amountBaseUnits).isEqualTo("1500000")
            assertThat(accepted.observation.externalId).isEqualTo("wd-ext-1")
            assertThat(accepted.observation.transactionHash).isEqualTo(TX_HASH)
            assertThat(accepted.observation.failureReason).isNull()
        }
        server.verify()
    }

    @Test
    fun `Solana mint 전송은 Spl2022 kind와 mint를 담고 네이티브는 추가 필드가 없다`() {
        val solanaScope = NetworkWalletScope(ORIGIN, "acct_dfns_1", "SOLANA_DEVNET")
        val (client, server) = fixture()
        expectUserAction(server, "/wallets/$WALLET_ID/transfers")
        server
            .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers"))
            .andExpect(content().json("""{"kind":"Spl2022","mint":"$MINT","to":"$MINT","amount":"7","externalId":"wd-ext-2"}""", true))
            .andRespond(
                withSuccess(
                    transferResponse(
                        "Pending",
                        network = "SolanaDevnet",
                        requestBody = """{"kind":"Spl2022","mint":"$MINT","to":"$MINT","amount":"7"}""",
                        externalId = "wd-ext-2",
                    ),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val solana = client.submit(NetworkTransferRequest(solanaScope, WALLET_ID, "SolanaDevnet:Spl2022:$MINT", MINT, "7", "wd-ext-2"))

        assertThat((solana as NetworkTransferSubmission.Accepted).observation.status).isEqualTo(NetworkTransferStatus.PENDING)
        server.verify()
        server.reset()

        expectUserAction(server, "/wallets/$WALLET_ID/transfers")
        server
            .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers"))
            .andExpect(content().json("""{"kind":"Native","to":"$DESTINATION","amount":"10","externalId":"wd-ext-3"}""", true))
            .andRespond(
                withSuccess(
                    transferResponse(
                        "Confirmed",
                        requestBody = """{"kind":"Native","to":"$DESTINATION","amount":"10"}""",
                        externalId = "wd-ext-3",
                    ),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val native = client.submit(NetworkTransferRequest(scope, WALLET_ID, "EthereumSepolia:Native", DESTINATION, "10", "wd-ext-3"))

        assertThat((native as NetworkTransferSubmission.Accepted).observation.vendorAssetId).isEqualTo("EthereumSepolia:Native")
        server.verify()
    }

    @Test
    fun `같은 제출 키의 다른 내용은 벤더 409를 조회 없이 충돌로 돌려주고 자동 재제출하지 않는다`() {
        val (client, server) = fixture()
        val conflictBody =
            """{"error":{"id":"er-1","status":409,"message":"Conflicting transfer with same externalId",""" +
                """"details":{"duplicate":{"id":"xfr-old","externalId":"wd-ext-1"}}}}"""
        expectUserAction(server, "/wallets/$WALLET_ID/transfers")
        server
            .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(conflictBody))

        val submission = client.submit(request)

        assertThat(submission).isInstanceOfSatisfying(NetworkTransferSubmission.Conflict::class.java) { conflict ->
            assertThat(conflict.duplicateTransferId).isEqualTo("xfr-old")
            assertThat(conflict.responseBody()).isEqualTo(conflictBody.toByteArray())
        }
        server.verify()
    }

    @Test
    fun `멱등 표식이 없는 409는 원인을 단정하지 않고 일반 벤더 오류로 전파하며 표식만 있고 ID가 없으면 충돌로 남는다`() {
        val (client, server) = fixture()
        expectUserAction(server, "/wallets/$WALLET_ID/transfers")
        server
            .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers"))
            .andRespond(
                withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"message":"Conflicting transfer with same externalId","details":{"duplicate":{}}}}"""),
            )

        assertThat((client.submit(request) as NetworkTransferSubmission.Conflict).duplicateTransferId).isNull()
        server.verify()

        listOf(
            "not json",
            """{"error":{"message":"wallet is archived"}}""",
            """{"error":{"details":{"duplicate":"xfr-1"}}}""",
            "[]",
        ).forEach { body ->
            val (other, otherServer) = fixture()
            expectUserAction(otherServer, "/wallets/$WALLET_ID/transfers")
            otherServer
                .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(body))

            assertThatThrownBy { other.submit(request) }
                .describedAs(body)
                .isInstanceOfSatisfying(VendorApiException::class.java) {
                    assertThat(it.httpStatus).isEqualTo(409)
                    assertThat(it.responseBody()).isEqualTo(body.toByteArray())
                }
            otherServer.verify()
        }

        val (failing, failingServer) = fixture()
        expectUserAction(failingServer, "/wallets/$WALLET_ID/transfers")
        failingServer
            .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers"))
            .andRespond(
                withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("""{"error":{"message":"forbidden"}}"""),
            )

        assertThatThrownBy { failing.submit(request) }
            .isInstanceOfSatisfying(VendorApiException::class.java) {
                assertThat(it.httpStatus).isEqualTo(403)
                assertThat(it.responseBody()).isEqualTo("""{"error":{"message":"forbidden"}}""".toByteArray())
                assertThat(it.message).doesNotContain("forbidden")
            }
        failingServer.verify()
    }

    @Test
    fun `응답의 지갑·network·자산 지정·상태가 요청과 다르거나 결손이면 정규화하지 않고 실패한다`() {
        listOf(
            transferResponse("Broadcasted", walletId = "wa-other-00000-000000000000000"),
            transferResponse("Broadcasted", network = "Ethereum"),
            transferResponse("Broadcasted", requestBody = """{"kind":"Native","to":"$DESTINATION","amount":"1500000"}"""),
            transferResponse(
                "Broadcasted",
                requestBody = """{"kind":"Erc20","contract":"0xdead","to":"$DESTINATION","amount":"1500000"}""",
            ),
            transferResponse("Broadcasted", requestBody = """{"to":"$DESTINATION","amount":"1500000"}"""),
            transferResponse(
                "Broadcasted",
                requestBody = """{"kind":"Erc20","contract":"$CONTRACT","to":"$OTHER_ADDRESS","amount":"1500000"}""",
            ),
            transferResponse(
                "Broadcasted",
                requestBody = """{"kind":"Erc20","contract":"$CONTRACT","to":"$DESTINATION","amount":"1500001"}""",
            ),
            transferResponse("Broadcasted", requestBody = """{"kind":"Erc20","contract":"$CONTRACT","to":"$DESTINATION"}"""),
            transferResponse("Broadcasted", externalId = "wd-ext-other"),
            transferResponse("Broadcasted", externalId = null),
            transferResponse("Broadcasted", requester = null),
            transferResponse("Broadcasted", requester = """{"tokenId":"to-1"}"""),
            transferResponse("Broadcasted", metadata = null),
            transferResponse("Broadcasted", id = "xfr-short"),
            transferResponse("Broadcasted", dateRequested = "not-a-date"),
            transferResponse("Broadcasted", dateRequested = "2026-09-16"),
            transferResponse("Broadcasted", dateRequested = "2026-09-16T00:00:00+09:00"),
            transferResponse("Broadcasted", dateRequested = " 2026-09-16T00:00:00Z"),
            transferResponse("Completed"),
            transferResponse(status = null),
            transferResponse("Broadcasted", id = null),
            transferResponse("Broadcasted", dateRequested = null),
            """{"id":"$TRANSFER_ID","walletId":"$WALLET_ID","network":"EthereumSepolia","status":"Broadcasted","dateRequested":"2026-09-16T00:00:00.000Z"}""",
            "[]",
            "not json",
        ).forEach { body ->
            val (client, server) = fixture()
            expectUserAction(server, "/wallets/$WALLET_ID/transfers")
            server.expect(requestTo("$BASE/wallets/$WALLET_ID/transfers")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

            assertThatThrownBy { client.submit(request) }
                .describedAs(body)
                .isInstanceOfSatisfying(VendorApiException::class.java) { assertThat(it.responseBody()).isEqualTo(body.toByteArray()) }
            server.verify()
        }
    }

    @Test
    fun `조회는 서명 없이 Bearer만 보내고 404는 미관찰이며 ID 불일치는 전파한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers/$TRANSFER_ID"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer $AUTH_TOKEN"))
            .andExpect(headerDoesNotExist("X-DFNS-USERACTION"))
            .andRespond(withSuccess(transferResponse("Failed", reason = "insufficient funds"), MediaType.APPLICATION_JSON))

        val observed = client.transfer(scope, WALLET_ID, TRANSFER_ID)

        assertThat(observed?.status).isEqualTo(NetworkTransferStatus.FAILED)
        assertThat(observed?.failureReason).isEqualTo("insufficient funds")
        server.verify()
        server.reset()

        server
            .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers/$TRANSFER_ID"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""{"error":{"message":"not found"}}"""),
            )
        assertThat(client.transfer(scope, WALLET_ID, TRANSFER_ID)).isNull()
        server.verify()
        server.reset()

        server
            .expect(requestTo("$BASE/wallets/$WALLET_ID/transfers/$TRANSFER_ID"))
            .andRespond(withSuccess(transferResponse("Broadcasted", id = "xfr-other-0000-000000000000000"), MediaType.APPLICATION_JSON))
        assertThatThrownBy { client.transfer(scope, WALLET_ID, TRANSFER_ID) }.isInstanceOf(VendorApiException::class.java)
        server.verify()
    }

    @Test
    fun `scope에 속하지 않는 자산 키·모델 밖 kind·잘못된 ID·다른 원천은 어떤 HTTP 호출도 하지 않고 거절한다`() {
        val (client, server) = fixture()
        val foreign = NetworkWalletScope(ProviderOrigin("other", "dfns", "dfns", "p", "o", "TESTNET"), "acct_dfns_1", "ETHEREUM_SEPOLIA")

        assertThatThrownBy {
            client.submit(
                request.copy(vendorAssetId = "SolanaDevnet:Native"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            client.submit(request.copy(vendorAssetId = "EthereumSepolia:Iou:usd"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            client.submit(request.copy(vendorAssetId = "EthereumSepolia:Erc20:0xdead"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { client.submit(request.copy(scope = foreign)) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { client.transfer(foreign, WALLET_ID, TRANSFER_ID) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { client.transfer(scope, WALLET_ID, "xfr 1/../x") }.isInstanceOf(IllegalArgumentException::class.java)
        server.verify()
    }

    private fun transferResponse(
        status: String?,
        id: String? = TRANSFER_ID,
        walletId: String = WALLET_ID,
        network: String = "EthereumSepolia",
        requestBody: String = """{"kind":"Erc20","contract":"$CONTRACT","to":"$DESTINATION","amount":"1500000"}""",
        dateRequested: String? = "2026-09-16T00:00:00.000Z",
        reason: String? = null,
        externalId: String? = "wd-ext-1",
        requester: String? = """{"userId":"us-1"}""",
        metadata: String? = """{"asset":{"symbol":"USDC","decimals":6}}""",
    ): String =
        buildString {
            append("""{"walletId":"$walletId","network":"$network",""")
            append(""""requestBody":$requestBody,"txHash":"$TX_HASH","fee":"21000"""")
            requester?.let { append(""","requester":$it""") }
            metadata?.let { append(""","metadata":$it""") }
            externalId?.let { append(""","externalId":"$it"""") }
            id?.let { append(""","id":"$it"""") }
            status?.let { append(""","status":"$it"""") }
            dateRequested?.let { append(""","dateRequested":"$it"""") }
            reason?.let { append(""","reason":"$it"""") }
            append("}")
        }

    private companion object {
        const val BASE = "https://baseline.dfns.internal.test"
        const val WALLET_ID = "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx"
        const val TRANSFER_ID = "xfr-20g4k-nsdpo-mg6arrifgvid4orn"
        const val CONTRACT = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
        const val DESTINATION = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47"
        const val OTHER_ADDRESS = "0x1111111111111111111111111111111111111111"
        const val MINT = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
        const val TX_HASH = "0x5f2b1c0e2b0a4d3c8e9f0a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5"

        /** 시험용 자격값 — 실행마다 생성해 소스에 고정 토큰을 두지 않는다. */
        private fun randomToken(): String =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(java.security.SecureRandom().generateSeed(24))

        val AUTH_TOKEN: String = randomToken()
        val USER_ACTION_TOKEN: String = randomToken()
        val USER_ACTION = """{"userAction":"$USER_ACTION_TOKEN"}"""
        val ORIGIN = ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET")
        val CHALLENGE =
            """{"challenge":"Y2gtNzloaHQtbXJlb2stOGFwOHFtMmVpZWZ0amxhZw","challengeIdentifier":"eyJ0e.fQNA",
               "allowCredentials":{"key":[{"type":"public-key","id":"${DfnsTestKeyFixture.CREDENTIAL_ID}"}]}}"""
    }
}
