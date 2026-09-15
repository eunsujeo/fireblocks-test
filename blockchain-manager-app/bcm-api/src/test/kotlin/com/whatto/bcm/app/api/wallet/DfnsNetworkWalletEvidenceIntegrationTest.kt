package com.whatto.bcm.app.api.wallet

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.LogicalAccountService
import com.whatto.bcm.app.application.wallet.NetworkWalletProvisioningService
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.domain.wallet.NetworkWalletCreationStatus
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceOperation
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
import com.whatto.bcm.infra.client.dfns.DfnsCredentialSigner
import com.whatto.bcm.infra.client.dfns.DfnsNetworkWalletClient
import com.whatto.bcm.infra.client.dfns.DfnsProperties
import com.whatto.bcm.infra.client.dfns.DfnsRestClientFactory
import com.whatto.bcm.infra.persistence.account.AccountJdbcAdapter
import com.whatto.bcm.infra.persistence.account.LogicalAccountJdbcAdapter
import com.whatto.bcm.infra.persistence.provider.ProviderOriginJdbcAdapter
import com.whatto.bcm.infra.persistence.wallet.NetworkWalletEvidenceJdbcAdapter
import com.whatto.bcm.infra.persistence.wallet.NetworkWalletProvisioningJdbcAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * 공식 명세 기반 Dfns HTTP 어댑터 + 내부 생성 서비스 + 실제 PostgreSQL 원장·V24 증적 저장소 결합.
 * HTTP는 MockRestServiceServer(벤더 실호출 없음)이고 응답 JSON은 명세 schema 필드만 쓴 표기다 — 실제 Baseline 응답·서명 원문 수용은 별도다.
 * 검증 대상은 "받은 응답 바이트가 그대로 V24에 보관되고 DB가 계산한 해시와 일치하는가"다. 기본 실행 빈/공개 API에는 연결하지 않는다.
 */
