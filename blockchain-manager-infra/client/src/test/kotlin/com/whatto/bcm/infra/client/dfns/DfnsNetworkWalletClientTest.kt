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
    fun `챌린지가 설정된 credential을 허용하지 않거나 필수 허용 키 목록이 없으면 서명·생성 호출 없이 실패한다`() {
        listOf(
            """{"challenge":"c","challengeIdentifier":"i","allowCredentials":{"key":[{"type":"public-key","id":"cr-other-other-otherotherother0"}]}}""",
            """{"challenge":"c","challengeIdentifier":"i"}""",
            """{"challenge":"c","challengeIdentifier":"i","allowCredentials":{"webauthn":[]}}""",
            """{"challenge":"c","challengeIdentifier":"i","allowCredentials":{"key":"${DfnsTestKeyFixture.CREDENTIAL_ID}"}}""",
        ).forEach { init ->
            val (client, server) = fixture()
            server.expect(requestTo("$BASE/auth/action/init")).andRespond(withSuccess(init, MediaType.APPLICATION_JSON))

            assertThatThrownBy { client.create(request, submission) }.describedAs(init).isInstanceOf(VendorApiException::class.java)
            server.verify()
        }
    }

    @Test
    fun `챌린지·서명 단계의 HTTP 오류와 결손 응답은 지갑 생성 호출 없이 전파된다`() {
        listOf(
            """{"error":{"message":"invalid token"}}""" to HttpStatus.UNAUTHORIZED,
            """{"challengeIdentifier":"i"}""" to HttpStatus.OK,
            "not json" to HttpStatus.OK,
            BAD_CHALLENGE to HttpStatus.OK,
        ).forEach { (initBody, status) ->
            val (client, server) = fixture()
            server
                .expect(requestTo("$BASE/auth/action/init"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(initBody))

            assertThatThrownBy { client.create(request, submission) }
                .isInstanceOfSatisfying(VendorApiException::class.java) {
                    assertThat(it.httpStatus).isEqualTo(status.value())
                    assertThat(it.responseBody()).isEqualTo(initBody.toByteArray())
                }
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
            .isInstanceOfSatisfying(VendorApiException::class.java) {
                assertThat(it.httpStatus).isEqualTo(403)
                assertThat(it.responseBody()).isEqualTo("""{"error":{"message":"forbidden"}}""".toByteArray())
                assertThat(it.message).doesNotContain("forbidden")
            }
        server.verify()
    }

    @Test
    fun `생성 응답에 필수 id·network·signingKey·status·custodial이 없거나 형식이 다르면 정규화하지 않고 실패한다`() {
        listOf(
            """{"network":"EthereumSepolia","status":"Active","custodial":true,"signingKey":{"id":"key-1"}}""",
            """{"id":"wa-1","status":"Active","custodial":true,"signingKey":{"id":"key-1"}}""",
            """{"id":"wa-1","network":"EthereumSepolia","status":"Active","custodial":true}""",
            """{"id":"wa-1","network":"EthereumSepolia","status":"Active","signingKey":{"id":"key-1"}}""",
            """{"id":"wa-1","network":"EthereumSepolia","custodial":true,"signingKey":{"id":"key-1"}}""",
            """{"id":"wa-1","network":"EthereumSepolia","status":"Active","custodial":"true","signingKey":{"id":"key-1"}}""",
            """{"id":"wa-1","network":"EthereumSepolia","status":"Active","custodial":true,"signingKey":{"id":"key-1"},"address":123}""",
            "[]",
        ).forEach { created ->
            val (client, server) = fixture()
            server.expect(requestTo("$BASE/auth/action/init")).andRespond(withSuccess(CHALLENGE, MediaType.APPLICATION_JSON))
            server.expect(requestTo("$BASE/auth/action")).andRespond(withSuccess(USER_ACTION, MediaType.APPLICATION_JSON))
            server.expect(requestTo("$BASE/wallets")).andRespond(withSuccess(created, MediaType.APPLICATION_JSON))

            assertThatThrownBy { client.create(request, submission) }
                .describedAs(created)
                .isInstanceOfSatisfying(VendorApiException::class.java) { assertThat(it.responseBody()).isEqualTo(created.toByteArray()) }
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
    fun `예약 문자를 포함한 페이지 토큰은 한 번만 인코딩해 보낸다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("$BASE/wallets?limit=100&paginationToken=a%2Fb%3D%3D%2Bc%20d"))
            .andRespond(withSuccess("""{"items":[],"nextPageToken":"e/f=="}""", MediaType.APPLICATION_JSON))

        val response = client.candidates(request, "a/b==+c d")

        server.verify()
        assertThat(response.value.next).isEqualTo("e/f==")
        assertThatThrownBy { client.candidates(request, " ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `nextPageToken이 있는데 비어 있지 않은 문자열이 아니면 조회 완료로 바꾸지 않고 실패한다`() {
        listOf("""{"items":[],"nextPageToken":123}""", """{"items":[],"nextPageToken":""}""", """{"items":[],"nextPageToken":{"a":1}}""")
            .forEach { page ->
                val (client, server) = fixture()
                server.expect(requestTo("$BASE/wallets?limit=100")).andRespond(withSuccess(page, MediaType.APPLICATION_JSON))

                assertThatThrownBy { client.candidates(request, null) }.describedAs(page).isInstanceOf(VendorApiException::class.java)
                server.verify()
            }
        val (client, server) = fixture()
        server
            .expect(requestTo("$BASE/wallets?limit=100"))
            .andRespond(withSuccess("""{"items":[],"nextPageToken":null}""", MediaType.APPLICATION_JSON))
        assertThat(client.candidates(request, null).value.next).isNull()
    }

    @Test
    fun `목록 항목이 객체가 아니거나 externalId가 문자열이 아니면 버리지 않고 페이지 해석을 거절한다`() {
        val good =
            """{"id":"wa-a","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"corr-1","tags":[],"signingKey":{"id":"k"}}"""
        listOf(
            """{"items":[$good, 123]}""",
            """{"items":[$good, {"id":"wa-b","externalId":123}]}""",
            """{"items":[[]]}""",
        ).forEach { page ->
            val (client, server) = fixture()
            server.expect(requestTo("$BASE/wallets?limit=100")).andRespond(withSuccess(page, MediaType.APPLICATION_JSON))

            assertThatThrownBy { client.candidates(request, null) }.describedAs(page).isInstanceOf(VendorApiException::class.java)
            server.verify()
        }
    }

    @Test
    fun `빈 address는 주소 대기(null)이고 빈 externalId는 상관관계 없음(null)이며 minLength 1인 delegatedTo·vaultId의 빈 값은 오류다`() {
        val (client, server) = fixture()
        val page =
            """{"items":[
              {"id":"wa-a","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"corr-1","address":"","tags":[],"signingKey":{"id":"k"}},
              {"id":"wa-b","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"","tags":[],"signingKey":{"id":"k"}}
            ]}"""
        server.expect(requestTo("$BASE/wallets?limit=100")).andRespond(withSuccess(page, MediaType.APPLICATION_JSON))

        val response = client.candidates(request, null)

        server.verify()
        assertThat(response.value.data.map { it.vendorWalletId }).containsExactly("wa-a")
        assertThat(
            response.value.data
                .single()
                .address,
        ).isNull()
        val (client2, server2) = fixture()
        server2
            .expect(requestTo("$BASE/wallets/wa-b"))
            .andRespond(
                withSuccess(
                    """{"id":"wa-b","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"","tags":[],"signingKey":{"id":"k"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        assertThat(client2.read(request.scope, "wa-b").value?.correlationId).isNull()
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
              {"id":"wa-5","network":"EthereumSepolia","status":"Archived","custodial":true,"externalId":"corr-1","tags":[],"signingKey":{"id":"k","scheme":"ECDSA","curve":"secp256k1","publicKey":"00"}}
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
            ),
        )
    }

    @Test
    fun `소유 판정에 필요한 필드가 빠지거나 형식이 다른 후보는 조직 소유로 승인하지 않고 페이지 해석을 거절한다`() {
        listOf(
            """{"id":"wa-6","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"corr-1","tags":[]}""",
            """{"id":"wa-7","network":"EthereumSepolia","status":"Active","externalId":"corr-1","tags":[],"signingKey":{"id":"k"}}""",
            """{"id":"wa-8","network":"EthereumSepolia","custodial":true,"externalId":"corr-1","tags":[],"signingKey":{"id":"k"}}""",
            """{"id":"wa-9","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"corr-1","tags":[],"signingKey":{"id":"k","delegatedTo":7}}""",
            """{"id":"wa-10","network":"EthereumSepolia","status":"Active","custodial":true,"externalId":"corr-1","tags":[],"vaultId":"","signingKey":{"id":"k"}}""",
        ).forEach { wallet ->
            val (client, server) = fixture()
            server
                .expect(
                    requestTo("$BASE/wallets?limit=100"),
                ).andRespond(withSuccess("""{"items":[$wallet]}""", MediaType.APPLICATION_JSON))

            assertThatThrownBy { client.candidates(request, null) }.describedAs(wallet).isInstanceOf(VendorApiException::class.java)
            server.verify()
        }
    }

    @Test
    fun `후보 조회의 HTTP 오류·items 결손은 빈 페이지로 바꾸지 않고 수신 바이트와 함께 전파한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("$BASE/wallets?limit=100"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body("{}"))
        server.expect(requestTo("$BASE/wallets?limit=100")).andRespond(withSuccess("""{"nextPageToken":"x"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { client.candidates(request, null) }
            .isInstanceOfSatisfying(VendorApiException::class.java) {
                assertThat(it.httpStatus).isEqualTo(429)
                assertThat(it.responseBody()).isEqualTo("{}".toByteArray())
            }
        assertThatThrownBy { client.candidates(request, null) }
            .isInstanceOfSatisfying(VendorApiException::class.java) {
                assertThat(it.httpStatus).isEqualTo(200)
                assertThat(it.responseBody()).isEqualTo("""{"nextPageToken":"x"}""".toByteArray())
            }
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

    @Test
    fun `지갑 자산 조회는 서명 없이 Bearer만 보내고 모델링한 kind를 매핑과 같은 키로 정규화하며 나머지 kind는 대조 대상에서 제외한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("$BASE/wallets/wa-1/assets"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-account-token"))
            .andExpect(headerDoesNotExist("X-DFNS-USERACTION"))
            .andRespond(withSuccess(WALLET_ASSETS, MediaType.APPLICATION_JSON))

        val snapshot = client.assets(request.scope, "wa-1")

        assertThat(snapshot.vendorWalletId).isEqualTo("wa-1")
        assertThat(snapshot.network).isEqualTo("ETHEREUM_SEPOLIA")
        assertThat(snapshot.assets.map { it.vendorAssetId }).containsExactly(
            "EthereumSepolia:Native",
            "EthereumSepolia:Erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
            "EthereumSepolia:Spl:EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        )
        assertThat(snapshot.assets[0].amount()).isEqualTo("0.5")
        assertThat(snapshot.assets[1]).satisfies({
            assertThat(it.symbol).isEqualTo("USDC")
            assertThat(it.decimals).isEqualTo(6)
            assertThat(it.baseUnits).isEqualTo("1500000")
            assertThat(it.verified).isTrue()
            assertThat(it.amount()).isEqualTo("1.5")
        })
        assertThat(snapshot.assets[2].verified).isNull()
        server.verify()
    }

    @Test
    fun `지갑 자산 응답의 지갑 ID·네트워크가 요청과 다르거나 항목 필수 필드가 결손·형식 오류면 정규화하지 않고 실패한다`() {
        listOf(
            """{"walletId":"wa-2","network":"EthereumSepolia","assets":[]}""",
            """{"walletId":"wa-1","network":"BaseSepolia","assets":[]}""",
            """{"walletId":"wa-1","network":"ETHEREUM_SEPOLIA","assets":[]}""",
            """{"walletId":"wa-1","network":"Ethereum","assets":[]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia"}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":["x"]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"decimals":18,"balance":"1"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Erc20","decimals":6,"balance":"1"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Native","balance":"1"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Native","decimals":"18","balance":"1"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Native","decimals":18.5,"balance":"1"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Native","decimals":18}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Native","decimals":18,"balance":"1.5"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Native","decimals":18,"balance":1}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Native","decimals":18,"balance":"1","verified":"yes"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Erc20","contract":"0xabc","decimals":6,"balance":"1"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Spl","mint":"0OIl","decimals":6,"balance":"1"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Spl2022","mint":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v2","decimals":6,"balance":"1"}]}""",
            """{"walletId":"wa-1","network":"EthereumSepolia","assets":[{"kind":"Spl","decimals":6,"balance":"1"}]}""",
        ).forEach { body ->
            val (client, server) = fixture()
            server.expect(requestTo("$BASE/wallets/wa-1/assets")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

            assertThatThrownBy { client.assets(request.scope, "wa-1") }
                .describedAs(body)
                .isInstanceOfSatisfying(VendorApiException::class.java) { assertThat(it.responseBody()).isEqualTo(body.toByteArray()) }
            server.verify()
        }
    }

    @Test
    fun `지갑 자산 조회의 HTTP 오류는 빈 목록으로 바꾸지 않고 상태·수신 바이트와 함께 전파하며 잘못된 지갑 ID와 다른 원천은 호출 전에 거절한다`() {
        val (client, server) = fixture()
        server
            .expect(requestTo("$BASE/wallets/wa-1/assets"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""{"error":{"message":"not found"}}"""),
            )

        assertThatThrownBy { client.assets(request.scope, "wa-1") }
            .isInstanceOfSatisfying(VendorApiException::class.java) {
                assertThat(it.httpStatus).isEqualTo(404)
                assertThat(it.responseBody()).isEqualTo("""{"error":{"message":"not found"}}""".toByteArray())
            }
        server.verify()

        val (idle, idleServer) = fixture()
        assertThatThrownBy { idle.assets(request.scope, "wa 1/../x") }.isInstanceOf(IllegalArgumentException::class.java)
        val foreign = NetworkWalletScope(ProviderOrigin("other", "dfns", "dfns", "p", "o", "TESTNET"), "acct_dfns_1", "ETHEREUM_SEPOLIA")
        assertThatThrownBy { idle.assets(foreign, "wa-1") }.isInstanceOf(IllegalStateException::class.java)
        idleServer.verify()
    }

    companion object {
        private const val BASE = "https://baseline.dfns.internal.test"

        /** 명세 Get Wallet Assets 응답 형태 — Native·Erc20·Spl 항목과 모델 밖 kind(Iou) 하나. 값은 예시이며 Baseline 실측이 아니다. */
        private val WALLET_ASSETS =
            """
            {"walletId":"wa-1","network":"EthereumSepolia","assets":[
              {"kind":"Native","symbol":"ETH","decimals":18,"verified":true,"balance":"500000000000000000"},
              {"kind":"Erc20","contract":"0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48","symbol":"USDC","decimals":6,"verified":true,"balance":"1500000"},
              {"kind":"Spl","mint":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v","decimals":6,"balance":"7"},
              {"kind":"Iou","currency":"USD","issuer":"rhub","decimals":15,"balance":"1"}
            ]}
            """.trimIndent()
        private val ORIGIN = ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET")
        private val CHALLENGE =
            """{"challenge":"Y2gtNzloaHQtbXJlb2stOGFwOHFtMmVpZWZ0amxhZw","challengeIdentifier":"eyJ0e.fQNA",
               "allowCredentials":{"key":[{"type":"public-key","id":"${DfnsTestKeyFixture.CREDENTIAL_ID}"}]}}"""
        private const val USER_ACTION = """{"userAction":"eyJ0eX.bzrQakA"}"""

        /** 허용 credential은 맞지만 challenge가 서명 입력이 될 수 없는 형식(공백 포함)인 init 응답. */
        private val BAD_CHALLENGE =
            """{"challenge":"bad challenge","challengeIdentifier":"i",
               "allowCredentials":{"key":[{"type":"public-key","id":"${DfnsTestKeyFixture.CREDENTIAL_ID}"}]}}"""

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
