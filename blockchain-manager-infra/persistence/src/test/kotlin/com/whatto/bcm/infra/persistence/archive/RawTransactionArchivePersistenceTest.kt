package com.whatto.bcm.infra.persistence.archive

import com.whatto.bcm.domain.archive.RawTransactionArchiveRepository
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@DataJdbcTest
@Import(RawTransactionArchiveJdbcAdapter::class)
class RawTransactionArchivePersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var archives: RawTransactionArchiveRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `배포 SQL은 지정한 월부터 필요한 월별 파티션을 미리 만든다`() {
        createPartitions("202608", 2)

        val partitions =
            jdbc.queryForList(
                """
                SELECT child.relname
                FROM pg_inherits inheritance
                JOIN pg_class parent ON parent.oid = inheritance.inhparent
                JOIN pg_class child ON child.oid = inheritance.inhrelid
                WHERE parent.relname = 'bcm_raw_tx_l'
                ORDER BY child.relname
                """.trimIndent(),
                String::class.java,
            )

        assertThat(partitions).containsExactly("bcm_raw_tx_l_202608", "bcm_raw_tx_l_202609")
    }

    @Test
    fun `마지막 COMPLETED 원문과 수신 해시 서명을 그대로 보관하고 처리된 인박스만 정리한다`() {
        createPartitions("202608", 1)
        insertFinalizedDeposit()
        insertWebhook(
            notificationId = "completed-old",
            status = "COMPLETED",
            payloadHash = "a".repeat(64),
            signature = "signature-old",
            receivedAt = "20260807110000",
            processStatus = "S",
            processedAt = "20260807110100",
        )
        val latestPayload =
            insertWebhook(
                notificationId = "completed-latest",
                status = "COMPLETED",
                payloadHash = "b".repeat(64),
                signature = "signature-latest",
                receivedAt = "20260807120000",
                processStatus = "S",
                processedAt = "20260807120100",
            )
        insertWebhook(
            notificationId = "confirming-processed",
            status = "CONFIRMING",
            payloadHash = "c".repeat(64),
            signature = "signature-confirming",
            receivedAt = "20260807100000",
            processStatus = "S",
            processedAt = "20260807100100",
        )
        insertWebhook(
            notificationId = "pending",
            status = "CONFIRMING",
            payloadHash = "d".repeat(64),
            signature = "signature-pending",
            receivedAt = "20260807100000",
            processStatus = "P",
            processedAt = null,
        )
        insertWebhook(
            notificationId = "poison",
            status = "CONFIRMING",
            payloadHash = "e".repeat(64),
            signature = "signature-poison",
            receivedAt = "20260807100000",
            processStatus = "F",
            processedAt = null,
        )

        val archived = archives.archiveCompletedWindow("20260813", "00010101000000", "20260807130000", 10)
        val deleted = archives.deleteProcessedAtOrBefore("20260807130000")

        assertThat(archived.candidateCount).isEqualTo(1)
        assertThat(archived.archivedCount).isEqualTo(1)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_raw_tx_l"))
            .containsEntry("base_dt", "20260813")
            .containsEntry("vndr_tx_id", "tx-deposit")
            .containsEntry("addr", "0xDestination")
            .containsEntry("payload", latestPayload)
            .containsEntry("payload_hash", "b".repeat(64))
            .containsEntry("sign_vl", "signature-latest")
            .containsEntry("rcv_dttm", "20260807120000")
        assertThat(deleted).isEqualTo(3)
        assertThat(jdbc.queryForList("SELECT noti_id FROM bcm_whk_l ORDER BY noti_id", String::class.java))
            .containsExactly("pending", "poison")
    }

    @Test
    fun `같은 날 재실행은 중복하지 않고 더 늦은 COMPLETED가 오면 원본을 교체한다`() {
        createPartitions("202608", 1)
        insertFinalizedDeposit()
        insertWebhook(
            notificationId = "completed-first",
            status = "COMPLETED",
            payloadHash = "a".repeat(64),
            signature = "signature-first",
            receivedAt = "20260807110000",
            processStatus = "S",
            processedAt = "20260807110100",
        )

        archives.archiveCompletedWindow("20260813", "00010101000000", "20260807120000", 10)
        val duplicate = archives.archiveCompletedWindow("20260813", "00010101000000", "20260807120000", 10)
        val latestPayload =
            insertWebhook(
                notificationId = "completed-new",
                status = "COMPLETED",
                payloadHash = "f".repeat(64),
                signature = "signature-new",
                receivedAt = "20260807130000",
                processStatus = "S",
                processedAt = "20260807130100",
            )
        val updated = archives.archiveCompletedWindow("20260813", "20260807120000", "20260807140000", 10)

        assertThat(duplicate.candidateCount).isZero()
        assertThat(updated.candidateCount).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_raw_tx_l", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_raw_tx_l"))
            .containsEntry("payload", latestPayload)
            .containsEntry("payload_hash", "f".repeat(64))
            .containsEntry("sign_vl", "signature-new")
    }

    @Test
    fun `제출 거래의 원본 조회 주소는 수취 주소가 아니라 출발 주소를 쓴다`() {
        createPartitions("202608", 1)
        insertFinalizedWithdrawal()
        insertWebhook(
            notificationId = "completed-withdrawal",
            vendorTransactionId = "tx-withdrawal",
            status = "COMPLETED",
            payloadHash = "a".repeat(64),
            signature = "signature-withdrawal",
            receivedAt = "20260807120000",
            processStatus = "S",
            processedAt = "20260807120100",
        )

        archives.archiveCompletedWindow("20260813", "00010101000000", "20260807130000", 10)

        assertThat(jdbc.queryForMap("SELECT * FROM bcm_raw_tx_l"))
            .containsEntry("vndr_tx_id", "tx-withdrawal")
            .containsEntry("ext_tx_id", "wd-1")
            .containsEntry("addr", "0xSource")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `대상 월 파티션이 없으면 원본 적재를 실패시킨다`() {
        insertFinalizedDeposit()
        insertWebhook(
            notificationId = "completed-no-partition",
            status = "COMPLETED",
            payloadHash = "a".repeat(64),
            signature = "signature",
            receivedAt = "20261007120000",
            processStatus = "S",
            processedAt = "20261007120100",
        )

        assertThatThrownBy {
            archives.archiveCompletedWindow("20261007", "00010101000000", "20261007130000", 10)
        }.isInstanceOf(DataAccessException::class.java)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_raw_tx_l", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_whk_l", Long::class.java)).isEqualTo(1)
        jdbc.update("DELETE FROM bcm_whk_l WHERE noti_id = 'completed-no-partition'")
        jdbc.update("DELETE FROM bcm_tx_l WHERE vndr_tx_id = 'tx-deposit'")
    }

    private fun createPartitions(
        startMonth: String,
        monthCount: Int,
    ) {
        val sql =
            ClassPathResource("db/operations/create_bcm_raw_tx_partitions.sql")
                .inputStream.bufferedReader().use { it.readText() }
                .replace(":'start_month'", "'$startMonth'")
                .replace(":'month_count'", "'$monthCount'")
        jdbc.execute(sql)
    }

    private fun insertFinalizedDeposit() {
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, stall_alrt_dttm,
               frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('tx-deposit', 'tx-deposit', NULL, 'account-1', 'ETHEREUM', 'USDC', '0xHash',
               'FINALIZED', 3, 'CONFIRMED', 'CONFIRMED', NULL,
               '20260807100000', '20260807120000',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    private fun insertFinalizedWithdrawal() {
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, stall_alrt_dttm,
               frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('tx-withdrawal', 'tx-withdrawal', 'wd-1', 'account-1', 'ETHEREUM', 'USDC', '0xHash',
               'FINALIZED', 3, 'CONFIRMED', 'CONFIRMED', NULL,
               '20260807100000', '20260807120000',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_sbmt_l
              (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, claim_id, claim_exp_dttm,
               tx_dvcd, vndr_tx_id, swp_exec_id, snd_acnt_id, rcv_dvcd, rcv_vl,
               ntwk_cd, tkn_smbl, trsf_amt, call_data, req_dttm, rsp_dttm, last_chck_dttm, chck_cnt,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('wd-1', ?, 'v1', 'SUBMITTED', NULL, NULL,
               'WITHDRAWAL', 'tx-withdrawal', NULL, 'account-1', 'ADDRESS', '0xDestination',
               'ETHEREUM', 'USDC', 1, NULL, '20260807100000', '20260807100100', NULL, 0,
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            "a".repeat(64),
        )
    }

    private fun insertWebhook(
        notificationId: String,
        vendorTransactionId: String = "tx-deposit",
        status: String,
        payloadHash: String,
        signature: String,
        receivedAt: String,
        processStatus: String,
        processedAt: String?,
    ): String {
        val payload =
            """{ "id": "$notificationId", "data": { "id": "$vendorTransactionId", "status": "$status", "sourceAddress": "0xSource", "destinationAddress": "0xDestination" } }"""
        jdbc.update(
            """
            INSERT INTO bcm_whk_l
              (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl,
               rcv_dttm, prcs_stcd, rtry_cnt, err_msg, prcs_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'transaction.status.updated', ?, ?, ?, ?, ?, ?, 0, NULL, ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            notificationId,
            vendorTransactionId,
            payload,
            payloadHash,
            signature,
            receivedAt,
            processStatus,
            processedAt,
        )
        return payload
    }
}