@DataJdbcTest
@ContextConfiguration(classes = [NetworkWalletDatasetTestConfiguration::class])
@Import(
    NetworkWalletProvisioningJdbcAdapter::class,
    NetworkWalletEvidenceJdbcAdapter::class,
    ProviderOriginJdbcAdapter::class,
    LogicalAccountJdbcAdapter::class,
    AccountJdbcAdapter::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DfnsNetworkWalletEvidenceIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var ledger: NetworkWalletProvisioningJdbcAdapter

    @Autowired lateinit var evidence: NetworkWalletEvidenceJdbcAdapter

    @Autowired lateinit var logicalAccounts: LogicalAccountJdbcAdapter

    @Autowired lateinit var accounts: AccountJdbcAdapter

    private val clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var seed: NetworkWalletCreationSeed
    private lateinit var server: MockRestServiceServer
    private lateinit var vendor: DfnsNetworkWalletClient

    @BeforeEach
    fun setup() {
        val account = LogicalAccountService(logicalAccounts, ORIGIN, clock).create(AccountType.CUSTOMER, "dfns-http-${UUID.randomUUID()}")
        val intentId = UUID.randomUUID().toString()
        val correlationId = "corr-$intentId"
        val body = DfnsNetworkWalletClient.createWalletBody(VENDOR_NETWORK, correlationId)
        seed =
            NetworkWalletCreationSeed(
                intentId,
                NetworkWalletCreationRequest(NetworkWalletScope(ORIGIN, account.accountId, BCM_NETWORK), correlationId),
                NetworkWalletSubmissionSpec(DfnsNetworkWalletClient.requestHash(body), "dfns-openapi-1.1018.3", VENDOR_NETWORK),
            )
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        // 실행 중 생성한 일회용 키 — 기존 통합 테스트와 같은 PEM 조립 방식이다(IntegrationTestSupport).
        val pemBody = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$pemBody\n-----END PRIVATE KEY-----"
        val properties =
            DfnsProperties(
                baseUrl = BASE,
                authToken = "test-service-account-token",
                credentialId = CREDENTIAL_ID,
                credentialPrivateKeyPem = pem,
                networks = mapOf(BCM_NETWORK to VENDOR_NETWORK),
            )
        vendor =
            DfnsNetworkWalletClient(
                builder,
                properties,
                ORIGIN,
                DfnsCredentialSigner(CREDENTIAL_ID, pem),
                NoOpOperationalMetricsPort,
                DfnsRestClientFactory { testBuilder, testProperties -> testBuilder.baseUrl(testProperties.baseUrl).build() },
            )
    }

    @AfterEach
    fun cleanup() {
        jdbc.execute("ALTER TABLE bcm_ntwk_wlt_evdc_l DISABLE TRIGGER trg_bcm_ntwk_wlt_evdc_append_only")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_evdc_l")
        jdbc.execute("ALTER TABLE bcm_ntwk_wlt_evdc_l ENABLE TRIGGER trg_bcm_ntwk_wlt_evdc_append_only")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_m")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_obs_item_l")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_obs_l")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_crtn_l")
        jdbc.update("DELETE FROM bcm_acnt_m WHERE ref LIKE 'dfns-http-%'")
    }

    @Test
    fun `생성 응답 바이트가 그대로 V24에 보관되고 DB 계산 해시가 원장 페이지 해시와 같다`() {
        val created =
            walletJson("wa-created-000000000000000", seed.request.correlationId, address = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47")
        expectUserActionSigning()
        server
            .expect(requestTo("$BASE/wallets"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-DFNS-USERACTION", "eyJ0eX.bzrQakA"))
            .andExpect(content().bytes(DfnsNetworkWalletClient.createWalletBody(VENDOR_NETWORK, seed.request.correlationId)))
            .andRespond(withSuccess(created, MediaType.APPLICATION_JSON))

        val completed = service().provision(seed)

        server.verify()
        assertThat(completed.status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(completed.knownWalletId).isEqualTo("wa-created-000000000000000")
        val stored = singleStoredEvidence()
        assertThat(stored.operation).isEqualTo(NetworkWalletEvidenceOperation.CREATE)
        assertThat(stored.body).isEqualTo(created.toByteArray())
        assertThat(stored.hash).isEqualTo(sha256(created.toByteArray()))
        assertThat(stored.length).isEqualTo(created.toByteArray().size)
        assertThat(storedPageHashes()).containsExactly(stored.hash)
        assertThat(ledger.findWallet(seed.request.scope)?.address).isEqualTo("0x00e3495cf6af59008f22ffaf32d4c92ac33dac47")
    }

    @Test
    fun `생성 응답 유실 뒤 재시작 조회는 목록 페이지 원문을 보관하고 필터된 후보로 완료한다`() {
        expectUserActionSigning()
        server
            .expect(requestTo("$BASE/wallets"))
            .andRespond(
                withStatus(HttpStatus.GATEWAY_TIMEOUT).contentType(MediaType.APPLICATION_JSON).body("""{"error":{"message":"timeout"}}"""),
            )
        assertThatThrownBy { service().provision(seed) }.isInstanceOf(VendorApiException::class.java)
        server.verify()
        server.reset()
        assertThat(ledger.find(seed.request.scope)?.status).isEqualTo(NetworkWalletCreationStatus.SUBMITTING)
        assertThat(evidenceCount()).isZero()

        val page =
            """{"items":[${walletJson("wa-other-00000000000000000", "corr-someone-else")},
               ${walletJson("wa-recovered-000000000000", seed.request.correlationId, address = "0xrecovered")}]}"""
        server
            .expect(requestTo("$BASE/wallets?limit=100"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(page, MediaType.APPLICATION_JSON))

        val recovered = service().provision(seed)

        server.verify()
        assertThat(recovered.status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(recovered.knownWalletId).isEqualTo("wa-recovered-000000000000")
        val stored = singleStoredEvidence()
        assertThat(stored.operation).isEqualTo(NetworkWalletEvidenceOperation.DISCOVER)
        assertThat(stored.body).isEqualTo(page.toByteArray())
        assertThat(stored.hash).isEqualTo(sha256(page.toByteArray()))
    }

    @Test
    fun `알고 있는 ID의 404 응답도 원문 그대로 보관하고 같은 ID로 대기한다`() {
        val created = walletJson("wa-pending-0000000000000000", seed.request.correlationId, address = null)
        expectUserActionSigning()
        server.expect(requestTo("$BASE/wallets")).andRespond(withSuccess(created, MediaType.APPLICATION_JSON))
        val pending = service().provision(seed)
        server.verify()
        server.reset()
        assertThat(pending.status).isEqualTo(NetworkWalletCreationStatus.RECOVERING)
        assertThat(pending.knownWalletId).isEqualTo("wa-pending-0000000000000000")

        val notFound = """{"error":{"message":"Wallet not found"}}"""
        server
            .expect(requestTo("$BASE/wallets/wa-pending-0000000000000000"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(notFound))

        val stillPending = service().provision(seed)

        server.verify()
        assertThat(stillPending.status).isEqualTo(NetworkWalletCreationStatus.RECOVERING)
        assertThat(stillPending.knownWalletId).isEqualTo("wa-pending-0000000000000000")
        assertThat(stillPending.lastReason).isEqualTo("NOT_OBSERVED")
        val records = storedEvidence()
        assertThat(records.map { it.operation }).containsExactly(NetworkWalletEvidenceOperation.CREATE, NetworkWalletEvidenceOperation.READ)
        assertThat(records.last().body).isEqualTo(notFound.toByteArray())
        assertThat(records.last().hash).isEqualTo(sha256(notFound.toByteArray()))
    }

    private fun expectUserActionSigning() {
        server
            .expect(requestTo("$BASE/auth/action/init"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"challenge":"Y2gtNzloaHQtbXJlb2stOGFwOHFtMmVpZWZ0amxhZw","challengeIdentifier":"eyJ0e.fQNA",
                       "allowCredentials":{"key":[{"type":"public-key","id":"$CREDENTIAL_ID"}]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server
            .expect(requestTo("$BASE/auth/action"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"userAction":"eyJ0eX.bzrQakA"}""", MediaType.APPLICATION_JSON))
    }

    /** 명세 Wallet schema의 필수 필드(id·network·signingKey·status·dateCreated·custodial·tags)와 선택 address·externalId. */
    private fun walletJson(
        id: String,
        externalId: String,
        address: String? = "0xaddress",
    ): String =
        """{"id":"$id","network":"$VENDOR_NETWORK"${address?.let { ""","address":"$it"""" } ?: ""},
           "signingKey":{"id":"key-test0-test0-test0test0test0","scheme":"ECDSA","curve":"secp256k1","publicKey":"00"},
           "status":"Active","dateCreated":"2026-09-15T00:00:00.000Z","custodial":true,"externalId":"$externalId","tags":[]}"""

    private fun service() = NetworkWalletProvisioningService(ledger, vendor, evidence, AccountQueryService(accounts), ORIGIN, clock)

    private fun storedPageHashes(): List<String> =
        jdbc.queryForList("SELECT evdc_hash FROM bcm_ntwk_wlt_obs_l ORDER BY page_no", String::class.java).map { checkNotNull(it) }

    private fun evidenceCount(): Int = checkNotNull(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_evdc_l", Int::class.java))

    private fun singleStoredEvidence(): StoredEvidence = storedEvidence().single()

    /** 테스트 소유자 연결로 원문을 직접 읽어 대조한다 — 앱 역할에는 body 열람 권한이 없다(03 V24). */
    private fun storedEvidence(): List<StoredEvidence> =
        jdbc.query(
            "SELECT oprtn_dvcd, body, body_len, encode(sha256(body), 'hex') AS db_hash, body_hash FROM bcm_ntwk_wlt_evdc_l ORDER BY obs_dttm, oprtn_dvcd",
        ) { row, _ ->
            check(row.getString("db_hash") == row.getString("body_hash"))
            StoredEvidence(
                NetworkWalletEvidenceOperation.valueOf(row.getString("oprtn_dvcd")),
                row.getBytes("body"),
                row.getInt("body_len"),
                row.getString("body_hash"),
            )
        }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class StoredEvidence(
        val operation: NetworkWalletEvidenceOperation,
        val body: ByteArray,
        val length: Int,
        val hash: String,
    )

    companion object {
        private const val BASE = "https://baseline.dfns.internal.test"
        private const val CREDENTIAL_ID = "cr-test0-test0-test0test0test0"
        private const val BCM_NETWORK = "ETHEREUM_TEST"
        private const val VENDOR_NETWORK = "EthereumSepolia"
        private val ORIGIN = ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET")

        @JvmStatic
        @DynamicPropertySource
        fun dataset(registry: DynamicPropertyRegistry) = DfnsDatasetTestSupport.register(registry)
    }
}
