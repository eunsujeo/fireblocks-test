package com.whatto.bcm.app.bat.archive

import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.archive.RawTransactionArchiveRepository
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.infra.persistence.archive.RawTransactionArchiveJdbcAdapter
import com.whatto.bcm.infra.persistence.config.SpringTransactionRunner
import com.whatto.bcm.infra.persistence.job.JobStateJdbcAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@SpringBootTest(classes = [RawTransactionArchiveIntegrationTest.TestApplication::class])
@Import(
    RawTransactionArchiveJdbcAdapter::class,
    JobStateJdbcAdapter::class,
    SpringTransactionRunner::class,
)
class RawTransactionArchiveIntegrationTest : IntegrationTestSupport() {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class TestApplication

    @Autowired
    lateinit var archives: RawTransactionArchiveRepository

    @Autowired
    lateinit var jobs: JobStateRepository

    @Autowired
    lateinit var transactionRunner: TransactionRunner

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        clearTables()
        createPartition()
    }

    @AfterEach
    fun tearDown() {
        clearTables()
    }

    @Test
    fun `인박스 정리가 실패해도 앞서 커밋한 원본은 유지하고 삭제와 성공 heartbeat만 롤백한다`() {
        insertFinalizedTransactionAndWebhook()
        val failingCleanup =
            object : RawTransactionArchiveRepository by archives {
                override fun deleteProcessedAtOrBefore(processedAtOrBefore: String): Int {
                    val deleted = archives.deleteProcessedAtOrBefore(processedAtOrBefore)
                    check(deleted == 1)
                    error("cleanup failed after delete")
                }
            }
        val job = job(failingCleanup)

        assertThatThrownBy(job::run)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cleanup failed")

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_raw_tx_l", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_whk_l", Long::class.java)).isEqualTo(1)
        assertThat(jobs.find(RawTransactionArchiveJob.JOB_NAME)?.lastSucceededAt).isNull()
    }

    private fun job(repository: RawTransactionArchiveRepository) =
        RawTransactionArchiveJob(
            archives = repository,
            jobs = jobs,
            transactionRunner = transactionRunner,
            clock = Clock.fixed(Instant.parse("2026-08-13T03:00:00Z"), ZoneId.of("Asia/Seoul")),
            properties = RawTransactionArchiveProperties(enabled = true, retentionDays = 30),
        )

    private fun createPartition() {
        val sql =
            ClassPathResource("db/operations/create_bcm_raw_tx_partitions.sql")
                .inputStream
                .bufferedReader()
                .use { it.readText() }
                .replace(":'start_month'", "'202608'")
                .replace(":'month_count'", "'1'")
        jdbc.execute(sql)
    }

    private fun insertFinalizedTransactionAndWebhook() {
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, stall_alrt_dttm,
               vndr_crt_dttm, frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('tx-archive', 'tx-archive', NULL, 'account-1', 'ETHEREUM', 'USDC', '0xHash',
               'FINALIZED', 3, 'CONFIRMED', 'CONFIRMED', NULL,
               '20260701100000', '20260701100000', '20260701120000',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        val payload =
            """{ "data": { "id": "tx-archive", "status": "COMPLETED", "sourceAddress": "0xSource", "destinationAddress": "0xDestination" } }"""
        jdbc.update(
            """
            INSERT INTO bcm_whk_l
              (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl,
               rcv_dttm, prcs_stcd, rtry_cnt, err_msg, prcs_dttm, vndr_cmpl_yn,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('notification-archive', 'transaction.status.updated', 'tx-archive', ?, ?, 'signature',
               '20260701120000', 'S', 0, NULL, '20260701120100', 'Y',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            payload,
            "a".repeat(64),
        )
    }

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_raw_tx_l")
        jdbc.update("DELETE FROM bcm_whk_l")
        jdbc.update("DELETE FROM bcm_tx_l")
        jdbc.update("DELETE FROM bcm_job_m")
    }
}
