package com.whatto.bcm.app.application.admin

import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(classes = [BcmApiApplication::class])
class ExecutionGateCommandServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var service: ExecutionGateCommandService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm, chain_mdl_dvcd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 987654321, 'Execution gate concurrency', 'N', 'N', '20260817210000', 'EVM',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            CANDIDATE_ID,
            NETWORK,
        )
    }

    @AfterEach
    fun tearDown() {
        jdbc.execute("TRUNCATE TABLE bcm_exec_gate_evt_l CASCADE")
        jdbc.update("DELETE FROM bcm_blkc_m WHERE vndr_blkc_id = ?", CANDIDATE_ID)
    }

    @Test
    fun `동시에 같은 범위를 중지해도 event는 한 건만 남고 두 요청은 같은 결과를 본다`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val results =
                (1..2).map { index ->
                    executor.submit(
                        Callable<ExecutionGateEvent> {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            service.stop(command(index))
                        },
                    )
                }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val eventIds = results.map { it.get(10, TimeUnit.SECONDS).eventId }

            assertThat(eventIds).hasSize(2).containsOnly(eventIds.first())
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM bcm_exec_gate_evt_l WHERE ntwk_cd = ? AND gate_dvcd = 'WITHDRAWAL'",
                    Int::class.java,
                    NETWORK,
                ),
            ).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun command(index: Int) =
        StopExecutionGateCommand(
            network = NETWORK,
            type = ExecutionGateType.WITHDRAWAL,
            reason = "동시 중지 검증",
            workTicket = "SEC-1060",
            idempotencyKey = "execution-gate-concurrent-$index",
            actor = AdminActor("81000$index", "0001", setOf(AdminRole.BCM_OPERATOR)),
        )

    private companion object {
        const val NETWORK = "GATECONCUR"
        const val CANDIDATE_ID = "gate-concurrency-test"
    }
}
