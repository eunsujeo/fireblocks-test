package com.whatto.bcm.infra.persistence.wallet

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.domain.wallet.NetworkWalletCreationStatus
import com.whatto.bcm.infra.persistence.provider.ProviderOriginJdbcAdapter
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import com.whatto.bcm.infra.persistence.wallet.fixture.NetworkWalletLedgerFixture
import com.whatto.bcm.infra.persistence.wallet.fixture.NetworkWalletLedgerFixture.NOW
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJdbcTest
@Import(NetworkWalletProvisioningJdbcAdapter::class, ProviderOriginJdbcAdapter::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NetworkWalletPersistenceTest : PersistenceTestSupport() {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var repository: NetworkWalletProvisioningJdbcAdapter

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private lateinit var seed: NetworkWalletCreationSeed

    @BeforeEach
    fun setup() {
        seed = NetworkWalletLedgerFixture.seed()
        jdbc.update(
            """
            INSERT INTO bcm_prvd_bndg_m VALUES
              (1, 'wallet-ledger-origin', 'fireblocks', 'fireblocks', 'test-instance', 'test-org',
               'TESTNET', ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            NOW,
        )
        insertAccount(seed)
    }

    @AfterEach
    fun cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_wallet_completion ON bcm_ntwk_wlt_crtn_l")
        jdbc.execute("DROP FUNCTION IF EXISTS reject_wallet_completion()")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_m")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_obs_item_l")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_obs_l")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_crtn_l")
        jdbc.update("DELETE FROM bcm_acnt_m WHERE ref LIKE 'wallet-ledger-%'")
        jdbc.update("DELETE FROM bcm_prvd_bndg_m WHERE orgn_id = 'wallet-ledger-origin'")
    }

    @Test
    fun `같은 네트워크의 요청은 최초 의도와 상관관계를 재사용한다`() {
        val first = repository.reserve(seed, NOW)
        val raced = seed.copy(intentId = UUID.randomUUID().toString(), request = seed.request.copy(correlationId = "raced-correlation"))
        assertThat(repository.reserve(raced, NOW)).isEqualTo(first)
        assertThatThrownBy { repository.reserve(raced.copy(submission = seed.submission.copy(requestHash = "c".repeat(64))), NOW) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(repository.find(seed.request.scope)).isEqualTo(first)
    }

    @Test
    fun `동시 예약과 제출 권한 경쟁에서 한 요청만 최초 제출을 획득한다`() {
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val results =
                (1..2).map {
                    executor.submit<Boolean> {
                        check(start.await(5, TimeUnit.SECONDS))
                        val reserved = repository.reserve(seed, NOW)
                        repository.claimSubmission(reserved.request.scope, reserved.revision, NOW) != null
                    }
                }
            start.countDown()
            assertThat(results.count { it.get(10, TimeUnit.SECONDS) }).isEqualTo(1)
        }
        assertThat(repository.find(seed.request.scope)?.status).isEqualTo(NetworkWalletCreationStatus.SUBMITTING)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_crtn_l", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `호출자 롤백과 장시간 경과에도 이미 커밋한 최초 제출 권한은 되살아나지 않는다`() {
        assertThatThrownBy {
            TransactionTemplate(transactionManager).execute {
                val reserved = repository.reserve(seed, NOW)
                assertThat(repository.claimSubmission(seed.request.scope, reserved.revision, NOW)).isNotNull()
                error("simulated caller failure before POST")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        val stored = requireNotNull(repository.find(seed.request.scope))
        assertThat(stored.preparedAt).isEqualTo(NOW)
        assertThat(repository.claimSubmission(seed.request.scope, stored.revision, "20270914000000")).isNull()
    }

    @Test
    fun `완료된 빈 조회는 미관찰로 남기고 제출 권한을 재발급하지 않는다`() {
        val scan = startScan()
        val stored = repository.recordPage(seed.request.scope, scan.revision, NetworkWalletLedgerFixture.page(), NOW)
        assertThat(stored.status).isEqualTo(NetworkWalletCreationStatus.RECOVERING)
        assertThat(stored.lastReason).isEqualTo("NOT_OBSERVED")
        assertThat(stored.scanComplete).isTrue()
        assertThat(repository.claimSubmission(seed.request.scope, stored.revision, NOW)).isNull()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_obs_l", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `저장한 cursor로 이어 읽고 겹친 관찰은 지갑 하나로 원자 완료한다`() {
        val scan = startScan()
        val wallet = NetworkWalletLedgerFixture.wallet(seed.request)
        val page = NetworkWalletLedgerFixture.page(next = "next-page", candidates = listOf(wallet))
        val first = repository.recordPage(seed.request.scope, scan.revision, page, NOW)
        assertThat(first.status).isEqualTo(NetworkWalletCreationStatus.RECOVERING)
        val resumed = requireNotNull(repository.find(seed.request.scope))
        assertThat(resumed.nextCursor).isEqualTo("next-page")
        val complete =
            repository.recordPage(
                seed.request.scope,
                resumed.revision,
                NetworkWalletLedgerFixture.page(cursor = resumed.nextCursor, candidates = listOf(wallet)),
                NOW,
            )
        assertThat(complete.status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(complete.knownWalletId).isEqualTo(wallet.vendorWalletId)
        assertThat(repository.findWallet(seed.request.scope)).isEqualTo(wallet)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_obs_l", Int::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_obs_item_l", Int::class.java)).isEqualTo(2)
        assertThatThrownBy { repository.recordPage(seed.request.scope, scan.revision, page, NOW) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(repository.findWallet(seed.request.scope)).isEqualTo(wallet)
    }

    @Test
    fun `지갑 주소 소유권은 계정·자산을 묻지 않고 네트워크 안에서만 찾는다`() {
        // 제출은 그 자산의 주소 발급을 요구하지 않는다 — 발급 기록으로 물으면 우리 지갑을 못 알아본다(계약13 "내부이체").
        val scan = startScan()
        val wallet = NetworkWalletLedgerFixture.wallet(seed.request)
        repository.recordPage(seed.request.scope, scan.revision, NetworkWalletLedgerFixture.page(candidates = listOf(wallet)), NOW)
        val origin = seed.request.scope.origin
        val network = seed.request.scope.network
        val address = requireNotNull(wallet.address)

        assertThat(repository.ownsWalletAddress(origin, network, address)).isTrue()
        // EVM 주소는 대소문자에 정보가 없다 — 벤더가 사건과 지갑 응답에서 다른 표기를 줘도 같은 주소로 찾아야 한다.
        assertThat(repository.ownsWalletAddress(origin, network, address.uppercase())).isTrue()
        // 네트워크가 다르면 같은 문자열이라도 우리 지갑이 아니다.
        assertThat(repository.ownsWalletAddress(origin, "OTHER_NETWORK", address)).isFalse()
        assertThat(repository.ownsWalletAddress(origin, network, "address-not-ours")).isFalse()
    }

    @Test
    fun `cursor 반복은 페이지 저장과 cursor 전진 없이 거절한다`() {
        val scan = startScan()
        val first = repository.recordPage(seed.request.scope, scan.revision, NetworkWalletLedgerFixture.page(next = "next-page"), NOW)
        assertThatThrownBy {
            repository.recordPage(
                seed.request.scope,
                first.revision,
                NetworkWalletLedgerFixture.page(cursor = "next-page", next = "next-page"),
                NOW,
            )
        }.isInstanceOf(ConflictException::class.java)
        assertThat(repository.find(seed.request.scope)).isEqualTo(first)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_obs_l", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `새 scan이 시작되면 이전 worker와 후보를 섞지 않는다`() {
        val scan = startScan()
        val first =
            repository.recordPage(
                seed.request.scope,
                scan.revision,
                NetworkWalletLedgerFixture.page(next = "next-page", candidates = listOf(NetworkWalletLedgerFixture.wallet(seed.request))),
                NOW,
            )
        val restarted = repository.startRecovery(seed.request.scope, first.revision, "scan-2", NOW)
        assertThatThrownBy {
            repository.recordPage(seed.request.scope, first.revision, NetworkWalletLedgerFixture.page(cursor = "next-page"), NOW)
        }.isInstanceOf(ConflictException::class.java)
        val completedScan =
            repository.recordPage(seed.request.scope, restarted.revision, NetworkWalletLedgerFixture.page(scanId = "scan-2"), NOW)
        assertThat(completedScan.lastReason).isEqualTo("NOT_OBSERVED")
        assertThat(repository.findWallet(seed.request.scope)).isNull()
    }

    @Test
    fun `주소 준비를 기다리는 동안 저장된 wallet ID를 다른 ID로 교체하지 않는다`() {
        val scan = startScan()
        val wallet = NetworkWalletLedgerFixture.wallet(seed.request)
        val waiting =
            repository.recordPage(
                seed.request.scope,
                scan.revision,
                NetworkWalletLedgerFixture.page(candidates = listOf(wallet.copy(address = null))),
                NOW,
            )
        assertThat(waiting.knownWalletId).isEqualTo(wallet.vendorWalletId)
        assertThat(waiting.lastReason).isEqualTo("ADDRESS_NOT_READY")
        val next = repository.startRecovery(seed.request.scope, waiting.revision, "scan-2", NOW)
        val conflict =
            repository.recordPage(
                seed.request.scope,
                next.revision,
                NetworkWalletLedgerFixture.page(scanId = "scan-2", candidates = listOf(wallet.copy(vendorWalletId = "other-wallet"))),
                NOW,
            )
        assertThat(conflict.status).isEqualTo(NetworkWalletCreationStatus.CONFLICT)
        assertThat(conflict.knownWalletId).isEqualTo(wallet.vendorWalletId)
        assertThat(repository.findWallet(seed.request.scope)).isNull()
    }

    @Test
    fun `미완료 조회에서 검증된 wallet ID도 새 scan에서 다른 ID로 교체하지 않는다`() {
        val scan = startScan()
        val wallet = NetworkWalletLedgerFixture.wallet(seed.request)
        val partial =
            repository.recordPage(
                seed.request.scope,
                scan.revision,
                NetworkWalletLedgerFixture.page(next = "next-page", candidates = listOf(wallet)),
                NOW,
            )
        assertThat(partial.knownWalletId).isEqualTo(wallet.vendorWalletId)
        val restarted = repository.startRecovery(seed.request.scope, partial.revision, "scan-2", NOW)
        val conflict =
            repository.recordPage(
                seed.request.scope,
                restarted.revision,
                NetworkWalletLedgerFixture.page(scanId = "scan-2", candidates = listOf(wallet.copy(vendorWalletId = "other-wallet"))),
                NOW,
            )
        assertThat(conflict.lastReason).isEqualTo("KNOWN_WALLET_MISMATCH")
        assertThat(conflict.knownWalletId).isEqualTo(wallet.vendorWalletId)
        assertThat(repository.findWallet(seed.request.scope)).isNull()
    }

    @Test
    fun `후보의 원천 불일치는 증적으로 보존하고 자동 연결을 중단한다`() {
        val scan = startScan()
        val wallet = NetworkWalletLedgerFixture.wallet(seed.request)
        val conflict =
            repository.recordPage(
                seed.request.scope,
                scan.revision,
                NetworkWalletLedgerFixture.page(
                    candidates = listOf(wallet.copy(origin = wallet.origin.copy(vendorOrganizationId = "other-org"))),
                ),
                NOW,
            )
        assertThat(conflict.status).isEqualTo(NetworkWalletCreationStatus.CONFLICT)
        assertThat(conflict.lastReason).isEqualTo("ORIGIN_MISMATCH")
        assertThat(jdbc.queryForObject("SELECT vndr_org_id FROM bcm_ntwk_wlt_obs_item_l", String::class.java)).isEqualTo("other-org")
        assertThatThrownBy { repository.startRecovery(seed.request.scope, conflict.revision, "scan-2", NOW) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `다른 계정에 연결된 wallet ID는 중복 매핑 대신 충돌로 남긴다`() {
        val scan = startScan()
        val wallet = NetworkWalletLedgerFixture.wallet(seed.request)
        repository.recordPage(seed.request.scope, scan.revision, NetworkWalletLedgerFixture.page(candidates = listOf(wallet)), NOW)
        val other = NetworkWalletLedgerFixture.seed()
        insertAccount(other)
        val reserved = repository.reserve(other, NOW)
        val submitted = requireNotNull(repository.claimSubmission(other.request.scope, reserved.revision, NOW))
        val otherScan = repository.startRecovery(other.request.scope, submitted.revision, "other-scan", NOW)
        val conflict =
            repository.recordPage(
                other.request.scope,
                otherScan.revision,
                NetworkWalletLedgerFixture.page(
                    scanId = "other-scan",
                    candidates = listOf(NetworkWalletLedgerFixture.wallet(other.request)),
                ),
                NOW,
            )
        assertThat(conflict.lastReason).isEqualTo("WALLET_ALREADY_BOUND")
        assertThat(conflict.status).isEqualTo(NetworkWalletCreationStatus.CONFLICT)
        assertThat(repository.findWallet(other.request.scope)).isNull()
        assertThat(repository.findWallet(seed.request.scope)).isEqualTo(wallet)
    }

    @Test
    fun `완료 갱신 실패는 증적과 연결까지 롤백하되 제출 이력은 보존한다`() {
        val scan = startScan()
        jdbc.execute(
            """
            CREATE FUNCTION reject_wallet_completion() RETURNS trigger LANGUAGE plpgsql AS '
            BEGIN
              IF NEW.crtn_stcd = ''COMPLETED'' THEN RAISE EXCEPTION ''simulated completion failure''; END IF;
              RETURN NEW;
            END';
            CREATE TRIGGER reject_wallet_completion BEFORE UPDATE ON bcm_ntwk_wlt_crtn_l
              FOR EACH ROW EXECUTE FUNCTION reject_wallet_completion();
            """.trimIndent(),
        )
        assertThatThrownBy {
            repository.recordPage(
                seed.request.scope,
                scan.revision,
                NetworkWalletLedgerFixture.page(candidates = listOf(NetworkWalletLedgerFixture.wallet(seed.request))),
                NOW,
            )
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(repository.find(seed.request.scope)).isEqualTo(scan)
        assertThat(repository.findWallet(seed.request.scope)).isNull()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_obs_l", Int::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_obs_item_l", Int::class.java)).isZero()
        assertThat(repository.claimSubmission(seed.request.scope, scan.revision, NOW)).isNull()
    }

    @Test
    fun `설정 원천 불일치와 없는 계정은 예약할 수 없다`() {
        val wrong =
            seed.copy(
                request =
                    seed.request.copy(
                        scope =
                            seed.request.scope.copy(
                                origin =
                                    seed.request.scope.origin
                                        .copy(chainMode = "MAINNET"),
                            ),
                    ),
            )
        assertThatThrownBy { repository.reserve(wrong, NOW) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { repository.reserve(NetworkWalletLedgerFixture.seed(), NOW) }.isInstanceOf(DataAccessException::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_crtn_l", Int::class.java)).isZero()
    }

    @Test
    fun `아직 제출하지 않은 의도는 조회 결과로 완료할 수 없다`() {
        val prepared = repository.reserve(seed, NOW)
        assertThatThrownBy { repository.startRecovery(seed.request.scope, prepared.revision, "scan-1", NOW) }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { repository.recordPage(seed.request.scope, prepared.revision, NetworkWalletLedgerFixture.page(), NOW) }
            .isInstanceOf(ConflictException::class.java)
    }

    private fun startScan() =
        repository.reserve(seed, NOW).let { reserved ->
            val submitted = requireNotNull(repository.claimSubmission(seed.request.scope, reserved.revision, NOW))
            repository.startRecovery(seed.request.scope, submitted.revision, "scan-1", NOW)
        }

    @Test
    fun `원장 PK와 FK 및 상태 감사 컬럼을 DB에서 강제한다`() {
        repository.reserve(seed, NOW)
        listOf("bcm_ntwk_wlt_crtn_l", "bcm_ntwk_wlt_obs_l", "bcm_ntwk_wlt_obs_item_l", "bcm_ntwk_wlt_m").forEach { table ->
            assertThat(
                jdbc.queryForList(
                    "SELECT constraint_type FROM information_schema.table_constraints WHERE table_name = ? AND table_schema = 'public'",
                    String::class.java,
                    table,
                ),
            ).contains("PRIMARY KEY", "FOREIGN KEY", "CHECK")
            assertThat(
                jdbc.queryForList(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = ? AND table_schema = 'public' AND is_nullable = 'NO'
                    """.trimIndent(),
                    String::class.java,
                    table,
                ),
            ).contains("frst_reg_empno", "frst_reg_brcd", "last_chng_empno", "last_chng_brcd")
        }
        listOf(
            "UPDATE bcm_ntwk_wlt_crtn_l SET rvsn = -1",
            "UPDATE bcm_ntwk_wlt_crtn_l SET crtn_stcd = 'COMPLETED'",
            "UPDATE bcm_ntwk_wlt_crtn_l SET post_prep_dttm = '20260914000000'",
            "UPDATE bcm_ntwk_wlt_crtn_l SET req_hash = 'invalid'",
        ).forEach { sql -> assertThatThrownBy { jdbc.update(sql) }.isInstanceOf(DataAccessException::class.java) }
        val other = NetworkWalletLedgerFixture.seed()
        insertAccount(other)
        assertThatThrownBy { repository.reserve(other.copy(request = other.request.copy(correlationId = seed.request.correlationId)), NOW) }
            .isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `V22 적용은 기존 계정과 미완료 vault 의도를 변경하지 않는다`() {
        TransactionTemplate(transactionManager).execute { transaction ->
            jdbc.execute("CREATE SCHEMA wallet_upgrade_test")
            jdbc.execute("SET LOCAL search_path TO wallet_upgrade_test")

            fun apply(name: String) {
                val sql =
                    requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$name"))
                        .bufferedReader()
                        .use { it.readText() }
                jdbc.execute(sql)
            }
            apply("V1__bcm_core_tables.sql")
            apply("V20__wallet_provisioning_ledgers.sql")
            apply("V21__provider_origin_binding.sql")
            insertAccount(seed)
            jdbc.execute(
                """
                INSERT INTO bcm_acnt_crtn_l VALUES (
                  'legacy-pending', 'CU', 'legacy-ref', 'CUSTOMER:legacy-ref', 'stable-key',
                  '20260913000000', '20260914000000', 'SUBMITTING', 2, NULL,
                  '20260913000000', '20260914000000', 'SYSTEM', '9999', 'SYSTEM', '9999'
                )
                """.trimIndent(),
            )
            val account = jdbc.queryForMap("SELECT * FROM bcm_acnt_m")
            val pending = jdbc.queryForMap("SELECT * FROM bcm_acnt_crtn_l")
            apply("V22__network_wallet_provisioning.sql")
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_acnt_m")).isEqualTo(account)
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_acnt_crtn_l")).isEqualTo(pending)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_crtn_l", Int::class.java)).isZero()
            transaction.setRollbackOnly()
        }
    }

    private fun insertAccount(value: NetworkWalletCreationSeed) {
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m VALUES (?, 'CU', ?, ?, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            value.request.scope.accountId,
            "wallet-ledger-${value.intentId}",
            "vault-${value.intentId}",
            NOW,
        )
    }
}
