package com.whatto.bcm.infra.persistence.wallet

import com.whatto.bcm.domain.wallet.NetworkWalletCreationIntent
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceOperation
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

@DataJdbcTest
@Import(NetworkWalletEvidenceJdbcAdapter::class, NetworkWalletProvisioningJdbcAdapter::class, ProviderOriginJdbcAdapter::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NetworkWalletEvidencePersistenceTest : PersistenceTestSupport() {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var evidence: NetworkWalletEvidenceJdbcAdapter

    @Autowired lateinit var intents: NetworkWalletProvisioningJdbcAdapter

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private lateinit var seed: NetworkWalletCreationSeed
    private lateinit var intent: NetworkWalletCreationIntent

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
        jdbc.update(
            "INSERT INTO bcm_acnt_m VALUES (?, 'CU', ?, ?, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')",
            seed.request.scope.accountId,
            "wallet-ledger-${seed.intentId}",
            "vault-${seed.intentId}",
            NOW,
        )
        intent = intents.reserve(seed, NOW)
    }

    @AfterEach
    fun cleanup() {
        // append-only 원장은 테스트 격리를 위해서만 trigger를 잠시 끄고 비운다. 운영 절차가 아니다.
        jdbc.execute("ALTER TABLE bcm_ntwk_wlt_evdc_l DISABLE TRIGGER trg_bcm_ntwk_wlt_evdc_append_only")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_evdc_l")
        jdbc.execute("ALTER TABLE bcm_ntwk_wlt_evdc_l ENABLE TRIGGER trg_bcm_ntwk_wlt_evdc_append_only")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_crtn_l")
        jdbc.update("DELETE FROM bcm_acnt_m WHERE ref LIKE 'wallet-ledger-%'")
        jdbc.update("DELETE FROM bcm_prvd_bndg_m WHERE orgn_id = 'wallet-ledger-origin'")
    }

    @Test
    fun `원문 바이트를 그대로 보관하고 DB가 계산한 해시와 길이를 참조로 조회한다`() {
        val body = NetworkWalletLedgerFixture.body()
        val context = NetworkWalletLedgerFixture.context(intent, NetworkWalletEvidenceOperation.DISCOVER, "cursor-1", "wallet-1")

        val stored = evidence.store(context, body)

        assertThat(stored.hash).isEqualTo(NetworkWalletLedgerFixture.sha256(body))
        val record = requireNotNull(evidence.find(stored.reference))
        assertThat(record.reference).isEqualTo(stored.reference)
        assertThat(record.hash).isEqualTo(stored.hash)
        assertThat(record.length).isEqualTo(body.size)
        assertThat(record.intentId).isEqualTo(intent.intentId)
        assertThat(record.originId).isEqualTo(seed.request.scope.origin.originId)
        assertThat(record.accountId).isEqualTo(seed.request.scope.accountId)
        assertThat(record.network).isEqualTo(seed.request.scope.network)
        assertThat(record.correlationId).isEqualTo(seed.request.correlationId)
        assertThat(record.requestHash).isEqualTo(seed.submission.requestHash)
        assertThat(record.operation).isEqualTo(NetworkWalletEvidenceOperation.DISCOVER)
        assertThat(record.cursor).isEqualTo("cursor-1")
        assertThat(record.knownWalletId).isEqualTo("wallet-1")
        assertThat(record.observedAt).isEqualTo(NOW)
        // 감사 역할의 원문 열람에 해당하는 직접 조회 — 앱 어댑터는 body를 반환하지 않는다.
        assertThat(jdbc.queryForObject("SELECT body FROM bcm_ntwk_wlt_evdc_l", ByteArray::class.java)).isEqualTo(body)
    }

    @Test
    fun `빈 응답 본문도 보관하고 같은 해시 규칙을 적용한다`() {
        val stored = evidence.store(NetworkWalletLedgerFixture.context(intent, NetworkWalletEvidenceOperation.READ), ByteArray(0))
        assertThat(stored.hash).isEqualTo(NetworkWalletLedgerFixture.sha256(ByteArray(0)))
        assertThat(requireNotNull(evidence.find(stored.reference)).length).isZero()
    }

    @Test
    fun `보관한 증적은 수정하거나 삭제할 수 없다`() {
        val body = NetworkWalletLedgerFixture.body()
        val stored = evidence.store(NetworkWalletLedgerFixture.context(intent), body)

        assertThatThrownBy { jdbc.update("UPDATE bcm_ntwk_wlt_evdc_l SET obs_dttm = '20270101000000'") }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { jdbc.update("DELETE FROM bcm_ntwk_wlt_evdc_l") }.isInstanceOf(DataAccessException::class.java)
        assertThat(requireNotNull(evidence.find(stored.reference)).observedAt).isEqualTo(NOW)
        assertThat(jdbc.queryForObject("SELECT body FROM bcm_ntwk_wlt_evdc_l", ByteArray::class.java)).isEqualTo(body)
    }

    @Test
    fun `본문과 다른 해시나 길이를 가진 행은 DB가 거절한다`() {
        val tampered =
            listOf(
                "'" + "0".repeat(64) + "'" to "octet_length(body)",
                "encode(sha256(body), 'hex')" to "octet_length(body) + 1",
            )
        tampered.forEach { (hash, length) ->
            assertThatThrownBy {
                jdbc.update(
                    """
                    INSERT INTO bcm_ntwk_wlt_evdc_l
                      (evdc_id, crtn_id, orgn_id, acnt_id, ntwk_cd, corr_id, req_hash, oprtn_dvcd, body, body_len, body_hash, obs_dttm,
                       frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                    SELECT 'tampered', crtn_id, orgn_id, acnt_id, ntwk_cd, corr_id, req_hash, 'CREATE', body, $length, $hash, ?,
                           'SYSTEM', '9999', 'SYSTEM', '9999'
                    FROM (SELECT crtn_id, orgn_id, acnt_id, ntwk_cd, corr_id, req_hash, decode('0a00', 'hex') AS body
                          FROM bcm_ntwk_wlt_crtn_l) intent
                    """.trimIndent(),
                    NOW,
                )
            }.isInstanceOf(DataAccessException::class.java)
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_evdc_l", Int::class.java)).isZero()
    }

    @Test
    fun `의도와 다른 scope나 원천의 증적은 저장하지 않는다`() {
        val scope = intent.request.scope
        val otherAccount = intent.copy(request = intent.request.copy(scope = scope.copy(accountId = "acct_other")))
        assertThatThrownBy { evidence.store(NetworkWalletLedgerFixture.context(otherAccount), NetworkWalletLedgerFixture.body()) }
            .isInstanceOf(DataAccessException::class.java)

        val otherOrigin = intent.copy(request = intent.request.copy(scope = scope.copy(origin = scope.origin.copy(chainMode = "MAINNET"))))
        assertThatThrownBy { evidence.store(NetworkWalletLedgerFixture.context(otherOrigin), NetworkWalletLedgerFixture.body()) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_ntwk_wlt_evdc_l", Int::class.java)).isZero()
    }

    @Test
    fun `호출자 트랜잭션이 롤백돼도 이미 보관한 증적은 남는다`() {
        lateinit var reference: String
        assertThatThrownBy {
            TransactionTemplate(transactionManager).execute {
                reference = evidence.store(NetworkWalletLedgerFixture.context(intent), NetworkWalletLedgerFixture.body()).reference
                error("simulated caller failure after archive")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(evidence.find(reference)).isNotNull()
    }

    @Test
    fun `모르는 참조는 null이고 다른 형식의 참조는 거절한다`() {
        assertThat(evidence.find("bcm-evidence://network-wallet/unknown-id")).isNull()
        listOf("fixture://internal-observation", "bcm-evidence://network-wallet/", "bcm-evidence://network-wallet/ id").forEach {
            assertThatThrownBy { evidence.find(it) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `증적 원장은 PK와 의도 복합 FK 및 감사 컬럼을 DB에서 강제한다`() {
        assertThat(
            jdbc.queryForList(
                "SELECT constraint_type FROM information_schema.table_constraints WHERE table_name = 'bcm_ntwk_wlt_evdc_l' AND table_schema = 'public'",
                String::class.java,
            ),
        ).contains("PRIMARY KEY", "FOREIGN KEY", "CHECK")
        assertThat(
            jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'bcm_ntwk_wlt_evdc_l' AND table_schema = 'public' AND is_nullable = 'NO'
                """.trimIndent(),
                String::class.java,
            ),
        ).contains("body", "body_len", "body_hash", "frst_reg_empno", "frst_reg_brcd", "last_chng_empno", "last_chng_brcd")
    }
}
