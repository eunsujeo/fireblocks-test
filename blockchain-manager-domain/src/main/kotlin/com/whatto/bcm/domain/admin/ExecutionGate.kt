package com.whatto.bcm.domain.admin

import com.whatto.bcm.domain.exception.ConflictException
import java.time.Instant

enum class ExecutionGateType {
    WITHDRAWAL,
    SWEEP,
    APPROVE,
}

enum class ExecutionGateStatus {
    STOPPED,
    RESUMED,
}

enum class ExecutionGateState {
    OPEN,
    STOPPED,
}

data class ExecutionGateEvent(
    val eventId: String,
    val network: String,
    val type: ExecutionGateType,
    val sequence: Int,
    val status: ExecutionGateStatus,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val occurredAt: Instant,
    val actor: AdminActor,
    val resumeRequestId: String? = null,
)

interface ExecutionGateRepository {
    fun findCurrent(
        network: String,
        type: ExecutionGateType,
    ): ExecutionGateEvent?

    fun lockAndFindCurrent(
        network: String,
        type: ExecutionGateType,
    ): ExecutionGateEvent? = findCurrent(network, type)

    fun findByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): ExecutionGateEvent?

    fun insert(event: ExecutionGateEvent): ExecutionGateEvent
}

data class ExecutionGateAvailability(
    val state: ExecutionGateState,
    val newExecutionAllowed: Boolean,
    val existingExecutionRecoveryAllowed: Boolean,
    val emergencyRevocationAllowed: Boolean,
    val disabledReasons: List<String>,
)

object ExecutionGatePolicy {
    fun requireOpen(current: ExecutionGateEvent?) {
        if (current?.status == ExecutionGateStatus.STOPPED) {
            throw ConflictException("executionGate", "${current.network}:${current.type}")
        }
    }

    fun availability(
        type: ExecutionGateType,
        current: ExecutionGateEvent?,
        releaseReady: Boolean = true,
        emergencyRevocationReady: Boolean = true,
    ): ExecutionGateAvailability {
        val stopped = current?.status == ExecutionGateStatus.STOPPED
        val releaseRequired = type == ExecutionGateType.SWEEP || type == ExecutionGateType.APPROVE
        return ExecutionGateAvailability(
            state = if (stopped) ExecutionGateState.STOPPED else ExecutionGateState.OPEN,
            newExecutionAllowed = !stopped && (!releaseRequired || releaseReady),
            existingExecutionRecoveryAllowed = true,
            emergencyRevocationAllowed = type == ExecutionGateType.APPROVE && emergencyRevocationReady,
            disabledReasons =
                buildList {
                    if (stopped) add("EXECUTION_GATE_STOPPED")
                    if (releaseRequired && !releaseReady) add("RELEASE_GATE_NOT_READY")
                    if (type == ExecutionGateType.APPROVE && !emergencyRevocationReady) {
                        add("EMERGENCY_REVOCATION_NOT_READY")
                    }
                },
        )
    }
}
