package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.infra.client.fireblocks.fixture.TestRsaKeyFixture
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
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests
import org.springframework.web.client.RestClient
import java.time.Clock

/**
 * 벤더 클라이언트 계약 — 인증 헤더 · Idempotency-Key · 응답 매핑 · 429 백오프 · 도메인 예외 변환.
 * 벤더 실호출 없음 — MockRestServiceServer (CLAUDE.md 0절).
 * 엔드포인트·필드 근거: fireblocks-openapi-spec api-spec-v2.yaml (2026-08-05 확인).
 */
class FireblocksClientTest {
    private val privateKeyPem: String = TestRsaKeyFixture.toPkcs8Pem(TestRsaKeyFixture.generateKeyPair())

    private fun fixture(
        maxAttempts: Int = 3,
        maxBackoffMillis: Long = 250,
    ): Pair<FireblocksClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val properties =
            FireblocksProperties(
                baseUrl = "https://sandbox-api.fireblocks.test",
                apiKey = "api-key-1",
                privateKeyPem = privateKeyPem,
                maxAttempts = maxAttempts,
                retryBackoffMillis = 1,
                maxBackoffMillis = maxBackoffMillis,
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
            )
        return client to server
    }

    @Test
    fun `createVault — POST vault accounts, 인증·멱등 헤더와 name 본문, id·name 매핑`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/vault/accounts"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-Key", "api-key-1"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, org.hamcrest.Matchers.startsWith("Bearer ")))
            .andExpect(header("Idempotency-Key", "idem-1"))
            .andExpect(jsonPath("$.name").value("acct-ref-1"))
            .andRespond(
                withSuccess(
                    """{"id":"7","name":"acct-ref-1","assets":[],"hiddenOnUI":false}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val vault = client.createVault(name = "acct-ref-1", idempotencyKey = "idem-1")

        assertThat(vault.vaultId).isEqualTo("7")
        assertThat(vault.name).isEqualTo("acct-ref-1")
        server.verify()
    }

    @Test
    fun `createDepositAddress — POST vault wallet 생성, address·tag 매핑 (tag 없으면 null)`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/vault/accounts/7/ETH_TEST5"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Idempotency-Key", "idem-2"))
            .andRespond(
                withSuccess(
                    """{"id":"0","address":"0xabc123","status":"READY"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val depositAddress = client.createDepositAddress(vaultId = "7", assetSymbol = "ETH_TEST5", idempotencyKey = "idem-2")

        assertThat(depositAddress.address).isEqualTo("0xabc123")
        assertThat(depositAddress.tag).isNull()
        server.verify()
    }

    @Test
    fun `balanceOf — GET vault asset, 잔액 5필드를 문자열 그대로 매핑, 멱등 헤더 없음`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/vault/accounts/7/ETH_TEST5"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(headerDoesNotExist("Idempotency-Key"))
            .andRespond(
                withSuccess(
                    """{"id":"ETH_TEST5","total":"10.5","available":"9","pending":"0.5","frozen":"0.5","lockedAmount":"0.5"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val balance = client.balanceOf(vaultId = "7", assetSymbol = "ETH_TEST5")

        assertThat(balance.total).isEqualTo("10.5")
        assertThat(balance.available).isEqualTo("9")
        assertThat(balance.pending).isEqualTo("0.5")
        assertThat(balance.frozen).isEqualTo("0.5")
        assertThat(balance.lockedAmount).isEqualTo("0.5")
        server.verify()
    }

    @Test
    fun `blockchains — 최대 페이지와 커서로 조회하고 카탈로그 필드와 next를 매핑한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/blockchains?pageSize=500&pageCursor=cursor-1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(headerDoesNotExist("Idempotency-Key"))
            .andRespond(
                withSuccess(
                    """{"data":[{"id":"ethereum-uuid","displayName":"Ethereum","metadata":{"deprecated":true},"onchain":{"protocol":"EVM","chainId":"1","test":false}}],"next":"cursor-2"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val page = client.blockchains("cursor-1")

        assertThat(page.next).isEqualTo("cursor-2")
        assertThat(page.data.single().deprecated).isTrue()
        assertThat(
            page.data
                .single()
                .onchain
                ?.chainId,
        ).isEqualTo("1")
        server.verify()
    }

    @Test
    fun `assets — blockchainId·선택 symbol·커서로 후보를 조회하고 내부 UUID를 매핑한다`() {
        val (client, server) = fixture()
        server
            .expect(
                requestTo(
                    "https://sandbox-api.fireblocks.test/v1/assets?blockchainId=ethereum-uuid&pageSize=1000&symbol=USDC&pageCursor=cursor-1",
                ),
            ).andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"data":[{"id":"asset-uuid","legacyId":"USDC_ERC20","blockchainId":"ethereum-uuid","displayName":"USD Coin","displaySymbol":"USDC","assetClass":"FT","onchain":{"address":"0xA0B8","decimals":6}}],"next":null}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val page = client.assets("ethereum-uuid", "USDC", "cursor-1")

        assertThat(page.data.single().id).isEqualTo("asset-uuid")
        assertThat(page.data.single().blockchainId).isEqualTo("ethereum-uuid")
        assertThat(page.data.single().contractAddress).isEqualTo("0xA0B8")
        assertThat(page.data.single().decimals).isEqualTo(6)
        server.verify()
    }

    @Test
    fun `assets — 소수 자릿수 평면 응답도 호환한다`() {
        val (client, server) = fixture()
        server
            .expect(
                requestTo(
                    "https://sandbox-api.fireblocks.test/v1/assets?blockchainId=ethereum-uuid&pageSize=1000&symbol=USDC",
                ),
            ).andRespond(
                withSuccess(
                    """{"data":[{"id":"asset-uuid","blockchainId":"ethereum-uuid","displaySymbol":"USDC","decimals":6,"assetClass":"FT","onchain":{"address":"0xA0B8"}}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val page = client.assets("ethereum-uuid", "USDC", null)

        assertThat(page.data.single().decimals).isEqualTo(6)
        server.verify()
    }

    @Test
    fun `429 는 Retry-After 를 존중해 재시도하고 성공하면 결과를 돌려준다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/vault/accounts/7/ETH_TEST5"))
            .andRespond(withTooManyRequests().header(HttpHeaders.RETRY_AFTER, "0"))
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/vault/accounts/7/ETH_TEST5"))
            .andRespond(
                withSuccess(
                    """{"id":"ETH_TEST5","total":"1","available":"1","pending":"0","frozen":"0","lockedAmount":"0"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val balance = client.balanceOf(vaultId = "7", assetSymbol = "ETH_TEST5")

        assertThat(balance.total).isEqualTo("1")
        server.verify()
    }

    @Test
    fun `429 의 Retry-After 가 커도 1회 대기는 상한으로 잘린다 — 요청 스레드 장기 점유 방지`() {
        val (client, server) = fixture(maxBackoffMillis = 100)
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/vault/accounts/7/ETH_TEST5"))
            .andRespond(withTooManyRequests().header(HttpHeaders.RETRY_AFTER, "2"))
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/vault/accounts/7/ETH_TEST5"))
            .andRespond(
                withSuccess(
                    """{"id":"ETH_TEST5","total":"1","available":"1","pending":"0","frozen":"0","lockedAmount":"0"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val startedAt = System.nanoTime()
        val balance = client.balanceOf(vaultId = "7", assetSymbol = "ETH_TEST5")
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertThat(balance.total).isEqualTo("1")
        // 상한 미적용이면 Retry-After 2초를 그대로 기다린다 — 상한(100ms) 적용 확인
        assertThat(elapsedMillis).isLessThan(1_500)
        server.verify()
    }

    @Test
    fun `429 가 최대 시도까지 계속되면 VendorApiException(429) 로 소진을 알린다`() {
        val (client, server) = fixture(maxAttempts = 2)
        repeat(2) {
            server
                .expect(requestTo("https://sandbox-api.fireblocks.test/v1/vault/accounts/7/ETH_TEST5"))
                .andRespond(withTooManyRequests().header(HttpHeaders.RETRY_AFTER, "0"))
        }

        assertThatThrownBy { client.balanceOf(vaultId = "7", assetSymbol = "ETH_TEST5") }
            .isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                exception as VendorApiException
                assertThat(exception.httpStatus).isEqualTo(429)
                assertThat(exception.operation).isEqualTo("balanceOf")
                assertThat(exception.cause).isNotNull()
            })
        server.verify()
    }

    @Test
    fun `429 외 HTTP 에러는 재시도 없이 VendorApiException 으로 변환한다 — cause 보존`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("https://sandbox-api.fireblocks.test/v1/vault/accounts"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"type":"VALIDATION","message":"name must be ASCII"}}"""),
            )

        assertThatThrownBy { client.createVault(name = "계좌", idempotencyKey = "idem-3") }
            .isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                exception as VendorApiException
                assertThat(exception.httpStatus).isEqualTo(400)
                assertThat(exception.operation).isEqualTo("createVault")
                assertThat(exception.cause).isNotNull()
            })
        server.verify()
    }
}
