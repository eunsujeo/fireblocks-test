package com.whatto.bcm.infra.persistence.account

import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.infra.persistence.account.fixture.LogicalAccountFixture
import com.whatto.bcm.infra.persistence.admin.VaultReconciliationJdbcAdapter
import com.whatto.bcm.infra.persistence.provider.ProviderOriginJdbcAdapter
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
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
@Import(
    LogicalAccountJdbcAdapter::class,
    AccountJdbcAdapter::class,
    ProviderOriginJdbcAdapter::class,
    VaultReconciliationJdbcAdapter::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LogicalAccountPersistenceTest : PersistenceTestSupport() {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var logical: LogicalAccountJdbcAdapter

    @Autowired lateinit var accounts: AccountJdbcAdapter

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var vaults: VaultReconciliationJdbcAdapter

    @BeforeEach
    fun setup() {
        jdbc.update(
            """
            INSERT INTO bcm_prvd_bndg_m VALUES
              (1, 'logical-origin', 'dfns', 'dfns', 'logical-instance', 'logical-org', 'TESTNET',
               '20260914000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanup() {
        jdbc.update("DELETE FROM bcm_vlt_rcnc_item_l WHERE vlt_rcnc_id = 'logical-vault-run'")
        jdbc.update("DELETE FROM bcm_vlt_rcnc_l WHERE vlt_rcnc_id = 'logical-vault-run'")
        jdbc.update("DELETE FROM bcm_acnt_m WHERE ref LIKE 'logical-%'")
        jdbc.update("DELETE FROM bcm_prvd_bndg_m WHERE orgn_id = 'logical-origin'")
    }

    @Test
    fun `vault 없이 등록한 논리 계정은 기존 조회 경로에서 같은 모델로 읽힌다`() {
        val account = LogicalAccountFixture.account()
        assertThat(logical.reserve(account, LogicalAccountFixture.origin())).isEqualTo(account)
        assertThat(accounts.findByAccountId(account.accountId)).isEqualTo(account)
        assertThat(accounts.findByTypeAndRef(account.accountType, account.ref)).isEqualTo(account)
        assertThat(
            logical.reserve(account.copy(accountId = UUID.randomUUID().toString()), LogicalAccountFixture.origin()),
        ).isEqualTo(account)
    }

    @Test
    fun `동시 논리 계정 예약은 동일 유형과 ref에 계정 하나만 만든다`() {
        val account = LogicalAccountFixture.account()
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val results =
                (1..2).map {
                    executor.submit<String> {
                        check(start.await(5, TimeUnit.SECONDS))
                        logical.reserve(account.copy(accountId = UUID.randomUUID().toString()), LogicalAccountFixture.origin()).accountId
                    }
                }
            start.countDown()
            assertThat(results.map { it.get(10, TimeUnit.SECONDS) }.distinct()).hasSize(1)
        }
    }

    @Test
    fun `논리 계정 예약은 호출자 롤백과 독립적으로 커밋된다`() {
        val account = LogicalAccountFixture.account()
        assertThatThrownBy {
            TransactionTemplate(transactionManager).execute {
                logical.reserve(account, LogicalAccountFixture.origin())
                error("simulated caller rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(accounts.findByAccountId(account.accountId)).isEqualTo(account)
    }

    @Test
    fun `Dfns 원천에 vault 계정이나 가짜 vault를 넣을 수 없다`() {
        val account = LogicalAccountFixture.account()
        assertThatThrownBy { accounts.insert(account.copy(model = AccountModel.VAULT, vendorVaultId = "fake-vault")) }
            .isInstanceOf(DataAccessException::class.java)
        logical.reserve(account, LogicalAccountFixture.origin())
        assertThatThrownBy { jdbc.update("UPDATE bcm_acnt_m SET vndr_vlt_id = 'fake-vault' WHERE acnt_id = ?", account.accountId) }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.update("UPDATE bcm_acnt_m SET acnt_mdl = 'VAULT', vndr_vlt_id = 'fake-vault' WHERE acnt_id = ?", account.accountId)
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `원천이 없거나 다르면 논리 계정을 저장하지 않는다`() {
        val account = LogicalAccountFixture.account()
        assertThatThrownBy { logical.reserve(account, LogicalAccountFixture.origin().copy(vendorOrganizationId = "other-org")) }
            .isInstanceOf(IllegalStateException::class.java)
        jdbc.update("DELETE FROM bcm_prvd_bndg_m WHERE orgn_id = 'logical-origin'")
        assertThatThrownBy { logical.reserve(account, LogicalAccountFixture.origin()) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { accounts.insert(account) }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `Fireblocks 원천에서는 기존 vault 등록만 허용한다`() {
        jdbc.update("UPDATE bcm_prvd_bndg_m SET exec_mode = 'fireblocks', prtc_prvd = 'fireblocks' WHERE orgn_id = 'logical-origin'")
        val account = LogicalAccountFixture.account()
        val vault = account.copy(model = AccountModel.VAULT, vendorVaultId = "vault-real")
        assertThat(accounts.insert(vault)).isEqualTo(vault)
        assertThatThrownBy { accounts.insert(LogicalAccountFixture.account()) }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { jdbc.update("UPDATE bcm_acnt_m SET vndr_vlt_id = NULL WHERE acnt_id = ?", vault.accountId) }
            .isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `vault 대사는 논리 계정을 누락이나 가짜 vault로 분류하지 않는다`() {
        logical.reserve(LogicalAccountFixture.account(), LogicalAccountFixture.origin())
        vaults.create(LogicalAccountFixture.vaultRun())
        assertThat(vaults.claim("logical-vault-run", "claim-1", "20260914000000", "20260914000100")).isTrue()
        assertThatThrownBy { vaults.startWithAccountSnapshot("logical-vault-run", "claim-1", "20260914000000") }
            .isInstanceOf(ConflictException::class.java)
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM bcm_vlt_rcnc_item_l WHERE vlt_rcnc_id = 'logical-vault-run'", Int::class.java),
        ).isZero()
    }

    @Test
    fun `V23은 기존 vault 계정과 진행 의도를 보존하고 모델 기본값만 추가한다`() {
        TransactionTemplate(transactionManager).execute { transaction ->
            jdbc.execute("CREATE SCHEMA logical_upgrade_test")
            jdbc.execute("SET LOCAL search_path TO logical_upgrade_test")

            fun apply(name: String) {
                val sql =
                    requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$name"))
                        .bufferedReader()
                        .use { it.readText() }
                jdbc.execute(sql)
            }
            listOf(
                "V1__bcm_core_tables.sql",
                "V20__wallet_provisioning_ledgers.sql",
                "V21__provider_origin_binding.sql",
                "V22__network_wallet_provisioning.sql",
            ).forEach(::apply)
            jdbc.execute(
                """
                INSERT INTO bcm_acnt_m VALUES ('legacy-account', 'CU', 'legacy-ref', 'legacy-vault',
                  '20260913000000', 'SYSTEM', '9999', 'SYSTEM', '9999');
                INSERT INTO bcm_acnt_crtn_l VALUES ('pending-account', 'CU', 'pending-ref', 'CUSTOMER:pending-ref', 'stable-key',
                  '20260913000000', '20260914000000', 'SUBMITTING', 2, NULL,
                  '20260913000000', '20260914000000', 'SYSTEM', '9999', 'SYSTEM', '9999');
                """.trimIndent(),
            )
            val before = jdbc.queryForMap("SELECT * FROM bcm_acnt_m")
            val pending = jdbc.queryForMap("SELECT * FROM bcm_acnt_crtn_l")
            apply("V23__account_models.sql")
            val after = jdbc.queryForMap("SELECT * FROM bcm_acnt_m")
            assertThat(after - "acnt_mdl").isEqualTo(before)
            assertThat(after["acnt_mdl"]).isEqualTo("VAULT")
            assertThat(jdbc.queryForMap("SELECT * FROM bcm_acnt_crtn_l")).isEqualTo(pending)
            transaction.setRollbackOnly()
        }
    }
}
