package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.ExecutionGateRepository
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.infra.persistence.admin.fixture.ExecutionGateFixture.event
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(ExecutionGateJdbcAdapter::class)
class ExecutionGatePersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var gates: ExecutionGateRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('base-gate-test', 'BASE', 8453, 'Base', 'N', 'N', '20260817120000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    @Test
    fun `중지 event를 append-only로 저장하고 현재 범위와 멱등 키로 조회한다`() {
        val stopped = event()

        assertThat(gates.insert(stopped)).isEqualTo(stopped)
        assertThat(gates.findCurrent("BASE", ExecutionGateType.WITHDRAWAL)).isEqualTo(stopped)
        assertThat(gates.findByIdempotency(stopped.actor.employeeNo, stopped.idempotencyKey)).isEqualTo(stopped)

        assertThatThrownBy {
            jdbc.update("UPDATE bcm_exec_gate_evt_l SET req_rsn = 'changed' WHERE gate_evt_id = ?", stopped.eventId)
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 범위 sequence는 DB가 중복을 거절한다`() {
        gates.insert(event())

        assertThatThrownBy { gates.insert(event(eventId = "gate-event-2", idempotencyKey = "stop-2")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `같은 작업자 멱등 키는 다른 범위에도 재사용할 수 없다`() {
        gates.insert(event())

        assertThatThrownBy {
            gates.insert(
                event(
                    eventId = "gate-event-3",
                    network = "BASE",
                    type = ExecutionGateType.SWEEP,
                ),
            )
        }.isInstanceOf(ConflictException::class.java)
    }
}
