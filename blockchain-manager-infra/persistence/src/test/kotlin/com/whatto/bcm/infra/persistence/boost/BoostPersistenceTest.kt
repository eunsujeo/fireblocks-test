package com.whatto.bcm.infra.persistence.boost

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.BoostAttemptAcquisition
import com.whatto.bcm.domain.tx.BoostIntentRequest
import com.whatto.bcm.domain.tx.BoostStatus
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorFeeLevel
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import com.whatto.bcm.infra.persistence.tx.TxJdbcAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@DataJdbcTest
@Import(BoostJdbcAdapter::class, TxJdbcAdapter::class)
class BoostPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var boosts: BoostJdbcAdapter

    @Autowired
    lateinit var transactions: TxJdbcAdapter

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `boost intent를 선기록하고 같은 root의 시도 순번을 증가시킨다`() {
        val candidate = candidate(transactions.insert(record()))

        val first = acquire(candidate, request("bst-first"), maximumAttempts = 3)
        boosts.markFailedByClaim("tx-root", 1, "claim-1", NOW)
        val second = acquire(candidate, request("bst-second", claimId = "claim-2"), maximumAttempts = 3)

        assertThat(first)
            .isInstanceOfSatisfying(BoostAttemptAcquisition.Acquired::class.java) {
                assertThat(it.newIntent).isTrue()
                assertThat(it.attempt.externalTransactionId).isEqualTo("bst-first")
                assertThat(it.attempt.status).isEqualTo(BoostStatus.REQUESTED)
            }
        assertThat(second).isEqualTo(BoostAttemptAcquisition.Acquired(boosts.required("tx-root", 2), true))
        assertThat(boosts.required("tx-root", 2))
            .extracting(
                "externalTransactionId",
                "status",
                "replacementVendorTransactionId",
                "replacementTransactionHash",
                "feeLevel",
                "useGasless",
            ).containsExactly("bst-second", BoostStatus.REQUESTED, "tx-root", "0xabc", VendorFeeLevel.HIGH, true)
    }

    @Test
    fun `만료 전 claim은 재획득하지 않고 만료 뒤 같은 intent를 회수한다`() {
        val candidate = candidate(transactions.insert(record()))
        acquire(candidate, request("bst-recover"), maximumAttempts = 3)

        assertThat(acquire(candidate, request("bst-unused", claimId = "claim-2"), maximumAttempts = 3, now = NOW))
            .isEqualTo(BoostAttemptAcquisition.InProgress)
        assertThat(
            acquire(
                candidate,
                request("bst-unused", claimId = "claim-2", claimExpiresAt = "20260807120200"),
                maximumAttempts = 3,
                now = "20260807120101",
            ),
        ).isEqualTo(BoostAttemptAcquisition.Acquired(boosts.required("tx-root", 1), false))
        assertThat(boosts.required("tx-root", 1).claimId).isEqualTo("claim-2")
        assertThat(boosts.findByExternalTransactionId("bst-recover")?.trySequence).isEqualTo(1)
    }

    @Test
    fun `최대 시도에 도달하면 새 intent를 만들지 않는다`() {
        val candidate = candidate(transactions.insert(record()))
        acquire(candidate, request("bst-only"), maximumAttempts = 1)
        boosts.markFailedByClaim("tx-root", 1, "claim-1", NOW)

        assertThat(acquire(candidate, request("bst-over", claimId = "claim-2"), maximumAttempts = 1))
            .isEqualTo(BoostAttemptAcquisition.MaximumAttemptsReached)
        assertThat(boosts.findByExternalTransactionId("bst-over")).isNull()
    }

    @Test
    fun `대체 거래 접수와 root active 전환을 한 트랜잭션으로 기록한다`() {
        val root = transactions.insert(record(transactionHash = "0xabc", externalTransactionId = "wd-root"))
        acquire(candidate(root), request("bst-submit"), maximumAttempts = 3)

        transaction {
            boosts.markSubmittedByClaim("tx-root", 1, "claim-1", "tx-new", NOW)
        }

        assertThat(boosts.required("tx-root", 1))
            .extracting("status", "newVendorTransactionId", "respondedAt", "claimId")
            .containsExactly(BoostStatus.SUBMITTED, "tx-new", NOW, null)
        assertThat(transactions.findByVendorTxId("tx-root"))
            .extracting("vendorTxId", "activeVendorTxId", "externalTxId", "transactionHash")
            .containsExactly("tx-root", "tx-new", "wd-root", null)
    }

    @Test
    fun `root active가 먼저 바뀌어도 벤더에 실존하는 boost 결과는 남긴다`() {
        val root = transactions.insert(record(transactionHash = "0xabc"))
        acquire(candidate(root), request("bst-race"), maximumAttempts = 3)
        jdbc.update("UPDATE bcm_tx_l SET actv_tx_id = 'tx-other' WHERE vndr_tx_id = 'tx-root'")

        transaction {
            boosts.markSubmittedByClaim("tx-root", 1, "claim-1", "tx-new", NOW)
        }

        assertThat(boosts.required("tx-root", 1))
            .extracting("status", "newVendorTransactionId", "claimId")
            .containsExactly(BoostStatus.SUBMITTED, "tx-new", null)
        assertThat(transactions.findByVendorTxId("tx-root")?.activeVendorTxId).isEqualTo("tx-other")
    }

    @Test
    fun `다른 claim은 boost 결과를 마감할 수 없다`() {
        val root = transactions.insert(record(transactionHash = "0xabc"))
        acquire(candidate(root), request("bst-claim"), maximumAttempts = 3)

        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                transaction {
                    boosts.markSubmittedByClaim("tx-root", 1, "claim-other", "tx-new", NOW)
                }
            }.isInstanceOf(ConflictException::class.java)

        assertThat(boosts.required("tx-root", 1).status).isEqualTo(BoostStatus.REQUESTED)
    }

    private fun acquire(
        candidate: StallCandidate,
        request: BoostIntentRequest,
        maximumAttempts: Int,
        now: String = NOW,
    ): BoostAttemptAcquisition = transaction { boosts.acquire(candidate.record, request, maximumAttempts, now) }

    private fun <T> transaction(block: () -> T): T = checkNotNull(TransactionTemplate(transactionManager).execute { block() })

    private fun request(
        externalTransactionId: String,
        claimId: String = "claim-1",
        claimExpiresAt: String = "20260807120100",
    ) = BoostIntentRequest(
        externalTransactionId = externalTransactionId,
        claimId = claimId,
        claimExpiresAt = claimExpiresAt,
        replacementVendorTransactionId = "tx-root",
        replacementTransactionHash = "0xabc",
        feeLevel = VendorFeeLevel.HIGH,
        useGasless = true,
        requestedAt = NOW,
    )

    private fun candidate(record: TxRecord) = StallCandidate(record, SubmissionTransactionType.WITHDRAWAL)

    private fun record(
        transactionHash: String? = null,
        externalTransactionId: String? = null,
    ) = TxRecord(
        vendorTxId = "tx-root",
        externalTxId = externalTransactionId,
        accountId = "account-1",
        network = "ETHEREUM",
        symbol = "USDC",
        transactionHash = transactionHash,
        lastPublishedStatus = TxStatus.CONFIRMED,
        confirmationCount = 0,
        firstDetectedAt = "20260807110000",
        lastChangedAt = "20260807110000",
    )

    private companion object {
        const val NOW = "20260807120000"
    }
}
