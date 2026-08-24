package com.whatto.bcm.domain.admin

import java.math.BigDecimal
import java.time.Instant

data class AllowanceRevocationExecution(
    val executionId: String,
    val contractVersionId: String,
    val contractBindingRevision: Long,
    val network: String,
    val sweepContractAddress: String,
    val targetSnapshotHash: String,
    val itemCount: Int,
    val idempotencyKey: String,
    val registeredAt: Instant,
    val registeredBy: AdminActor,
)

data class AllowanceRevocationTarget(
    val executionId: String,
    val itemSequence: Int,
    val accountId: String,
    val network: String,
    val symbol: String,
    val sweepContractAddress: String,
    val sourceVaultId: String,
    val ownerAddress: String,
    val tokenContractAddress: String,
    val beforeObservedAllowance: BigDecimal,
    val externalTransactionId: String,
    val requestHash: String,
)

enum class AllowanceRevocationEventStatus {
    RESERVED,
    SUBMIT_INTENT,
    SUBMITTED,
    ZERO_CONFIRMED,
    FAILED,
}

data class AllowanceRevocationEvent(
    val executionId: String,
    val itemSequence: Int,
    val eventSequence: Int,
    val status: AllowanceRevocationEventStatus,
    val externalTransactionId: String?,
    val vendorTransactionId: String?,
    val observedAllowance: BigDecimal?,
    val observationPayload: String,
    val observationHash: String,
    val errorCode: String?,
    val occurredAt: Instant,
    val actor: AdminActor,
)

enum class AllowanceRevocationExecutionStatus {
    READY,
    IN_PROGRESS,
    PARTIAL,
    COMPLETED,
}

data class AllowanceRevocationView(
    val execution: AllowanceRevocationExecution,
    val targets: List<AllowanceRevocationTarget>,
    val events: List<AllowanceRevocationEvent>,
    val status: AllowanceRevocationExecutionStatus,
)

interface AllowanceRevocationRepository {
    fun insertExecution(
        execution: AllowanceRevocationExecution,
        targets: List<AllowanceRevocationTarget>,
    ): AllowanceRevocationExecution

    fun findExecution(executionId: String): AllowanceRevocationView?

    fun findExecutionByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): AllowanceRevocationExecution?

    fun insertExecutionIntent(
        actionId: String,
        correlationId: String,
        request: AdminChangeRequest,
        idempotencyKey: String,
        requestHash: String,
        expectedState: String,
        expectedStateHash: String,
        actor: AdminActor,
        now: Instant,
    )

    fun appendEvent(event: AllowanceRevocationEvent): AllowanceRevocationEvent
}

object AllowanceRevocationLifecycle {
    fun executionStatus(
        itemCount: Int,
        events: List<AllowanceRevocationEvent>,
    ): AllowanceRevocationExecutionStatus {
        require(itemCount > 0) { "allowance revocation must have at least one item" }
        val latestByItem = events.groupBy { it.itemSequence }.mapValues { (_, itemEvents) -> itemEvents.maxBy { it.eventSequence } }
        return executionStatusFromLatest(itemCount, latestByItem.values.map { it.status })
    }

    fun executionStatusFromLatest(
        itemCount: Int,
        latestStatuses: Collection<AllowanceRevocationEventStatus?>,
    ): AllowanceRevocationExecutionStatus {
        require(itemCount > 0) { "allowance revocation must have at least one item" }
        if (latestStatuses.none { it != null }) return AllowanceRevocationExecutionStatus.READY
        val confirmedCount = latestStatuses.count { it == AllowanceRevocationEventStatus.ZERO_CONFIRMED }
        return when {
            confirmedCount == itemCount -> AllowanceRevocationExecutionStatus.COMPLETED
            confirmedCount > 0 -> AllowanceRevocationExecutionStatus.PARTIAL
            else -> AllowanceRevocationExecutionStatus.IN_PROGRESS
        }
    }

    fun validateAppend(
        target: AllowanceRevocationTarget,
        existingEvents: List<AllowanceRevocationEvent>,
        candidate: AllowanceRevocationEvent,
    ) {
        require(candidate.executionId == target.executionId) { "allowance revocation execution does not match target" }
        require(candidate.itemSequence == target.itemSequence) { "allowance revocation item does not match target" }
        val ordered = existingEvents.filter { it.itemSequence == target.itemSequence }.sortedBy { it.eventSequence }
        require(candidate.eventSequence == (ordered.lastOrNull()?.eventSequence ?: 0) + 1) {
            "allowance revocation event sequence must be contiguous"
        }
        validateFields(target, candidate)
        val previous = ordered.lastOrNull()?.status
        val allowed =
            when (previous) {
                null -> setOf(AllowanceRevocationEventStatus.RESERVED)
                AllowanceRevocationEventStatus.RESERVED ->
                    setOf(
                        AllowanceRevocationEventStatus.SUBMIT_INTENT,
                        AllowanceRevocationEventStatus.ZERO_CONFIRMED,
                        AllowanceRevocationEventStatus.FAILED,
                    )

                AllowanceRevocationEventStatus.SUBMIT_INTENT ->
                    setOf(
                        AllowanceRevocationEventStatus.SUBMIT_INTENT,
                        AllowanceRevocationEventStatus.SUBMITTED,
                        AllowanceRevocationEventStatus.ZERO_CONFIRMED,
                        AllowanceRevocationEventStatus.FAILED,
                    )

                AllowanceRevocationEventStatus.SUBMITTED ->
                    setOf(
                        AllowanceRevocationEventStatus.ZERO_CONFIRMED,
                        AllowanceRevocationEventStatus.FAILED,
                    )

                AllowanceRevocationEventStatus.FAILED ->
                    setOf(
                        AllowanceRevocationEventStatus.SUBMIT_INTENT,
                        AllowanceRevocationEventStatus.SUBMITTED,
                        AllowanceRevocationEventStatus.ZERO_CONFIRMED,
                    )

                AllowanceRevocationEventStatus.ZERO_CONFIRMED -> emptySet()
            }
        require(candidate.status in allowed) {
            "allowance revocation transition is not allowed: $previous -> ${candidate.status}"
        }
    }

    private fun validateFields(
        target: AllowanceRevocationTarget,
        event: AllowanceRevocationEvent,
    ) {
        require(event.observationHash.matches(Regex("[0-9a-f]{64}"))) { "observation hash must be lowercase SHA-256" }
        require(event.observedAllowance == null || event.observedAllowance.signum() >= 0) {
            "observed allowance must not be negative"
        }
        when (event.status) {
            AllowanceRevocationEventStatus.RESERVED -> {
                require(event.externalTransactionId == null && event.vendorTransactionId == null)
                require(event.observedAllowance == null && event.errorCode == null)
            }

            AllowanceRevocationEventStatus.SUBMIT_INTENT -> {
                require(event.externalTransactionId == target.externalTransactionId)
                require(event.vendorTransactionId == null && event.errorCode == null)
            }

            AllowanceRevocationEventStatus.SUBMITTED -> {
                require(event.externalTransactionId == target.externalTransactionId)
                require(!event.vendorTransactionId.isNullOrBlank() && event.errorCode == null)
            }

            AllowanceRevocationEventStatus.ZERO_CONFIRMED -> {
                require(event.externalTransactionId == null && event.vendorTransactionId == null)
                require(event.observedAllowance?.signum() == 0 && event.errorCode == null)
            }

            AllowanceRevocationEventStatus.FAILED -> require(!event.errorCode.isNullOrBlank())
        }
    }
}
