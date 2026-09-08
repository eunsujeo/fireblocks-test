package com.whatto.bcm.domain.sweep

data class SweepExecution(
    val executionId: String,
    val externalTransactionId: String,
    val requestHash: String,
    val network: String,
    val symbol: String,
    val operatorAccountId: String,
    val sweepContractAddress: String,
    val status: SweepExecutionStatus,
    val itemCount: Int,
    val requestedTotalAmount: String,
    val actualTotalAmount: String?,
    val gasless: Boolean,
    val vendorTransactionId: String?,
    val transactionHash: String?,
    val requestedAt: String,
    val finishedAt: String?,
    val policyVersionId: String,
    val policySnapshotHash: String,
    val contractVersionId: String,
    val contractEvidenceId: String,
)

enum class SweepExecutionStatus {
    READY,
    SUBMITTING,
    SUBMITTED,
    RECONCILING,
    COMPLETED,
    PARTIAL,
    FAILED,
}

data class SweepItem(
    val executionId: String,
    val sequence: Int,
    val sweepRequestId: String,
    val sweepRequestItemId: String,
    val accountId: String,
    val sourceAddress: String,
    val requestedAmount: String,
    val actualAmount: String?,
    val status: SweepItemStatus,
    val failureCode: String?,
    val logIndex: Int?,
)

data class SweepItemReconciliation(
    val sequence: Int,
    val actualAmount: String,
    val status: SweepItemStatus,
    val failureCode: String?,
    val logIndex: Int,
    val requestCompleted: Boolean = status == SweepItemStatus.SUCCEEDED,
)

enum class SweepItemStatus {
    READY,
    SUCCEEDED,
    FAILED,
    RETRY,
}

interface SweepExecutionRepository {
    /** 실행·항목을 선기록하고 대상 N건을 같은 트랜잭션으로 claim한다. */
    fun createAndClaim(
        execution: SweepExecution,
        items: List<SweepItem>,
    )

    fun findById(executionId: String): SweepExecution?

    fun findByIdForUpdate(executionId: String): SweepExecution?

    fun findItems(executionId: String): List<SweepItem>

    /** 같은 운영 계정에서 아직 벤더 접수가 끝나지 않은 단 하나의 실행. */
    fun findPendingSubmission(operatorAccountId: String): SweepExecution?

    fun findReconciling(limit: Int): List<SweepExecution>

    /** READY 실행을 SUBMITTING으로 옮긴다. 최초 제출 시도는 createAndClaim의 target claim이 이미 1회로 센다. */
    fun markSubmitting(executionId: String): SweepExecution

    /** FAILED submission을 같은 externalTxId로 실제 재획득한 트랜잭션에서 target 시도 횟수를 증가시킨다. */
    fun recordSubmissionRetry(
        executionId: String,
        attemptedAt: String,
    ): SweepExecution

    fun markSubmitted(
        executionId: String,
        vendorTransactionId: String,
    ): SweepExecution

    /** 종결 웹훅은 항목 성공을 추측하지 않고 실행만 대사 대기 상태로 옮긴다. */
    fun markReconciling(
        executionId: String,
        vendorTransactionId: String,
        transactionHash: String?,
    ): SweepExecution

    /** 영수증 대사 결과를 실행과 N개 항목에 반영한다. 호출 트랜잭션 안에서 target 정리와 함께 쓴다. */
    fun completeReconciliation(
        executionId: String,
        items: List<SweepItemReconciliation>,
        status: SweepExecutionStatus,
        actualTotalAmount: String,
        finishedAt: String,
    ): SweepExecution

    /** 확정 거절된 실행·항목을 FAILED/RETRY 처리하고 target을 같은 트랜잭션에서 해제한다. */
    fun markFailedAndRelease(
        executionId: String,
        failureCode: String,
        finishedAt: String,
    ): SweepExecution

    /** 이미 대사한 실행을 무효화하고 원 요청을 다시 연다. 후속 실행의 claim은 유지한다. */
    fun invalidateFinalized(
        executionId: String,
        failureCode: String,
        finishedAt: String,
    )
}
