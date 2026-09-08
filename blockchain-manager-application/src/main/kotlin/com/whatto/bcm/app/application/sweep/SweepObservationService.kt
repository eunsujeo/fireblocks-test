package com.whatto.bcm.app.application.sweep

import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import org.springframework.stereotype.Service

/** 체인 관찰을 항목 대사에 연결한다. 호출자의 tx 관찰 트랜잭션을 그대로 사용한다. */
@Service
class SweepObservationService(
    private val repository: SweepExecutionRepository,
) {
    fun markReconciling(
        executionId: String,
        vendorTransactionId: String,
        transactionHash: String?,
    ): SweepExecution = repository.markReconciling(executionId, vendorTransactionId, transactionHash)
}
