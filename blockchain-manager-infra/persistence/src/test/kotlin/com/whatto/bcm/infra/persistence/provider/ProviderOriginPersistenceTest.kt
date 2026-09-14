package com.whatto.bcm.infra.persistence.provider

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(ProviderOriginJdbcAdapter::class)
class ProviderOriginPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var repository: ProviderOriginJdbcAdapter

    private fun insert(
        mode: String = "fireblocks",
        protocol: String = "fireblocks",
        chain: String = "TESTNET",
        id: String = "test-origin",
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_prvd_bndg_m VALUES
              (1, ?, ?, ?, 'test-instance', 'test-org', ?, '20260914000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            id,
            mode,
            protocol,
            chain,
        )
    }

    @Test
    fun `마이그레이션은 원천을 자동 등록하지 않고 조회는 원천 전체를 보존한다`() {
        assertThat(repository.findBinding()).isNull()
        insert()
        val binding = requireNotNull(repository.findBinding())
        assertThat(binding.originId).isEqualTo("test-origin")
        assertThat(binding.executionMode).isEqualTo("fireblocks")
        assertThat(binding.protocolProvider).isEqualTo("fireblocks")
        assertThat(binding.platformInstanceId).isEqualTo("test-instance")
        assertThat(binding.vendorOrganizationId).isEqualTo("test-org")
        assertThat(binding.chainMode).isEqualTo("TESTNET")
    }

    @Test
    fun `PK와 원천 UNIQUE 및 컬럼 규약이 존재한다`() {
        val constraints =
            jdbc.queryForList(
                "SELECT constraint_type FROM information_schema.table_constraints WHERE table_name = 'bcm_prvd_bndg_m'",
                String::class.java,
            )
        assertThat(constraints).contains("PRIMARY KEY", "UNIQUE", "CHECK")
        val columns =
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'bcm_prvd_bndg_m' AND is_nullable = 'NO'",
                String::class.java,
            )
        assertThat(columns).containsExactlyInAnyOrder(
            "bndg_no",
            "orgn_id",
            "exec_mode",
            "prtc_prvd",
            "pltfrm_inst_id",
            "vndr_org_id",
            "chain_mode",
            "bndg_dttm",
            "frst_reg_empno",
            "frst_reg_brcd",
            "last_chng_empno",
            "last_chng_brcd",
        )
    }

    @ParameterizedTest
    @CsvSource("fireblocks,dfns,TESTNET", "local,fireblocks,MAINNET", "dfns,dfns,LOCAL", "unknown,fireblocks,TESTNET")
    fun `허용되지 않은 실행 원천 조합은 DB가 거절한다`(
        mode: String,
        protocol: String,
        chain: String,
    ) {
        assertThatThrownBy { insert(mode, protocol, chain) }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `둘째 binding은 PK가 거절한다`() {
        insert()
        assertThatThrownBy { insert(id = "second-origin") }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `다른 슬롯으로 singleton 제약을 우회할 수 없다`() {
        insert()
        assertThatThrownBy { jdbc.update("UPDATE bcm_prvd_bndg_m SET bndg_no = 2") }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `앞뒤 공백을 포함한 원천 식별자는 DB가 거절한다`() {
        assertThatThrownBy { insert(id = "origin\t") }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `기존 미완료 의도와 보관 원문을 유지하며 원천 등록을 요구한다`() {
        jdbc.execute("CREATE SCHEMA provider_upgrade_test")
        jdbc.execute("SET LOCAL search_path TO provider_upgrade_test")

        fun applyScript(name: String) {
            val sql = requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$name")).bufferedReader().use { it.readText() }
            jdbc.execute(sql)
        }
        applyScript("V1__bcm_core_tables.sql")
        applyScript("V20__wallet_provisioning_ledgers.sql")
        jdbc.execute(
            """
            INSERT INTO bcm_acnt_crtn_l VALUES (
              'legacy-account', 'CU', 'legacy-ref', 'CUSTOMER:legacy-ref', 'stable-key',
              '20260913000000', '20260914000000', 'SUBMITTING', 2, NULL,
              '20260913000000', '20260914000000', 'SYSTEM', '9999', 'SYSTEM', '9999'
            )
            """.trimIndent(),
        )
        val before = jdbc.queryForMap("SELECT * FROM bcm_acnt_crtn_l")
        jdbc.execute("CREATE TABLE bcm_raw_tx_20260914 PARTITION OF bcm_raw_tx_l FOR VALUES FROM ('20260914') TO ('20260915')")
        val payload = requireNotNull(javaClass.getResourceAsStream("/payload/transaction.created.json")).use { it.readAllBytes() }
        val hash =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) }
        jdbc.update(
            """
            INSERT INTO bcm_raw_tx_l VALUES (
              '20260914', 'legacy-transaction', NULL, NULL, 'legacy-address', 'ETHEREUM', 'USDC', 'FINALIZED',
              ?, ?, 'preserved-test-signature', '20260914000000', 'SYSTEM', '9999', 'SYSTEM', '9999'
            )
            """.trimIndent(),
            payload.toString(Charsets.UTF_8),
            hash,
        )
        val archived = jdbc.queryForMap("SELECT * FROM bcm_raw_tx_l")
        applyScript("V21__provider_origin_binding.sql")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_acnt_crtn_l")).isEqualTo(before)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_raw_tx_20260914")).isEqualTo(archived)
        assertThat(repository.findBinding()).isNull()
        insert()
        assertThat(repository.findBinding()?.originId).isEqualTo("test-origin")
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_acnt_crtn_l")).isEqualTo(before)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_raw_tx_l")).isEqualTo(archived)
    }

    @Test
    fun `앱 조회 전용 역할은 등록 변경 삭제 초기화를 수행할 수 없다`() {
        insert()
        jdbc.execute("CREATE ROLE bcm_origin_reader_test")
        jdbc.execute("GRANT SELECT ON bcm_prvd_bndg_m TO bcm_origin_reader_test")
        jdbc.execute("SET LOCAL ROLE bcm_origin_reader_test")
        assertThat(repository.findBinding()?.originId).isEqualTo("test-origin")
        listOf(
            "INSERT INTO bcm_prvd_bndg_m SELECT * FROM bcm_prvd_bndg_m",
            "UPDATE bcm_prvd_bndg_m SET orgn_id = 'relabelled'",
            "DELETE FROM bcm_prvd_bndg_m",
            "TRUNCATE bcm_prvd_bndg_m",
        ).forEach { sql ->
            jdbc.execute("SAVEPOINT denied_write")
            assertThatThrownBy { jdbc.execute(sql) }
                .isInstanceOf(DataAccessException::class.java)
                .rootCause()
                .hasMessageContaining("permission denied for table bcm_prvd_bndg_m")
            jdbc.execute("ROLLBACK TO SAVEPOINT denied_write")
        }
        jdbc.execute("RESET ROLE")
        assertThat(repository.findBinding()?.originId).isEqualTo("test-origin")
    }
}
