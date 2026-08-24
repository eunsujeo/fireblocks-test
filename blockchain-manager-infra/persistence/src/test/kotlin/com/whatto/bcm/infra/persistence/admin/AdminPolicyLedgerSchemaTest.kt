package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
class AdminPolicyLedgerSchemaTest : PersistenceTestSupport() {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `T10_4 불변 원장 6개와 현재 binding projection 2개가 존재한다`() {
        val tables =
            jdbc.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                     'bcm_ctrt_vrsn_l', 'bcm_ctrt_evdc_l', 'bcm_plcy_vrsn_l',
                     'bcm_chng_req_l', 'bcm_chng_dcsn_l', 'bcm_adm_actn_l',
                     'bcm_ctrt_bind_m', 'bcm_plcy_bind_m'
                   )
                """.trimIndent(),
                String::class.java,
            )

        assertThat(tables).containsExactlyInAnyOrder(
            "bcm_ctrt_vrsn_l",
            "bcm_ctrt_evdc_l",
            "bcm_plcy_vrsn_l",
            "bcm_chng_req_l",
            "bcm_chng_dcsn_l",
            "bcm_adm_actn_l",
            "bcm_ctrt_bind_m",
            "bcm_plcy_bind_m",
        )
    }
}
