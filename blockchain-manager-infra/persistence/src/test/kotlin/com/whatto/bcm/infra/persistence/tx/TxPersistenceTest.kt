package com.whatto.bcm.infra.persistence.tx

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJdbcTest
@Import(TxJdbcAdapter::class)
class TxPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var txRecords: TxJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private fun txRecord(
        vendorTxId: String = "tx-91c",
        activeVendorTxId: String = vendorTxId,
        externalTxId: String? = null,
        transactionHash: String? = null,
        status: TxStatus = TxStatus.CONFIRMED,
        confirmationCount: Int = 1,
        vendorCreatedAt: String = "20260805113000",
    ) = TxRecord(
        vendorTxId = vendorTxId,
        activeVendorTxId = activeVendorTxId,
        externalTxId = externalTxId,
        accountId = "acct_01",
        network = "ETHEREUM",
        symbol = "USDC",
        transactionHash = transactionHash,
        lastPublishedStatus = status,
        confirmationCount = confirmationCount,
        vendorSubStatus = "PENDING_BLOCKCHAIN_CONFIRMATIONS",
        vendorNetworkStatus = "CONFIRMING",
        vendorCreatedAt = vendorCreatedAt,
        firstDetectedAt = "20260805120000",
        lastChangedAt = "20260805120000",
    )

    @Test
    fun `거래 왕복 — 벤더 원어 보관 컬럼(subStatus·networkStatus)까지 그대로 되찾는다`() {
        val saved = txRecords.insert(txRecord())
        assertThat(txRecords.findByVendorTxId("tx-91c")).isEqualTo(saved)
        assertThat(txRecords.findByActiveVendorTxId("tx-91c")).isEqualTo(saved)
        assertThat(saved.activeVendorTxId).isEqualTo(saved.vendorTxId)
    }

    @Test
    fun `온체인 hash는 최초 값만 저장하고 빈 관찰로 지우거나 다른 값으로 바꾸지 않는다`() {
        val saved = txRecords.insert(txRecord())

        val withHash = txRecords.update(saved.copy(transactionHash = "0xabc"))
        val afterEmptyObservation = txRecords.update(withHash.copy(transactionHash = null))

        assertThat(withHash.transactionHash).isEqualTo("0xabc")
        assertThat(afterEmptyObservation.transactionHash).isEqualTo("0xabc")
        assertThatThrownBy { txRecords.update(withHash.copy(transactionHash = "0xdifferent")) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(txRecords.findByVendorTxId(saved.vendorTxId)?.transactionHash).isEqualTo("0xabc")
    }

    @Test
    fun `서로 다른 root 거래는 같은 active vendor tx id를 공유할 수 없다`() {
        txRecords.insert(txRecord(vendorTxId = "tx-root-1", activeVendorTxId = "tx-active"))

        assertThatThrownBy {
            txRecords.insert(txRecord(vendorTxId = "tx-root-2", activeVendorTxId = "tx-active"))
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `막힘 후보는 오래된 미종결 무경보 root만 시각 순으로 조회한다`() {
        txRecords.insert(txRecord(vendorTxId = "tx-submitted", status = TxStatus.SUBMITTED))
        txRecords.insert(txRecord(vendorTxId = "tx-confirmed", status = TxStatus.CONFIRMED))
        txRecords.insert(txRecord(vendorTxId = "tx-recent", status = TxStatus.CONFIRMED))
        txRecords.insert(txRecord(vendorTxId = "tx-finalized", status = TxStatus.FINALIZED))
        txRecords.insert(txRecord(vendorTxId = "tx-alerted", status = TxStatus.CONFIRMED))
        insertSubmission("tx-confirmed")
        jdbc.update("UPDATE bcm_tx_l SET last_chng_dttm = '20260807110000' WHERE vndr_tx_id = 'tx-submitted'")
        jdbc.update("UPDATE bcm_tx_l SET last_chng_dttm = '20260807111000' WHERE vndr_tx_id = 'tx-confirmed'")
        jdbc.update("UPDATE bcm_tx_l SET last_chng_dttm = '20260807115900' WHERE vndr_tx_id = 'tx-recent'")
        jdbc.update("UPDATE bcm_tx_l SET last_chng_dttm = '20260807100000' WHERE vndr_tx_id = 'tx-finalized'")
        jdbc.update(
            "UPDATE bcm_tx_l SET last_chng_dttm = '20260807102000', stall_alrt_dttm = '20260807103000' " +
                "WHERE vndr_tx_id = 'tx-alerted'",
        )

        val candidates = txRecords.findStallCandidates("20260807115000", 10)

        assertThat(candidates.map { it.record.vendorTxId }).containsExactly("tx-submitted", "tx-confirmed")
        assertThat(candidates.map { it.submissionType })
            .containsExactly(null, SubmissionTransactionType.WITHDRAWAL)
    }

    @Test
    fun `막힘 경보 시각은 같은 root에서 한 번만 기록한다`() {
        val candidate = txRecords.insert(txRecord(vendorTxId = "tx-stall"))

        assertThat(txRecords.markStallAlertedIfAbsent(candidate, "20260807120000")).isTrue()
        assertThat(txRecords.markStallAlertedIfAbsent(candidate, "20260807120100")).isFalse()
        assertThat(txRecords.findByVendorTxId("tx-stall")?.stallAlertedAt).isEqualTo("20260807120000")
    }

    @Test
    fun `후보 조회 뒤 active 거래가 바뀌면 옛 관찰로 경보 표시하지 않는다`() {
        val staleCandidate = txRecords.insert(txRecord(vendorTxId = "tx-root"))
        jdbc.update("UPDATE bcm_tx_l SET actv_tx_id = 'tx-replacement' WHERE vndr_tx_id = 'tx-root'")

        assertThat(txRecords.markStallAlertedIfAbsent(staleCandidate, "20260807120000")).isFalse()
        assertThat(txRecords.findByVendorTxId("tx-root")?.stallAlertedAt).isNull()
    }

    @Test
    fun `물리 승자 갱신은 읽었던 active가 이미 바뀌면 충돌한다`() {
        val root =
            txRecords.insert(
                txRecord(
                    vendorTxId = "tx-winner-root",
                    activeVendorTxId = "tx-candidate-a",
                    transactionHash = "0xa",
                    confirmationCount = 0,
                ),
            )
        jdbc.update("UPDATE bcm_tx_l SET actv_tx_id = 'tx-candidate-b' WHERE vndr_tx_id = ?", root.vendorTxId)

        assertThatThrownBy {
            txRecords.updatePhysicalWinner(
                root.copy(
                    activeVendorTxId = "tx-winner",
                    transactionHash = "0xwinner",
                    lastPublishedStatus = TxStatus.FINALIZED,
                    confirmationCount = 1,
                ),
                previousActiveVendorTxId = "tx-candidate-a",
            )
        }.isInstanceOf(ConflictException::class.java)
        assertThat(txRecords.findByVendorTxId(root.vendorTxId)?.activeVendorTxId).isEqualTo("tx-candidate-b")
    }

    @Test
    fun `전이 반영은 컨펌 수와 시각을 줄이지 않고 최초 탐지와 최초 감사를 보존한다`() {
        val saved = txRecords.insert(txRecord())
        jdbc.update(
            "UPDATE bcm_tx_l SET frst_reg_empno = '111111', frst_reg_brcd = '2222' WHERE vndr_tx_id = ?",
            saved.vendorTxId,
        )
        val attempted =
            saved.copy(
                lastPublishedStatus = TxStatus.FINALIZED,
                confirmationCount = 0,
                vendorSubStatus = "CONFIRMED",
                firstDetectedAt = "20260804110000",
                lastChangedAt = "20260805115900",
            )
        val updated = txRecords.update(attempted)
        val audit = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = ?", saved.vendorTxId)

        assertThat(updated)
            .isEqualTo(
                attempted.copy(
                    confirmationCount = saved.confirmationCount,
                    firstDetectedAt = saved.firstDetectedAt,
                    lastChangedAt = saved.lastChangedAt,
                ),
            )
        assertThat(audit["frst_reg_empno"]).isEqualTo("111111")
        assertThat(audit["frst_reg_brcd"]).isEqualTo("2222")
    }

    @Test
    fun `출금 재제출 중복 차단 — 같은 externalTxId 재삽입은 도메인 충돌로 변환된다`() {
        txRecords.insert(txRecord(vendorTxId = "tx-w1", externalTxId = "wd-42", status = TxStatus.SUBMITTED))
        assertThatThrownBy {
            txRecords.insert(txRecord(vendorTxId = "tx-w2", externalTxId = "wd-42", status = TxStatus.SUBMITTED))
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `없는 거래는 null — 신규 판정((없음) 행)의 분기 근거`() {
        assertThat(txRecords.findByVendorTxId("tx-none")).isNull()
        assertThat(txRecords.findByExternalTxId("wd-none")).isNull()
    }

    @Test
    fun `같은 vendorTxId 재삽입은 도메인 충돌로 변환된다`() {
        txRecords.insert(txRecord(vendorTxId = "tx-dup"))
        assertThatThrownBy { txRecords.insert(txRecord(vendorTxId = "tx-dup")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `externalTxId 로 출금 건을 되찾는다 — DAW-CORE 대응 키`() {
        val saved = txRecords.insert(txRecord(vendorTxId = "tx-w9", externalTxId = "wd-99", status = TxStatus.SUBMITTED))
        assertThat(txRecords.findByExternalTxId("wd-99")).isEqualTo(saved)
    }

    @Test
    fun `대사 물리 id 조회는 root active boost replacement를 같은 논리 거래로 접는다`() {
        txRecords.insert(
            txRecord(
                vendorTxId = "tx-reconcile-root",
                activeVendorTxId = "tx-reconcile-active",
                externalTxId = "wd-confirmed",
                confirmationCount = 0,
            ),
        )
        insertSubmission("tx-reconcile-root")
        jdbc.update(
            """
            INSERT INTO bcm_boost_l
              (orig_tx_id, try_seq, ext_tx_id, bst_stcd, claim_id, claim_exp_dttm,
               rplc_tx_id, rplc_tx_hash, fee_lvl, gasless_yn, new_tx_id, req_dttm, rsp_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('tx-reconcile-root', 1, 'bst-reconcile', 'SUBMITTED', NULL, NULL,
               'tx-reconcile-root', '0xroot', 'HIGH', 'Y', 'tx-reconcile-active',
               '20260807110000', '20260807110100', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )

        val root = txRecords.findByPhysicalVendorTransactionId("tx-reconcile-root")
        val active = txRecords.findByPhysicalVendorTransactionId("tx-reconcile-active")

        assertThat(root).isEqualTo(active)
        assertThat(root?.record?.vendorTxId).isEqualTo("tx-reconcile-root")
        assertThat(root?.submissionType).isEqualTo(SubmissionTransactionType.WITHDRAWAL)
    }

    @Test
    fun `대사 창은 최초 감지가 아니라 벤더 createdAt의 닫힌 구간으로 조회한다`() {
        txRecords.insert(txRecord(vendorTxId = "tx-before", status = TxStatus.FINALIZED, vendorCreatedAt = "20260807114959"))
        txRecords.insert(txRecord(vendorTxId = "tx-boundary", status = TxStatus.FINALIZED, vendorCreatedAt = "20260807115000"))
        txRecords.insert(txRecord(vendorTxId = "tx-window", status = TxStatus.FINALIZED, vendorCreatedAt = "20260807115100"))
        txRecords.insert(txRecord(vendorTxId = "tx-after", status = TxStatus.FAILED, vendorCreatedAt = "20260807120001"))
        jdbc.update("UPDATE bcm_tx_l SET frst_dtct_dttm = '20260808120000' WHERE vndr_tx_id = 'tx-boundary'")

        val window = txRecords.findCreatedBetween("20260807115000", "20260807120000")

        assertThat(window.map { it.record.vendorTxId })
            .containsExactly("tx-boundary", "tx-window")
    }

    @Test
    fun `미결 대사 claim은 확인 시각과 횟수를 먼저 남기고 영속 백오프를 지킨다`() {
        txRecords.insert(txRecord(vendorTxId = "tx-pending", confirmationCount = 0))
        jdbc.update(
            "UPDATE bcm_tx_l SET last_chng_dttm = '20260807110000' WHERE vndr_tx_id = 'tx-pending'",
        )

        val first = txRecords.claimPendingForReconciliation("20260807115000", "20260807120000", 10)
        val tooEarly = txRecords.claimPendingForReconciliation("20260807115000", "20260807120029", 10)
        val second = txRecords.claimPendingForReconciliation("20260807115000", "20260807120030", 10)

        assertThat(first.map { it.record.vendorTxId }).containsExactly("tx-pending")
        assertThat(first.single().record.reconciliationCheckedAt).isEqualTo("20260807120000")
        assertThat(first.single().record.reconciliationCheckCount).isEqualTo(1)
        assertThat(tooEarly).isEmpty()
        assertThat(second.single().record.reconciliationCheckedAt).isEqualTo("20260807120030")
        assertThat(second.single().record.reconciliationCheckCount).isEqualTo(2)
    }

    @Test
    fun `최대 추적 나이를 넘긴 미결 거래는 중단 표시 후 단건 조회 claim에서 제외한다`() {
        txRecords.insert(txRecord(vendorTxId = "tx-expired", confirmationCount = 0))
        jdbc.update("UPDATE bcm_tx_l SET frst_dtct_dttm = '20260731115959' WHERE vndr_tx_id = 'tx-expired'")

        val stopped = txRecords.markExpiredPendingStopped("20260731120000", "20260807120000")
        val claimed = txRecords.claimPendingForReconciliation("20260807115000", "20260807120000", 10)

        assertThat(stopped).isEqualTo(1)
        assertThat(claimed).isEmpty()
        assertThat(txRecords.findByVendorTxId("tx-expired")?.reconciliationStoppedAt)
            .isEqualTo("20260807120000")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `상태 판정 조회는 트랜잭션이 끝날 때까지 같은 거래의 다음 판정을 막는다`() {
        txRecords.insert(txRecord(vendorTxId = "tx-lock"))
        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val secondLocked = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val transaction =
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }

        try {
            val first =
                executor.submit {
                    transaction.executeWithoutResult {
                        assertThat(txRecords.findByVendorTxIdForUpdate("tx-lock")).isNotNull()
                        firstLocked.countDown()
                        check(releaseFirst.await(5, TimeUnit.SECONDS))
                    }
                }
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue()

            val second =
                executor.submit {
                    transaction.executeWithoutResult {
                        secondAttempted.countDown()
                        assertThat(txRecords.findByVendorTxIdForUpdate("tx-lock")).isNotNull()
                        secondLocked.countDown()
                    }
                }
            assertThat(secondAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(secondLocked.await(300, TimeUnit.MILLISECONDS)).isFalse()

            releaseFirst.countDown()
            assertThat(secondLocked.await(5, TimeUnit.SECONDS)).isTrue()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            jdbc.update("DELETE FROM bcm_tx_l WHERE vndr_tx_id = 'tx-lock'")
        }
    }

    private fun insertSubmission(vendorTransactionId: String) {
        jdbc.update(
            """
            INSERT INTO bcm_sbmt_l
              (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, claim_id, claim_exp_dttm,
               tx_dvcd, vndr_tx_id, snd_acnt_id, rcv_dvcd, rcv_vl, ntwk_cd, tkn_smbl,
               trsf_amt, req_dttm, rsp_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('wd-confirmed', ?, 'v1', 'SUBMITTED', NULL, NULL,
               'WITHDRAWAL', ?, 'account-1', 'ADDRESS', '0xTo', 'ETHEREUM', 'USDC',
               1, '20260807100000', '20260807100001',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            "a".repeat(64),
            vendorTransactionId,
        )
    }
}
