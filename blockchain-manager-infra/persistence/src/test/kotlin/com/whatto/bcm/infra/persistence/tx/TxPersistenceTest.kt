package com.whatto.bcm.infra.persistence.tx

import com.whatto.bcm.domain.exception.ConflictException
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
        externalTxId: String? = null,
        status: TxStatus = TxStatus.CONFIRMED,
    ) = TxRecord(
        vendorTxId = vendorTxId,
        externalTxId = externalTxId,
        accountId = "acct_01",
        network = "ETHEREUM",
        symbol = "USDC",
        lastPublishedStatus = status,
        confirmationCount = 1,
        vendorSubStatus = "PENDING_BLOCKCHAIN_CONFIRMATIONS",
        vendorNetworkStatus = "CONFIRMING",
        firstDetectedAt = "20260805120000",
        lastChangedAt = "20260805120000",
    )

    @Test
    fun `거래 왕복 — 벤더 원어 보관 컬럼(subStatus·networkStatus)까지 그대로 되찾는다`() {
        val saved = txRecords.insert(txRecord())
        assertThat(txRecords.findByVendorTxId("tx-91c")).isEqualTo(saved)
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
}
