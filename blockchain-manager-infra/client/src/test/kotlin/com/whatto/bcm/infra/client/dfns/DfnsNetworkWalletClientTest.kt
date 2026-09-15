package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
import com.whatto.bcm.infra.client.dfns.fixture.DfnsTestKeyFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
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
import tools.jackson.databind.ObjectMapper
import java.security.KeyPair

/**
 * Dfns 지갑 어댑터 계약 — 공식 OpenAPI 1.1018.3의 `/auth/action/init`·`/auth/action`·`/wallets`·`/wallets/{walletId}`.
 * 벤더 실호출 없음(MockRestServiceServer). 응답 JSON은 명세 schema/예시 필드만 사용하며 Baseline 실측 payload가 아니다.
 */
class DfnsNetworkWalletClientTest {
    private val keyPair: KeyPair = DfnsTestKeyFixture.ecKeyPair()
    private val objectMapper = ObjectMapper()
    private val request = NetworkWalletCreationRequest(NetworkWalletScope(ORIGIN, "acct_dfns_1", "ETHEREUM_SEPOLIA"), "corr-1")
    private val body = DfnsNetworkWalletClient.createWalletBody("EthereumSepolia", "corr-1")
    private val submission = NetworkWalletSubmissionSpec(DfnsNetworkWalletClient.requestHash(body), "dfns-1.1018.3", "EthereumSepolia")

