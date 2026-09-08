package com.whatto.bcm.domain.sweep

/** 과거 성공 이벤트를 덮어쓰지 않고 같은 실행의 새 무효화 결과를 만든다. */
object SweepInvalidation {
    const val FAILURE_CODE = "SWEEP_REORG"

    fun canInvalidate(status: SweepExecutionStatus): Boolean =
        status == SweepExecutionStatus.COMPLETED || status == SweepExecutionStatus.PARTIAL

    fun events(
        execution: SweepExecution,
        items: List<SweepItem>,
    ): List<SweepItemOutcomeEvent> =
        items.map { item ->
            SweepItemOutcomeEvent(
                sweepRequestId = item.sweepRequestId,
                sweepItemId = item.sweepRequestItemId,
                executionId = execution.executionId,
                txId = checkNotNull(execution.vendorTransactionId),
                vendorTxId = execution.vendorTransactionId,
                txHash = execution.transactionHash,
                accountId = item.accountId,
                network = execution.network,
                symbol = execution.symbol,
                requestedAmount = item.requestedAmount,
                actualAmount = "0",
                chainStatus = SweepChainStatus.FAILED,
                itemOutcome = SweepItemOutcome.FAILED,
                failureCode = FAILURE_CODE,
            )
        }
}
