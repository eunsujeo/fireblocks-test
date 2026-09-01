package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.VendorContractCallRequest
import com.whatto.bcm.domain.vendor.VendorFeeLevel
import com.whatto.bcm.domain.vendor.VendorNetworkFee
import com.whatto.bcm.domain.vendor.VendorNetworkFeeEstimate
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionLifecycleStage
import com.whatto.bcm.domain.vendor.VendorTransactionOrder
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.infra.client.fireblocks.fixture.TestRsaKeyFixture
import com.whatto.bcm.infra.client.fireblocks.fixture.VendorTransactionFixture.request
import com.whatto.bcm.infra.client.fireblocks.fixture.VendorTransactionFixture.responseJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import java.time.Clock

class FireblocksTransactionClientTest {
    private val privateKeyPem: String = TestRsaKeyFixture.toPkcs8Pem(TestRsaKeyFixture.generateKeyPair())

    @Test
    fun `벤더 connect와 read timeout은 양수여야 한다`() {
        assertThatThrownBy { FireblocksProperties(connectTimeoutMillis = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FireblocksProperties(readTimeoutMillis = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            FireblocksProperties(maxAttempts = 1, connectTimeoutMillis = 1, readTimeoutMillis = 300_000)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `네트워크 수수료 견적은 assetId로 조회하고 세 fee level의 선택 필드를 보존한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/estimate_network_fee?assetId=USDC_ERC20"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-API-Key", "api-key-1"))
            .andExpect(headerDoesNotExist("Idempotency-Key"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "low":{"gasPrice":"1.1","baseFee":"1.0","priorityFee":"0.1"},
                      "medium":{"gasPrice":"2.2","networkFee":"0.0000462"},
                      "high":{"feePerByte":"3","gasPrice":"4.4","priorityFee":"0.4"}
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val estimate = client.estimateNetworkFee("USDC_ERC20")

        assertThat(estimate)
            .isEqualTo(
                VendorNetworkFeeEstimate(
                    low =
                        VendorNetworkFee(
                            gasPrice = "1.1".toBigDecimal(),
                            baseFee = "1.0".toBigDecimal(),
                            priorityFee = "0.1".toBigDecimal(),
                        ),
                    medium = VendorNetworkFee(gasPrice = "2.2".toBigDecimal(), networkFee = "0.0000462".toBigDecimal()),
                    high =
                        VendorNetworkFee(
                            feePerByte = "3".toBigDecimal(),
                            gasPrice = "4.4".toBigDecimal(),
                            priorityFee = "0.4".toBigDecimal(),
                        ),
                ),
            )
        server.verify()
    }

    @Test
    fun `거래 제출 — 외부 주소를 ONE_TIME_ADDRESS로 변환하고 externalTxId와 gasless를 싣는다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-Key", "api-key-1"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, org.hamcrest.Matchers.startsWith("Bearer ")))
            .andExpect(headerDoesNotExist("Idempotency-Key"))
            .andExpect(jsonPath("$.operation").value("TRANSFER"))
            .andExpect(jsonPath("$.externalTxId").value("wd-260713-0042"))
            .andExpect(jsonPath("$.assetId").value("asset-uuid"))
            .andExpect(jsonPath("$.source.type").value("VAULT_ACCOUNT"))
            .andExpect(jsonPath("$.source.id").value("71"))
            .andExpect(jsonPath("$.destination.type").value("ONE_TIME_ADDRESS"))
            .andExpect(jsonPath("$.destination.oneTimeAddress.address").value("0x9fE2"))
            .andExpect(jsonPath("$.amount").value("1.5"))
            .andExpect(jsonPath("$.note").value("approved withdrawal"))
            .andExpect(jsonPath("$.travelRuleMessage.encrypted").value("cipher-text"))
            .andExpect(jsonPath("$.useGasless").value(true))
            .andExpect(jsonPath("$.replaceTxByHash").doesNotExist())
            .andRespond(withSuccess("""{"id":"tx-91c","status":"SUBMITTED"}""", MediaType.APPLICATION_JSON))

        assertThat(client.submitTransaction(request()))
            .isEqualTo(VendorTransactionSubmission.Accepted("tx-91c"))
        server.verify()
    }

    @Test
    fun `RBF 거래 제출 — 교체할 온체인 해시를 replaceTxByHash로 변환한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andExpect(jsonPath("$.replaceTxByHash").value("0xstuck"))
            .andExpect(jsonPath("$.feeLevel").value("HIGH"))
            .andExpect(jsonPath("$.useGasless").value(true))
            .andRespond(withSuccess("""{"id":"tx-replacement"}""", MediaType.APPLICATION_JSON))

        val result =
            client.submitTransaction(
                request().copy(replaceTransactionHash = "0xstuck", feeLevel = VendorFeeLevel.HIGH),
            )

        assertThat(result).isEqualTo(VendorTransactionSubmission.Accepted("tx-replacement"))
        server.verify()
    }

    @Test
    fun `approve는 가스 자산 CONTRACT_CALL과 calldata로 제출한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andExpect(jsonPath("$.operation").value("CONTRACT_CALL"))
            .andExpect(jsonPath("$.externalTxId").value("swa-1"))
            .andExpect(jsonPath("$.assetId").value("ETH"))
            .andExpect(jsonPath("$.source.id").value("71"))
            .andExpect(jsonPath("$.destination.oneTimeAddress.address").value("0xtoken"))
            .andExpect(jsonPath("$.amount").value("0"))
            .andExpect(jsonPath("$.useGasless").value(true))
            .andExpect(jsonPath("$.extraParameters.contractCallData").value("0x095ea7b3data"))
            .andRespond(withSuccess("""{"id":"tx-approve"}""", MediaType.APPLICATION_JSON))

        val result =
            client.submitContractCall(
                VendorContractCallRequest("swa-1", "ETHEREUM", "71", "0xtoken", "0x095ea7b3data", true),
            )

        assertThat(result).isEqualTo(VendorTransactionSubmission.Accepted("tx-approve"))
        server.verify()
    }

    @Test
    fun `approve 회수 조회는 원문 calldata와 계약 주소를 보존한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions/external_tx_id/swa-1"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "id":"tx-approve","externalTxId":"swa-1",
                      "source":{"type":"VAULT_ACCOUNT","id":"71"},
                      "destination":{"type":"ONE_TIME_ADDRESS"},
                      "destinationAddress":"0xtoken",
                      "extraParameters":{"contractCallData":"0x095ea7b3data"}
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val recovered = client.contractCallByExternalTransactionId("swa-1")

        assertThat(recovered?.transactionId).isEqualTo("tx-approve")
        assertThat(recovered?.sourceVaultId).isEqualTo("71")
        assertThat(recovered?.contractAddress).isEqualTo("0xtoken")
        assertThat(recovered?.callData).isEqualTo("0x095ea7b3data")
        server.verify()
    }

    @Test
    fun `거래 제출 — 우리 계정 목적지는 벤더 vault 계정으로 변환한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andExpect(jsonPath("$.destination.type").value("VAULT_ACCOUNT"))
            .andExpect(jsonPath("$.destination.id").value("88"))
            .andExpect(jsonPath("$.destination.oneTimeAddress").doesNotExist())
            .andRespond(withSuccess("""{"id":"tx-internal"}""", MediaType.APPLICATION_JSON))

        val result = client.submitTransaction(request(VendorTransactionDestination.Account("88")))

        assertThat(result).isEqualTo(VendorTransactionSubmission.Accepted("tx-internal"))
        server.verify()
    }

    @Test
    fun `거래 제출 — 사전 등록 지갑은 EXTERNAL_WALLET로 변환한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andExpect(jsonPath("$.destination.type").value("EXTERNAL_WALLET"))
            .andExpect(jsonPath("$.destination.id").value("wallet-3"))
            .andRespond(withSuccess("""{"id":"tx-whitelisted"}""", MediaType.APPLICATION_JSON))

        val result = client.submitTransaction(request(VendorTransactionDestination.Whitelisted("wallet-3")))

        assertThat(result).isEqualTo(VendorTransactionSubmission.Accepted("tx-whitelisted"))
        server.verify()
    }

    @Test
    fun `거래 제출 — 400은 오류 코드와 무관하게 조회로 확인할 결과로 변환한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"message":"external tx id already exists","code":1438}"""),
            )

        assertThat(client.submitTransaction(request()))
            .isInstanceOf(VendorTransactionSubmission.BadRequestNeedsLookup::class.java)
        server.verify()
    }

    @Test
    fun `거래 제출 — 검증 실패로 보이는 400도 확정 거절로 굳히지 않는다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"message":"invalid amount","code":1432}"""),
            )

        assertThat(client.submitTransaction(request()))
            .isInstanceOf(VendorTransactionSubmission.BadRequestNeedsLookup::class.java)
        server.verify()
    }

    @Test
    fun `거래 제출 — 409과 422만 확정 거절로 변환하고 cause를 보존한다`() {
        listOf(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY).forEach { status ->
            val (client, server) = fixture()
            server
                .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
                .andRespond(withStatus(status))

            assertThatThrownBy { client.submitTransaction(request()) }
                .isInstanceOf(RelayRejectedException::class.java)
                .hasCauseInstanceOf(VendorApiException::class.java)
            server.verify()
        }
    }

    @Test
    fun `거래 제출 — 인증 권한 경로 4xx는 검증 거절이 아니라 제출 여부 불명으로 보존한다`() {
        listOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND).forEach { status ->
            val (client, server) = fixture()
            server
                .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
                .andRespond(withStatus(status))

            assertThatThrownBy { client.submitTransaction(request()) }
                .isInstanceOf(VendorApiException::class.java)
                .satisfies({ exception ->
                    exception as VendorApiException
                    assertThat(exception.httpStatus).isEqualTo(status.value())
                })
            server.verify()
        }
    }

    @Test
    fun `거래 제출 — 5xx는 제출 여부를 모르는 VendorApiException으로 보존한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andRespond(withServerError())

        assertThatThrownBy { client.submitTransaction(request()) }
            .isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                exception as VendorApiException
                assertThat(exception.operation).isEqualTo("submitTransaction")
                assertThat(exception.httpStatus).isEqualTo(500)
            })
        server.verify()
    }

    @Test
    fun `거래 제출 — 재시도 소진 429는 확정 거절이 아니라 제출 여부 불명이다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andRespond(withTooManyRequests().header(HttpHeaders.RETRY_AFTER, "0"))

        assertThatThrownBy { client.submitTransaction(request()) }
            .isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                exception as VendorApiException
                assertThat(exception.httpStatus).isEqualTo(429)
            })
        server.verify()
    }

    @Test
    fun `거래 제출 — 타임아웃은 제출 여부를 모르는 VendorApiException으로 보존한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions"))
            .andRespond(withException(SocketTimeoutException("read timed out")))

        assertThatThrownBy { client.submitTransaction(request()) }
            .isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                exception as VendorApiException
                assertThat(exception.operation).isEqualTo("submitTransaction")
                assertThat(exception.httpStatus).isNull()
            })
        server.verify()
    }

    @Test
    fun `externalTxId 조회 — 거래 필드를 문자열 금액과 epoch 밀리초 그대로 매핑한다`() {
        val (client, server) = fixture()
        server
            .expect(
                requestTo(
                    "https://sandbox-api.fireblocks.test/v1/transactions/external_tx_id/wd-260713-0042",
                ),
            ).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON))

        val transaction = client.transactionByExternalTransactionId("wd-260713-0042")

        assertThat(transaction?.transactionId).isEqualTo("tx-91c")
        assertThat(transaction?.externalTransactionId).isEqualTo("wd-260713-0042")
        assertThat(transaction?.vendorAssetId).isEqualTo("asset-uuid")
        assertThat(transaction?.amount).isEqualTo("1.50")
        assertThat(transaction?.source?.type).isEqualTo("VAULT_ACCOUNT")
        assertThat(transaction?.destinationAddress).isEqualTo("0x9fE2")
        assertThat(transaction?.confirmationCount).isEqualTo(12)
        assertThat(transaction?.lifecycleStage).isEqualTo(VendorTransactionLifecycleStage.TERMINAL)
        assertThat(transaction?.createdAtEpochMillis).isEqualTo(1786068306789)
        server.verify()
    }

    @Test
    fun `거래 단건 조회 — CONFIRMING을 막힘 판정용 lifecycle 단계로 변환한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions/tx-91c"))
            .andRespond(
                withSuccess(
                    responseJson
                        .replace("\"status\":\"COMPLETED\"", "\"status\":\"CONFIRMING\"")
                        .replace("\"numOfConfirmations\":12", "\"numOfConfirmations\":0"),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val transaction = client.transaction("tx-91c")

        assertThat(transaction?.lifecycleStage).isEqualTo(VendorTransactionLifecycleStage.CONFIRMING)
        assertThat(transaction?.transactionHash).isEqualTo("0xabc")
        assertThat(transaction?.confirmationCount).isZero()
        server.verify()
    }

    @Test
    fun `거래 단건 조회는 batch 대사용 network records를 보존한다`() {
        val (client, server) = fixture()
        val withNetworkRecords =
            responseJson.dropLast(1) +
                """
                ,
                "networkRecords":[{
                  "type":"CONTRACT_CALL",
                  "source":{"type":"VAULT_ACCOUNT","id":"82"},
                  "destination":{"type":"ONE_TIME_ADDRESS"},
                  "destinationAddress":"0xOmnibus",
                  "txHash":"0xabc",
                  "assetId":"asset-uuid",
                  "netAmount":"2.50",
                  "isDropped":false
                }]}
                """.trimIndent()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions/tx-91c"))
            .andRespond(withSuccess(withNetworkRecords, MediaType.APPLICATION_JSON))

        val record = client.transaction("tx-91c")?.networkRecords?.single()

        assertThat(record?.source?.id).isEqualTo("82")
        assertThat(record?.destinationAddress).isEqualTo("0xOmnibus")
        assertThat(record?.transactionHash).isEqualTo("0xabc")
        assertThat(record?.netAmount).isEqualTo("2.50")
        assertThat(record?.dropped).isFalse()
        server.verify()
    }

    @Test
    fun `거래 조회 — 존재하지 않는 txId와 externalTxId의 404는 null이다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions/tx-none"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/transactions/external_tx_id/wd-none"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertThat(client.transaction("tx-none")).isNull()
        assertThat(client.transactionByExternalTransactionId("wd-none")).isNull()
        server.verify()
    }

    @Test
    fun `거래 목록 첫 페이지 — createdAt 조건과 vault를 보내고 next-page 헤더를 보존한다`() {
        val (client, server) = fixture()
        server
            .expect(
                requestTo(
                    "https://sandbox-api.fireblocks.test/v1/transactions" +
                        "?after=1786068000000&before=1786154400000&status=COMPLETED" +
                        "&sort=ASC&limit=2&sourceType=VAULT_ACCOUNT&sourceId=71",
                ),
            ).andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess("[$responseJson]", MediaType.APPLICATION_JSON)
                    .header("next-page", "cursor-2"),
            )

        val page =
            client.transactions(
                VendorTransactionPageRequest(
                    sourceVaultId = "71",
                    afterEpochMillis = 1786068000000,
                    beforeEpochMillis = 1786154400000,
                    vendorStatus = "COMPLETED",
                    order = VendorTransactionOrder.ASC,
                    limit = 2,
                ),
            )

        assertThat(page.data).hasSize(1)
        assertThat(page.next).isEqualTo("cursor-2")
        server.verify()
    }

    @Test
    fun `거래 목록 커서 페이지 — next에도 원래 기간 정렬과 vault 격리 조건을 보낸다`() {
        val (client, server) = fixture()
        server
            .expect(
                requestTo(
                    "https://sandbox-api.fireblocks.test/v1/transactions" +
                        "?next=cursor-2&after=1&before=2&status=FAILED&sort=DESC&limit=500" +
                        "&sourceType=VAULT_ACCOUNT&sourceId=71",
                ),
            ).andRespond(
                withSuccess("[$responseJson]", MediaType.APPLICATION_JSON)
                    .header("next-page", "cursor-3"),
            )

        val page =
            client.transactions(
                VendorTransactionPageRequest(
                    sourceVaultId = "71",
                    afterEpochMillis = 1,
                    beforeEpochMillis = 2,
                    vendorStatus = "FAILED",
                    order = VendorTransactionOrder.DESC,
                    limit = 500,
                    cursor = "cursor-2",
                ),
            )

        assertThat(page.next).isEqualTo("cursor-3")
        server.verify()
    }

    @Test
    fun `거래 목록 응답에 다른 vault 거래가 섞이면 전체 응답을 거절한다`() {
        val (client, server) = fixture()
        server
            .expect(
                requestTo(
                    "https://sandbox-api.fireblocks.test/v1/transactions" +
                        "?after=1786068000000&sort=DESC&limit=200" +
                        "&sourceType=VAULT_ACCOUNT&sourceId=71",
                ),
            ).andRespond(
                withSuccess(
                    "[${responseJson.replace("\"id\":\"71\"", "\"id\":\"72\"")}]",
                    MediaType.APPLICATION_JSON,
                ).header("next-page", "cursor-2"),
            )

        assertThatThrownBy {
            client.transactions(
                VendorTransactionPageRequest(
                    sourceVaultId = "71",
                    afterEpochMillis = 1786068000000,
                ),
            )
        }.isInstanceOf(VendorApiException::class.java)
        server.verify()
    }

    @Test
    fun `거래 대사 목록은 source와 sort를 지정하지 않고 워크스페이스 전체를 조회한다`() {
        val (client, server) = fixture()
        val otherSource = responseJson.replace("\"id\":\"71\"", "\"id\":\"72\"")
        server
            .expect(
                requestTo(
                    "https://sandbox-api.fireblocks.test/v1/transactions" +
                        "?after=1786068000000&before=1786154400000&limit=500",
                ),
            ).andRespond(
                withSuccess("[$responseJson,$otherSource]", MediaType.APPLICATION_JSON)
                    .header("next-page", "cursor-2"),
            )

        val page =
            client.transactions(
                VendorTransactionPageRequest(
                    sourceVaultId = null,
                    afterEpochMillis = 1786068000000,
                    beforeEpochMillis = 1786154400000,
                    order = null,
                    limit = 500,
                ),
            )

        assertThat(page.data.map { it.source.id }).containsExactly("71", "72")
        assertThat(page.next).isEqualTo("cursor-2")
        server.verify()
    }

    private fun fixture(): Pair<FireblocksClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val properties =
            FireblocksProperties(
                baseUrl = "https://sandbox-api.fireblocks.test",
                apiKey = "api-key-1",
                privateKeyPem = privateKeyPem,
                maxAttempts = 1,
                retryBackoffMillis = 1,
                maxBackoffMillis = 1,
                contractCallGasAssetIds = mapOf("ETHEREUM" to "ETH"),
            )
        val client =
            FireblocksClient(
                restClientBuilder = builder,
                restClientFactory =
                    FireblocksRestClientFactory { testBuilder, testProperties ->
                        testBuilder.baseUrl(testProperties.baseUrl).build()
                    },
                properties = properties,
                signer = FireblocksJwtSigner("api-key-1", privateKeyPem, Clock.systemUTC()),
                metrics = NoOpTransactionClientOperationalMetrics,
            )
        return client to server
    }
}

private object NoOpTransactionClientOperationalMetrics : com.whatto.bcm.domain.monitoring.OperationalMetricsPort {
    override fun recordWebhookIngestion(
        outcome: com.whatto.bcm.domain.monitoring.WebhookIngestionMetricOutcome,
        receivedAt: String?,
    ) = Unit

    override fun recordVendorCall(
        operation: String,
        outcome: com.whatto.bcm.domain.monitoring.VendorCallMetricOutcome,
    ) = Unit

    override fun recordReconciliation(recoveredCount: Int) = Unit
}