    private fun fixture(): Pair<DfnsNetworkWalletClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val properties =
            DfnsProperties(
                baseUrl = BASE,
                authToken = "service-account-token",
                credentialId = DfnsTestKeyFixture.CREDENTIAL_ID,
                credentialPrivateKeyPem = DfnsTestKeyFixture.pem(keyPair),
                candidatePageSize = 100,
                networks = mapOf("ETHEREUM_SEPOLIA" to "EthereumSepolia", "BASE_SEPOLIA" to "BaseSepolia"),
            )
        val client =
            DfnsNetworkWalletClient(
                builder,
                properties,
                ORIGIN,
                DfnsCredentialSigner(properties.credentialId, properties.credentialPrivateKeyPem),
                NoOpOperationalMetricsPort,
                DfnsRestClientFactory { testBuilder, testProperties -> testBuilder.baseUrl(testProperties.baseUrl).build() },
            )
        return client to server
    }

    @Test
    fun `생성은 같은 본문 바이트로 챌린지·서명·지갑 생성 세 호출을 순서대로 보내고 원문 바이트를 그대로 돌려준다`() {
        val (client, server) = fixture()
        val bodyText = body.toString(Charsets.UTF_8)
        val signedRequests = mutableListOf<MockClientHttpRequest>()
        server
            .expect(requestTo("$BASE/auth/action/init"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-account-token"))
            .andExpect(headerDoesNotExist("X-DFNS-USERACTION"))
            .andExpect(jsonPath("$.userActionServerKind").value("Api"))
            .andExpect(jsonPath("$.userActionHttpMethod").value("POST"))
            .andExpect(jsonPath("$.userActionHttpPath").value("/wallets"))
            .andExpect(jsonPath("$.userActionPayload").value(bodyText))
            .andRespond(
                withSuccess(
                    """{"challenge":"Y2gtNzloaHQtbXJlb2stOGFwOHFtMmVpZWZ0amxhZw","challengeIdentifier":"eyJ0e.fQNA",
                      "allowCredentials":{"key":[{"type":"public-key","id":"${DfnsTestKeyFixture.CREDENTIAL_ID}"}],"webauthn":[]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server
            .expect(requestTo("$BASE/auth/action"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-account-token"))
            .andExpect(headerDoesNotExist("X-DFNS-USERACTION"))
            .andExpect(jsonPath("$.challengeIdentifier").value("eyJ0e.fQNA"))
            .andExpect(jsonPath("$.firstFactor.kind").value("Key"))
            .andExpect(jsonPath("$.firstFactor.credentialAssertion.credId").value(DfnsTestKeyFixture.CREDENTIAL_ID))
            .andExpect(jsonPath("$.firstFactor.credentialAssertion.algorithm").doesNotExist())
            .andExpect(jsonPath("$.secondFactor").doesNotExist())
            .andExpect { signedRequests += it as MockClientHttpRequest }
            .andRespond(withSuccess("""{"userAction":"eyJ0eX.bzrQakA"}""", MediaType.APPLICATION_JSON))
        server
            .expect(requestTo("$BASE/wallets"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-account-token"))
            .andExpect(header("X-DFNS-USERACTION", "eyJ0eX.bzrQakA"))
            .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
            .andExpect(content().bytes(body))
            .andRespond(withSuccess(CREATED_WALLET, MediaType.APPLICATION_JSON))

        val response = client.create(request, submission)

        server.verify()
        val assertion = objectMapper.readTree(signedRequests.single().bodyAsBytes).path("firstFactor").path("credentialAssertion")
        assertThat(
            String(
                java.util.Base64
                    .getUrlDecoder()
                    .decode(assertion.path("clientData").asString()),
            ),
        ).isEqualTo("""{"challenge":"Y2gtNzloaHQtbXJlb2stOGFwOHFtMmVpZWZ0amxhZw","type":"key.get"}""")
        assertThat(
            DfnsTestKeyFixture.verifies(keyPair, assertion.path("clientData").asString(), assertion.path("signature").asString()),
        ).isTrue()
        assertThat(response.bodyBytes()).isEqualTo(CREATED_WALLET.toByteArray())
        assertThat(response.value.vendorWalletId).isEqualTo("wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx")
        assertThat(response.value.network).isEqualTo("ETHEREUM_SEPOLIA")
        assertThat(response.value.correlationId).isEqualTo("corr-1")
        assertThat(response.value.ownership).isEqualTo(NetworkWalletOwnership.ORGANIZATION)
        assertThat(response.value.address).isEqualTo("0x00e3495cf6af59008f22ffaf32d4c92ac33dac47")
        assertThat(response.value.origin).isEqualTo(ORIGIN)
    }

    @Test
    fun `저장된 요청 해시와 다른 본문은 어떤 HTTP 호출도 하지 않는다`() {
        val (client, server) = fixture()

        assertThatThrownBy { client.create(request, submission.copy(requestHash = "b".repeat(64))) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { client.create(request, submission.copy(vendorNetwork = "BaseSepolia")) }
            .isInstanceOf(IllegalStateException::class.java)
        server.verify()
    }

    @Test
    fun `챌린지가 설정된 credential을 허용하지 않으면 서명·생성 호출 없이 실패한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("$BASE/auth/action/init"))
            .andRespond(
                withSuccess(
                    """{"challenge":"c","challengeIdentifier":"i","allowCredentials":{"key":[{"type":"public-key","id":"cr-other-other-otherotherother0"}]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThatThrownBy { client.create(request, submission) }.isInstanceOf(VendorApiException::class.java)
        server.verify()
    }

    @Test
    fun `챌린지·서명 단계의 HTTP 오류와 결손 응답은 지갑 생성 호출 없이 전파된다`() {
        listOf(
            withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body("""{"error":{"message":"invalid token"}}"""),
            withSuccess("""{"challengeIdentifier":"i"}""", MediaType.APPLICATION_JSON),
            withSuccess("not json", MediaType.APPLICATION_JSON),
        ).forEach { initResponse ->
            val (client, server) = fixture()
            server.expect(requestTo("$BASE/auth/action/init")).andRespond(initResponse)

            assertThatThrownBy { client.create(request, submission) }.isInstanceOf(VendorApiException::class.java)
            server.verify()
        }
        val (client, server) = fixture()
        server.expect(requestTo("$BASE/auth/action/init")).andRespond(withSuccess(CHALLENGE, MediaType.APPLICATION_JSON))
        server.expect(requestTo("$BASE/auth/action")).andRespond(withSuccess("""{"userAction":""}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { client.create(request, submission) }.isInstanceOf(VendorApiException::class.java)
        server.verify()
    }

    @Test
    fun `지갑 생성의 HTTP 오류는 상태를 담아 전파하고 자동 재호출하지 않는다`() {
        val (client, server) = fixture()
        server.expect(requestTo("$BASE/auth/action/init")).andRespond(withSuccess(CHALLENGE, MediaType.APPLICATION_JSON))
        server.expect(requestTo("$BASE/auth/action")).andRespond(withSuccess(USER_ACTION, MediaType.APPLICATION_JSON))
        server
            .expect(requestTo("$BASE/wallets"))
            .andRespond(
                withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("""{"error":{"message":"forbidden"}}"""),
            )

        assertThatThrownBy { client.create(request, submission) }
            .isInstanceOfSatisfying(VendorApiException::class.java) { assertThat(it.httpStatus).isEqualTo(403) }
        server.verify()
    }

    @Test
    fun `생성 응답에 필수 id·network가 없으면 정규화하지 않고 실패한다`() {
        listOf(
            """{"network":"EthereumSepolia","status":"Active","custodial":true}""",
            """{"id":"wa-1","status":"Active","custodial":true}""",
            "[]",
        ).forEach { created ->
            val (client, server) = fixture()
            server.expect(requestTo("$BASE/auth/action/init")).andRespond(withSuccess(CHALLENGE, MediaType.APPLICATION_JSON))
            server.expect(requestTo("$BASE/auth/action")).andRespond(withSuccess(USER_ACTION, MediaType.APPLICATION_JSON))
            server.expect(requestTo("$BASE/wallets")).andRespond(withSuccess(created, MediaType.APPLICATION_JSON))

            assertThatThrownBy { client.create(request, submission) }.isInstanceOf(VendorApiException::class.java)
            server.verify()
        }
    }

    @Test
    fun `단건 조회는 서명 없이 Bearer만 보내고 200 응답을 정규화한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("$BASE/wallets/wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-account-token"))
            .andExpect(headerDoesNotExist("X-DFNS-USERACTION"))
            .andRespond(withSuccess(CREATED_WALLET, MediaType.APPLICATION_JSON))

        val response = client.read(request.scope, "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx")

        server.verify()
        assertThat(response.bodyBytes()).isEqualTo(CREATED_WALLET.toByteArray())
        assertThat(response.value?.vendorWalletId).isEqualTo("wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx")
        assertThat(response.value?.ownership).isEqualTo(NetworkWalletOwnership.ORGANIZATION)
    }

    @Test
    fun `단건 조회 404는 본문을 보존한 미관찰이고 다른 오류와 ID 불일치는 전파된다`() {
        val (client, server) = fixture()
        val notFound = """{"error":{"message":"Wallet not found"}}"""
        server
            .expect(requestTo("$BASE/wallets/wa-missing"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(notFound))
        server
            .expect(requestTo("$BASE/wallets/wa-broken"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body("{}"))
        server.expect(requestTo("$BASE/wallets/wa-other")).andRespond(withSuccess(CREATED_WALLET, MediaType.APPLICATION_JSON))

        val missing = client.read(request.scope, "wa-missing")
        assertThat(missing.value).isNull()
        assertThat(missing.bodyBytes()).isEqualTo(notFound.toByteArray())
        assertThatThrownBy { client.read(request.scope, "wa-broken") }
            .isInstanceOfSatisfying(VendorApiException::class.java) { assertThat(it.httpStatus).isEqualTo(500) }
        assertThatThrownBy { client.read(request.scope, "wa-other") }.isInstanceOf(VendorApiException::class.java)
        assertThatThrownBy { client.read(request.scope, "../wallets") }.isInstanceOf(IllegalArgumentException::class.java)
        server.verify()
    }

    @Test
    fun `후보 조회는 명세 query만 쓰고 페이지 항목을 correlation(externalId)으로 좁히며 원문·다음 토큰을 보존한다`() {
        val (client, server) = fixture()
        val page =
            """
            {"items":[
              {"id":"wa-a","network":"EthereumSepolia","address":"0xaaa","signingKey":{"id":"key-a","scheme":"ECDSA","curve":"secp256k1","publicKey":"aa"},
               "status":"Active","dateCreated":"2026-09-15T00:00:00.000Z","custodial":true,"externalId":"corr-1","tags":[]},
              {"id":"wa-b","network":"EthereumSepolia","signingKey":{"id":"key-b","scheme":"ECDSA","curve":"secp256k1","publicKey":"bb"},
               "status":"Active","dateCreated":"2026-09-15T00:00:00.000Z","custodial":true,"externalId":"corr-other","tags":[]},
              {"id":"wa-c","network":"BaseSepolia","signingKey":{"id":"key-c","scheme":"ECDSA","curve":"secp256k1","publicKey":"cc"},
               "status":"Active","dateCreated":"2026-09-15T00:00:00.000Z","custodial":true,"tags":[]},
              {"id":"wa-d","network":"Polygon","address":"0xddd","signingKey":{"id":"key-d","scheme":"ECDSA","curve":"secp256k1","publicKey":"dd"},
               "status":"Active","dateCreated":"2026-09-15T00:00:00.000Z","custodial":true,"externalId":"corr-1","tags":[]}
            ],"nextPageToken":"page-2"}
            """.trimIndent()
        server
            .expect(requestTo("$BASE/wallets?limit=100&paginationToken=page-1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(headerDoesNotExist("X-DFNS-USERACTION"))
            .andRespond(withSuccess(page, MediaType.APPLICATION_JSON))
        server
            .expect(requestTo("$BASE/wallets?limit=100"))
            .andRespond(withSuccess("""{"items":[]}""", MediaType.APPLICATION_JSON))

        val response = client.candidates(request, "page-1")
        val empty = client.candidates(request, null)

        server.verify()
        assertThat(response.bodyBytes()).isEqualTo(page.toByteArray())
        assertThat(response.value.next).isEqualTo("page-2")
        assertThat(response.value.data.map { it.vendorWalletId }).containsExactly("wa-a", "wa-d")
        assertThat(response.value.data.map { it.network }).containsExactly("ETHEREUM_SEPOLIA", "Polygon")
        assertThat(
            response.value.data
                .first()
                .address,
        ).isEqualTo("0xaaa")
        assertThat(empty.value.data).isEmpty()
        assertThat(empty.value.next).isNull()
    }

    @Test
    fun `소유 판정 — custodial·위임·Vault 통제·상태로 조직 사용 가능 자원만 ORGANIZATION이다`() {
        val (client, server) = fixture()
        val page =
            """
            {"items":[
              {"id":"wa-1","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"corr-1","tags":[],"signingKey":{"id":"k","scheme":"ECDSA","curve":"secp256k1","publicKey":"00"}},
              {"id":"wa-2","network":"EthereumSepolia","status":"Active","custodial":false,"externalId":"corr-1","tags":[],"signingKey":{"id":"k","scheme":"ECDSA","curve":"secp256k1","publicKey":"00"}},
              {"id":"wa-3","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"corr-1","tags":[],"signingKey":{"id":"k","scheme":"ECDSA","curve":"secp256k1","publicKey":"00","delegatedTo":"us-6b58p-r53sr-rlrd3l5cj3uc4ome"}},
              {"id":"wa-4","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"corr-1","tags":[],"vaultId":"vlt-5vbsp-u62g1-ostmunqgds5o9tc2","signingKey":{"id":"k","scheme":"ECDSA","curve":"secp256k1","publicKey":"00"}},
              {"id":"wa-5","network":"EthereumSepolia","status":"Archived","custodial":true,"externalId":"corr-1","tags":[],"signingKey":{"id":"k","scheme":"ECDSA","curve":"secp256k1","publicKey":"00"}},
              {"id":"wa-6","network":"EthereumSepolia","externalId":"corr-1","tags":[],"signingKey":{"id":"k","scheme":"ECDSA","curve":"secp256k1","publicKey":"00"}}
            ]}
            """.trimIndent()
        server.expect(requestTo("$BASE/wallets?limit=100")).andRespond(withSuccess(page, MediaType.APPLICATION_JSON))

        val ownership =
            client
                .candidates(request, null)
                .value.data
                .associate { it.vendorWalletId to it.ownership }

        assertThat(ownership).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "wa-1" to NetworkWalletOwnership.ORGANIZATION,
                "wa-2" to NetworkWalletOwnership.OTHER,
                "wa-3" to NetworkWalletOwnership.OTHER,
                "wa-4" to NetworkWalletOwnership.OTHER,
                "wa-5" to NetworkWalletOwnership.OTHER,
                "wa-6" to NetworkWalletOwnership.UNVERIFIED,
            ),
        )
    }

    @Test
    fun `후보 조회의 HTTP 오류·items 결손은 빈 페이지로 바꾸지 않고 전파한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("$BASE/wallets?limit=100"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body("{}"))
        server.expect(requestTo("$BASE/wallets?limit=100")).andRespond(withSuccess("""{"nextPageToken":"x"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { client.candidates(request, null) }
            .isInstanceOfSatisfying(VendorApiException::class.java) { assertThat(it.httpStatus).isEqualTo(429) }
        assertThatThrownBy { client.candidates(request, null) }.isInstanceOf(VendorApiException::class.java)
        server.verify()
    }

    @Test
    fun `다른 원천의 scope는 호출 전에 거절한다`() {
        val (client, server) = fixture()
        val other = ProviderOrigin("other-origin", "dfns", "dfns", "other-platform", "other-organization", "TESTNET")
        val foreign = NetworkWalletCreationRequest(NetworkWalletScope(other, "acct_dfns_1", "ETHEREUM_SEPOLIA"), "corr-1")

        assertThatThrownBy { client.create(foreign, submission) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { client.read(foreign.scope, "wa-1") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { client.candidates(foreign) }.isInstanceOf(IllegalStateException::class.java)
        server.verify()
    }

    @Test
    fun `생성 본문은 명세의 network와 externalId만 담고 100자를 넘는 상관관계 값을 거절한다`() {
        assertThat(body.toString(Charsets.UTF_8)).isEqualTo("""{"network":"EthereumSepolia","externalId":"corr-1"}""")
        assertThat(DfnsNetworkWalletClient.requestHash(body)).matches("[0-9a-f]{64}")
        assertThatThrownBy { DfnsNetworkWalletClient.createWalletBody("EthereumSepolia", "x".repeat(101)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DfnsNetworkWalletClient.createWalletBody(" ", "corr-1") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    companion object {
        private const val BASE = "https://baseline.dfns.internal.test"
        private val ORIGIN = ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET")
        private val CHALLENGE =
            """{"challenge":"Y2gtNzloaHQtbXJlb2stOGFwOHFtMmVpZWZ0amxhZw","challengeIdentifier":"eyJ0e.fQNA",
               "allowCredentials":{"key":[{"type":"public-key","id":"${DfnsTestKeyFixture.CREDENTIAL_ID}"}]}}"""
        private const val USER_ACTION = """{"userAction":"eyJ0eX.bzrQakA"}"""

        /** 명세 Wallet schema 예시 필드 + externalId. 공백·줄바꿈을 포함한 바이트가 그대로 보존되는지 본다. */
        private val CREATED_WALLET =
            """
            {
              "id": "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx",
              "network": "EthereumSepolia",
              "address": "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47",
              "signingKey": { "id": "key-6ece3-9l565-xxxxxxxxxxxxxxxx", "scheme": "ECDSA", "curve": "secp256k1", "publicKey": "e2375c8c9e" },
              "status": "Active",
              "dateCreated": "2026-09-15T00:00:00.000Z",
              "custodial": true,
              "externalId": "corr-1",
              "tags": []
            }
            """.trimIndent()
    }
}
