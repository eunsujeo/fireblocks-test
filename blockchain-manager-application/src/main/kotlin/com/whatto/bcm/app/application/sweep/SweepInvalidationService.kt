package com.whatto.bcm.app.application.sweep

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.sweep.SweepEventPublisher
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepInvalidation
import com.whatto.bcm.domain.tx.TxStatus
import org.springframework.stereotype.Service

@Service
class SweepInvalidationService(
    private val executions: SweepExecutionRepository,
    private val publisher: SweepEventPublisher,
) {
    /** 호출자는 tx 상태 관찰과 같은 DB 트랜잭션에서 호출한다. true면 별도 항목 대사가 필요 없다. */
    fun invalidate(
        executionId: String,
        vendorTransactionId: String,
        transactionHash: String?,
        status: TxStatus,
        observedAt: String,
    ): Boolean {
        if (status != TxStatus.FAILED) return false
        val execution = executions.findByIdForUpdate(executionId) ?: throw ResourceNotFoundException("sweepExecution", executionId)
        if (!SweepInvalidation.canInvalidate(execution.status) && execution.status != SweepExecutionStatus.FAILED) return false
        if (execution.vendorTransactionId != vendorTransactionId ||
            (transactionHash != null && execution.transactionHash?.equals(transactionHash, ignoreCase = true) != true)
        ) {
            throw ConflictException("sweepExecution", executionId)
        }
        if (execution.status == SweepExecutionStatus.FAILED) return true
        val items = executions.findItems(executionId)
        check(items.size == execution.itemCount) { "sweep invalidation item count mismatch: executionId=$executionId" }
        executions.invalidateFinalized(executionId, SweepInvalidation.FAILURE_CODE, observedAt)
        publisher.publish(SweepInvalidation.events(execution, items))
        return true
    }
}
