package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItem
import com.whatto.bcm.domain.sweep.SweepItemReconciliation
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import com.whatto.bcm.infra.persistence.sweep.fixture.SweepTargetFixture.fixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@DataJdbcTest
@Import(SweepAuthorizationJdbcAdapter::class, SweepExecutionJdbcAdapter::class, SweepTargetJdbcAdapter::class)
class SweepBatchPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var authorizations: SweepAuthorizationJdbcAdapter

    @Autowired
    lateinit var executions: SweepExecutionJdbcAdapter

    @Autowired
    lateinit var targets: SweepTargetJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `allowance 관찰 상태를 sweep 컨트랙트별로 보존한다`() {
        val authorization = authorization()

        authorizations.insert(authorization)

        assertThat(authorizations.findByKey(authorization.key)).isEqualTo(authorization)
    }

    @Test
    fun `실행 1건과 항목 N건을 만들며 대상 claim을 같은 트랜잭션으로 묶는다`() {
        targets.insertIfAbsent(fixture(accountId = "customer-1"))
        targets.insertIfAbsent(fixture(accountId = "customer-2"))
        authorizations.insert(authorization("customer-1", "20"))
        authorizations.insert(authorization("customer-2", "30"))
        val execution = execution()
        val items = listOf(item(1, "customer-1", "0xsource1", "20"), item(2, "customer-2", "0xsource2", "30"))

        executions.createAndClaim(execution, items)

        assertThat(executions.findById(execution.executionId)).isEqualTo(execution)
        assertThat(executions.findItems(execution.executionId)).containsExactlyElementsOf(items)
        assertThat(targets.findByKey(fixture(accountId = "customer-1").key))
            .extracting("activeSweepExecutionId", "activeItemSequence")
            .containsExactly(execution.executionId, 1)
        assertThat(targets.findByKey(fixture(accountId = "customer-2").key))
            .extracting("activeSweepExecutionId", "activeItemSequence")
            .containsExactly(execution.executionId, 2)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `대상 하나라도 이미 claim이면 실행과 항목을 모두 롤백한다`() {
        val accountId = "rollback-customer"
        val first =
            execution(executionId = "swx-rollback-first", externalTransactionId = "swp-rollback-first")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        val conflicting =
            execution(executionId = "swx-rollback-conflict", externalTransactionId = "swp-rollback-conflict")
                .copy(itemCount = 1, requestedTotalAmount = "20")

        try {
            targets.insertIfAbsent(fixture(accountId = accountId))
            authorizations.insert(authorization(accountId, "20"))
            executions.createAndClaim(
                first,
                listOf(item(1, accountId, "0xrollbacksource", "20", executionId = first.executionId)),
            )
            assertThatThrownBy {
                executions.createAndClaim(
                    conflicting,
                    listOf(item(1, accountId, "0xrollbacksource", "20", executionId = conflicting.executionId)),
                )
            }.isInstanceOf(ConflictException::class.java)

            assertThat(executions.findById(conflicting.executionId)).isNull()
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bcm_swp_item_l WHERE swp_exec_id IN (?, ?)",
                    Long::class.java,
                    first.executionId,
                    conflicting.executionId,
                ),
            ).isEqualTo(1)
        } finally {
            jdbc.update("UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", first.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", first.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id = ?", accountId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `allowance가 ACTIVE가 아니거나 요청 금액보다 작으면 실행 전체를 만들지 않는다`() {
        val execution =
            execution(executionId = "swx-insufficient", externalTransactionId = "swp-insufficient")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        try {
            targets.insertIfAbsent(fixture(accountId = "customer-insufficient"))
            authorizations.insert(authorization("customer-insufficient", "19"))

            assertThatThrownBy {
                executions.createAndClaim(
                    execution,
                    listOf(item(1, "customer-insufficient", "0xsource", "20", execution.executionId)),
                )
            }.isInstanceOf(ConflictException::class.java)

            assertThat(executions.findById(execution.executionId)).isNull()
            assertThat(targets.findByKey(fixture(accountId = "customer-insufficient").key)?.activeSweepExecutionId).isNull()
        } finally {
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = 'customer-insufficient'")
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id = 'customer-insufficient'")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `운영 계정별 미접수 실행은 하나이며 SUBMITTED 뒤 다음 실행을 만들 수 있다`() {
        val first =
            execution(executionId = "swx-operator-1", externalTransactionId = "swp-operator-1")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        val second =
            execution(executionId = "swx-operator-2", externalTransactionId = "swp-operator-2")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        try {
            targets.insertIfAbsent(fixture(accountId = "operator-lock-1"))
            targets.insertIfAbsent(fixture(accountId = "operator-lock-2"))
            authorizations.insert(authorization("operator-lock-1", "20"))
            authorizations.insert(authorization("operator-lock-2", "20"))
            executions.createAndClaim(first, listOf(item(1, "operator-lock-1", "0xsource1", "20", first.executionId)))

            assertThatThrownBy {
                executions.createAndClaim(second, listOf(item(1, "operator-lock-2", "0xsource2", "20", second.executionId)))
            }.isInstanceOf(ConflictException::class.java)

            assertThat(executions.findPendingSubmission("operator-1")?.executionId).isEqualTo(first.executionId)
            assertThat(executions.markSubmitting(first.executionId).status).isEqualTo(SweepExecutionStatus.SUBMITTING)
            assertThat(executions.markSubmitted(first.executionId, "vendor-batch-1").status)
                .isEqualTo(SweepExecutionStatus.SUBMITTED)
            executions.createAndClaim(second, listOf(item(1, "operator-lock-2", "0xsource2", "20", second.executionId)))
            assertThat(executions.findById(second.executionId)).isEqualTo(second)
        } finally {
            jdbc.update("UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id LIKE 'operator-lock-%'")
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id IN (?, ?)", first.executionId, second.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id IN (?, ?)", first.executionId, second.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id LIKE 'operator-lock-%'")
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id LIKE 'operator-lock-%'")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `확정 제출 거절은 READY 항목을 RETRY로 바꾸고 target claim을 해제한다`() {
        val failed =
            execution(executionId = "swx-rejected", externalTransactionId = "swp-rejected")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        val accountId = "rejected-customer"
        try {
            targets.insertIfAbsent(fixture(accountId = accountId))
            authorizations.insert(authorization(accountId, "20"))
            executions.createAndClaim(
                failed,
                listOf(item(1, accountId, "0xrejectedsource", "20", failed.executionId)),
            )

            val result = executions.markFailedAndRelease(failed.executionId, "20260812150100")

            assertThat(result.status).isEqualTo(SweepExecutionStatus.FAILED)
            assertThat(result.finishedAt).isEqualTo("20260812150100")
            assertThat(executions.findItems(failed.executionId).single().status).isEqualTo(SweepItemStatus.RETRY)
            assertThat(targets.findByKey(fixture(accountId = accountId).key)?.activeSweepExecutionId).isNull()
        } finally {
            jdbc.update("UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", failed.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", failed.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id = ?", accountId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `종결 웹훅부터 항목별 부분 성공까지 실행 상태와 결과를 보존한다`() {
        val execution = execution(executionId = "swx-reconcile", externalTransactionId = "swp-reconcile")
        val firstAccount = "reconcile-customer-1"
        val secondAccount = "reconcile-customer-2"
        try {
            targets.insertIfAbsent(fixture(accountId = firstAccount))
            targets.insertIfAbsent(fixture(accountId = secondAccount))
            authorizations.insert(authorization(firstAccount, "20"))
            authorizations.insert(authorization(secondAccount, "30"))
            executions.createAndClaim(
                execution,
                listOf(
                    item(1, firstAccount, "0xsource1", "20", execution.executionId),
                    item(2, secondAccount, "0xsource2", "30", execution.executionId),
                ),
            )
            executions.markSubmitting(execution.executionId)
            executions.markSubmitted(execution.executionId, "vendor-reconcile")

            val reconciling =
                executions.markReconciling(execution.executionId, "vendor-reconcile", "0xhash-reconcile")

            assertThat(reconciling.status).isEqualTo(SweepExecutionStatus.RECONCILING)
            assertThat(reconciling.transactionHash).isEqualTo("0xhash-reconcile")
            assertThat(executions.findReconciling(10).map { it.executionId }).contains(execution.executionId)

            val completed =
                executions.completeReconciliation(
                    execution.executionId,
                    listOf(
                        SweepItemReconciliation(1, "20", SweepItemStatus.SUCCEEDED, null, 7),
                        SweepItemReconciliation(2, "0", SweepItemStatus.FAILED, "1".padStart(64, '0'), 8),
                    ),
                    SweepExecutionStatus.PARTIAL,
                    "20",
                    "20260812150200",
                )
            assertThat(targets.deleteClaim(fixture(accountId = firstAccount).key, execution.executionId, 1)).isTrue()
            assertThat(targets.releaseClaim(fixture(accountId = secondAccount).key, execution.executionId, 2)).isTrue()

            assertThat(completed.status).isEqualTo(SweepExecutionStatus.PARTIAL)
            assertThat(completed.actualTotalAmount).isEqualTo("20")
            assertThat(completed.finishedAt).isEqualTo("20260812150200")
            assertThat(
                executions.markReconciling(execution.executionId, "vendor-reconcile", "0xhash-reconcile").status,
            ).isEqualTo(SweepExecutionStatus.PARTIAL)
            assertThat(executions.findItems(execution.executionId).map { it.status })
                .containsExactly(SweepItemStatus.SUCCEEDED, SweepItemStatus.FAILED)
            assertThat(targets.findByKey(fixture(accountId = firstAccount).key)).isNull()
            assertThat(targets.findByKey(fixture(accountId = secondAccount).key)?.activeSweepExecutionId).isNull()
        } finally {
            jdbc.update(
                "UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id IN (?, ?)",
                firstAccount,
                secondAccount,
            )
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id IN (?, ?)", firstAccount, secondAccount)
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id IN (?, ?)", firstAccount, secondAccount)
        }
    }

    private fun authorization(
        accountId: String = "customer-1",
        observedAllowance: String = "80",
    ) = SweepAuthorization(
        key = SweepAuthorizationKey(accountId, "ETHEREUM", "USDC", "0xsweeper"),
        allowanceCap = "100",
        observedAllowance = observedAllowance,
        status = SweepAuthorizationStatus.ACTIVE,
        approvalExternalTransactionId = "swa-1",
        approvalVendorTransactionId = "vendor-approve-1",
        lastCheckedAt = "20260812150000",
    )

    private fun execution(
        executionId: String = "swx-1",
        externalTransactionId: String = "swp-1",
    ) = SweepExecution(
        executionId = executionId,
        externalTransactionId = externalTransactionId,
        requestHash = "a".repeat(64),
        network = "ETHEREUM",
        symbol = "USDC",
        operatorAccountId = "operator-1",
        sweepContractAddress = "0xsweeper",
        status = SweepExecutionStatus.READY,
        itemCount = 2,
        requestedTotalAmount = "50",
        actualTotalAmount = null,
        gasless = true,
        vendorTransactionId = null,
        transactionHash = null,
        requestedAt = "20260812150000",
        finishedAt = null,
    )

    private fun item(
        sequence: Int,
        accountId: String,
        sourceAddress: String,
        amount: String,
        executionId: String = "swx-1",
    ) = SweepItem(
        executionId = executionId,
        sequence = sequence,
        accountId = accountId,
        sourceAddress = sourceAddress,
        requestedAmount = amount,
        actualAmount = null,
        status = SweepItemStatus.READY,
        failureCode = null,
        logIndex = null,
    )
}
