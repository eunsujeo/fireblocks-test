package com.whatto.bcm.app.api.wallet

import com.whatto.bcm.app.api.wallet.fixture.InternalNetworkWalletVendor
import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.LogicalAccountService
import com.whatto.bcm.app.application.wallet.NetworkWalletProvisioningService
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.wallet.NetworkWalletCreationIntent
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.domain.wallet.NetworkWalletCreationStatus
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceOperation
import com.whatto.bcm.domain.wallet.NetworkWalletProvisioningRepository
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
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
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 내부 생성 서비스 + 실제 PostgreSQL 원장·증적 저장소 결합 검증.
 * 벤더 포트는 내부 대역이며 Dfns HTTP 어댑터·Baseline 수용은 별도다. 기본 실행 빈/공개 API에는 여전히 연결하지 않는다.
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
class NetworkWalletProvisioningRecoveryIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var ledger: NetworkWalletProvisioningJdbcAdapter

    @Autowired lateinit var evidence: NetworkWalletEvidenceJdbcAdapter

    @Autowired lateinit var logicalAccounts: LogicalAccountJdbcAdapter

    @Autowired lateinit var accounts: AccountJdbcAdapter

    private val clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC)
    private val vendor = InternalNetworkWalletVendor()
    private lateinit var seed: NetworkWalletCreationSeed

    @BeforeEach
    fun setup() {
        val account = LogicalAccountService(logicalAccounts, ORIGIN, clock).create(AccountType.CUSTOMER, "recovery-${UUID.randomUUID()}")
        val intentId = UUID.randomUUID().toString()
        seed =
            NetworkWalletCreationSeed(
                intentId,
                NetworkWalletCreationRequest(NetworkWalletScope(ORIGIN, account.accountId, "ETHEREUM_TEST"), "corr-$intentId"),
                NetworkWalletSubmissionSpec("a".repeat(64), "internal-v1", "internal-network"),
            )
    }

    @AfterEach
    fun cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_evidence_insert ON bcm_ntwk_wlt_evdc_l")
        jdbc.execute("DROP TRIGGER IF EXISTS reject_wallet_completion ON bcm_ntwk_wlt_crtn_l")
        jdbc.execute("DROP FUNCTION IF EXISTS reject_evidence_insert()")
        jdbc.execute("DROP FUNCTION IF EXISTS reject_wallet_completion()")
        jdbc.execute("ALTER TABLE bcm_ntwk_wlt_evdc_l DISABLE TRIGGER trg_bcm_ntwk_wlt_evdc_append_only")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_evdc_l")
        jdbc.execute("ALTER TABLE bcm_ntwk_wlt_evdc_l ENABLE TRIGGER trg_bcm_ntwk_wlt_evdc_append_only")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_m")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_obs_item_l")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_obs_l")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_crtn_l")
        jdbc.update("DELETE FROM bcm_acnt_m WHERE ref LIKE 'recovery-%'")
    }

    @Test
    fun `최초 생성은 원문 증적을 DB에 보관한 뒤 같은 해시로 완료를 기록한다`() {
        val completed = service().provision(seed)

        assertThat(completed.status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(vendor.createCalls.get()).isEqualTo(1)
        assertThat(ledger.findWallet(seed.request.scope)?.vendorWalletId).isEqualTo(completed.knownWalletId)
        val pages = storedPages()
        assertThat(pages).hasSize(1)
        val record = requireNotNull(evidence.find(pages.single().reference))
        assertThat(record.hash).isEqualTo(pages.single().hash)
        assertThat(record.operation).isEqualTo(NetworkWalletEvidenceOperation.CREATE)
        assertThat(record.intentId).isEqualTo(completed.intentId)
        assertThat(record.length).isGreaterThan(0)
        assertThat(evidenceCount()).isEqualTo(1)
    }

    @Test
    fun `응답 유실 뒤 재시작한 서비스는 새 POST 없이 조회 증적으로 회수한다`() {
        vendor.loseNextCreateResponse = true
        assertThatThrownBy { service().provision(seed) }.isInstanceOf(IllegalStateException::class.java)
        val lost = requireNotNull(ledger.find(seed.request.scope))
        assertThat(lost.status).isEqualTo(NetworkWalletCreationStatus.SUBMITTING)
        assertThat(vendor.walletCount()).isEqualTo(1)
        assertThat(evidenceCount()).isZero()
        assertThat(storedPages()).isEmpty()

        val recovered = service().provision(seed)

        assertThat(recovered.status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(vendor.createCalls.get()).isEqualTo(1)
        assertThat(vendor.candidateCalls.get()).isEqualTo(1)
        assertThat(vendor.walletCount()).isEqualTo(1)
        val record = requireNotNull(evidence.find(storedPages().single().reference))
        assertThat(record.operation).isEqualTo(NetworkWalletEvidenceOperation.DISCOVER)
        assertThat(record.hash).isEqualTo(storedPages().single().hash)
        assertThat(ledger.findWallet(seed.request.scope)?.vendorWalletId).isEqualTo(recovered.knownWalletId)
    }

    @Test
    fun `증적 저장 실패는 전파되고 이후 요청은 재생성 없이 조회만 재개한다`() {
        jdbc.execute(
            """
            CREATE FUNCTION reject_evidence_insert() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'simulated evidence store failure'; END $$
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER reject_evidence_insert BEFORE INSERT ON bcm_ntwk_wlt_evdc_l FOR EACH ROW EXECUTE FUNCTION reject_evidence_insert()",
        )

        assertThatThrownBy { service().provision(seed) }.isInstanceOf(DataAccessException::class.java)

        val submitted = requireNotNull(ledger.find(seed.request.scope))
        assertThat(submitted.status).isEqualTo(NetworkWalletCreationStatus.SUBMITTING)
        assertThat(submitted.preparedAt).isNotNull()
        assertThat(vendor.walletCount()).isEqualTo(1)
        assertThat(storedPages()).isEmpty()
        assertThat(evidenceCount()).isZero()

        jdbc.execute("DROP TRIGGER reject_evidence_insert ON bcm_ntwk_wlt_evdc_l")
        val recovered = service().provision(seed)

        assertThat(recovered.status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(vendor.createCalls.get()).isEqualTo(1)
        assertThat(singleStoredEvidence().operation).isEqualTo(NetworkWalletEvidenceOperation.DISCOVER)
    }

    @Test
    fun `원장 저장 실패에도 보관한 증적은 남고 페이지와 연결은 롤백된 뒤 조회로 이어진다`() {
        jdbc.execute(
            """
            CREATE FUNCTION reject_wallet_completion() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF NEW.crtn_stcd = 'COMPLETED' THEN RAISE EXCEPTION 'simulated ledger failure'; END IF;
              RETURN NEW;
            END $$
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER reject_wallet_completion BEFORE UPDATE ON bcm_ntwk_wlt_crtn_l FOR EACH ROW EXECUTE FUNCTION reject_wallet_completion()",
        )

        assertThatThrownBy { service().provision(seed) }.isInstanceOf(DataAccessException::class.java)

        val recovering = requireNotNull(ledger.find(seed.request.scope))
        assertThat(recovering.status).isEqualTo(NetworkWalletCreationStatus.RECOVERING)
        assertThat(recovering.knownWalletId).isNull()
        assertThat(storedPages()).isEmpty()
        assertThat(ledger.findWallet(seed.request.scope)).isNull()
        assertThat(evidenceCount()).isEqualTo(1)
        val retained = evidenceOperations()
        assertThat(retained).containsExactly(NetworkWalletEvidenceOperation.CREATE)

        jdbc.execute("DROP TRIGGER reject_wallet_completion ON bcm_ntwk_wlt_crtn_l")
        val recovered = service().provision(seed)

        assertThat(recovered.status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(vendor.createCalls.get()).isEqualTo(1)
        assertThat(evidenceOperations())
            .containsExactlyInAnyOrder(NetworkWalletEvidenceOperation.CREATE, NetworkWalletEvidenceOperation.DISCOVER)
        assertThat(singleStoredEvidence().operation).isEqualTo(NetworkWalletEvidenceOperation.DISCOVER)
    }

    @Test
    fun `같은 PREPARED 의도를 읽은 두 요청 중 하나만 최초 제출 권한을 얻어 create를 호출한다`() {
        // 두 요청이 모두 PREPARED를 읽은 뒤에만 claim으로 진행하도록 예약 직후를 동기화한다 — 실행 순서에 의존하지 않는다.
        val bothReserved = CyclicBarrier(2)
        val ledgerAfterBarrier =
            object : NetworkWalletProvisioningRepository by ledger {
                override fun reserve(
                    seed: NetworkWalletCreationSeed,
                    now: String,
                ) = ledger.reserve(seed, now).also { bothReserved.await(5, TimeUnit.SECONDS) }
            }
        val results =
            Executors.newFixedThreadPool(2).use { executor ->
                (1..2)
                    .map { executor.submit<NetworkWalletCreationIntent> { service(ledgerAfterBarrier).provision(seed) } }
                    .map { it.get(15, TimeUnit.SECONDS) }
            }

        assertThat(vendor.createCalls.get()).isEqualTo(1)
        assertThat(results.map { it.intentId }.toSet()).hasSize(1)
        assertThat(results.map { it.status }).contains(NetworkWalletCreationStatus.COMPLETED)
        assertThat(evidenceOperations()).containsExactly(NetworkWalletEvidenceOperation.CREATE)
        assertThat(service().provision(seed).status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(vendor.createCalls.get()).isEqualTo(1)
        assertThat(vendor.candidateCalls.get()).isZero()
    }

    @Test
    fun `POST 응답 대기 중 다른 요청이 조회로 완료하면 늦은 생성 응답은 증적만 남기고 원장을 덮어쓰지 못한다`() {
        vendor.createResponseGate = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val slowPost = executor.submit<NetworkWalletCreationIntent> { service().provision(seed) }
            check(vendor.createEntered.await(5, TimeUnit.SECONDS))
            assertThat(ledger.find(seed.request.scope)?.status).isEqualTo(NetworkWalletCreationStatus.SUBMITTING)

            val recovered = service().provision(seed)

            assertThat(recovered.status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
            assertThat(vendor.candidateCalls.get()).isEqualTo(1)
            checkNotNull(vendor.createResponseGate).countDown()
            assertThatThrownBy { slowPost.get(10, TimeUnit.SECONDS) }.hasCauseInstanceOf(ConflictException::class.java)
        } finally {
            executor.shutdownNow()
        }

        assertThat(vendor.createCalls.get()).isEqualTo(1)
        assertThat(evidenceOperations())
            .containsExactlyInAnyOrder(NetworkWalletEvidenceOperation.CREATE, NetworkWalletEvidenceOperation.DISCOVER)
        assertThat(storedPages()).hasSize(1)
        assertThat(singleStoredEvidence().operation).isEqualTo(NetworkWalletEvidenceOperation.DISCOVER)
        val stored = requireNotNull(ledger.find(seed.request.scope))
        assertThat(stored.status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(ledger.findWallet(seed.request.scope)?.vendorWalletId).isEqualTo(stored.knownWalletId)
        assertThat(service().provision(seed)).isEqualTo(stored)
        assertThat(vendor.createCalls.get()).isEqualTo(1)
    }

    @Test
    fun `완료된 의도의 재요청은 외부 호출과 증적 추가 없이 저장 상태를 돌려준다`() {
        val completed = service().provision(seed)
        val again = service().provision(seed)

        assertThat(again).isEqualTo(completed)
        assertThat(vendor.createCalls.get()).isEqualTo(1)
        assertThat(vendor.readCalls.get()).isZero()
        assertThat(vendor.candidateCalls.get()).isZero()
        assertThat(evidenceCount()).isEqualTo(1)
    }

    /** 새 인스턴스는 프로세스 재시작을 뜻한다 — 상태는 DB에만 있다. */
    private fun service(repository: NetworkWalletProvisioningRepository = ledger) =
        NetworkWalletProvisioningService(repository, vendor, evidence, AccountQueryService(accounts), ORIGIN, clock)

    private fun storedPages(): List<StoredPage> =
        jdbc.query("SELECT evdc_ref, evdc_hash FROM bcm_ntwk_wlt_obs_l ORDER BY page_no") { row, _ ->
            StoredPage(row.getString("evdc_ref"), row.getString("evdc_hash"))
        }

    private fun singleStoredEvidence() = requireNotNull(evidence.find(storedPages().single().reference))

    private fun evidenceCount(): Int = checkNotNull(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_evdc_l", Int::class.java))

    private fun evidenceOperations(): List<NetworkWalletEvidenceOperation> =
        jdbc
            .queryForList("SELECT oprtn_dvcd FROM bcm_ntwk_wlt_evdc_l ORDER BY obs_dttm", String::class.java)
            .map { NetworkWalletEvidenceOperation.valueOf(checkNotNull(it)) }

    private data class StoredPage(
        val reference: String,
        val hash: String,
    )

    companion object {
        private val ORIGIN = ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET")

        @JvmStatic
        @DynamicPropertySource
        fun dataset(registry: DynamicPropertyRegistry) = DfnsDatasetTestSupport.register(registry)
    }
}
