package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateRepository
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class ExecutionGateCommandService(
    private val gates: ExecutionGateRepository,
    private val networks: VendorBlockchainCatalogRepository,
    private val transactions: TransactionRunner,
    private val ids: EventIdGenerator,
    private val clock: Clock,
) {
    fun stop(command: StopExecutionGateCommand): ExecutionGateEvent =
        transactions.run {
            check(AdminRole.BCM_OPERATOR in command.actor.roles) { "BCM_OPERATOR role is required" }
            require(command.reason.isNotBlank()) { "execution gate stop reason is required" }
            require(command.workTicket.isNotBlank()) { "execution gate stop work ticket is required" }
            require(command.idempotencyKey.isNotBlank()) { "execution gate stop idempotency key is required" }
            networks.findByNetwork(command.network)
                ?: throw ResourceNotFoundException("blockchainNetwork", command.network)
            gates.findByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
                if (existing.sameStopAs(command)) return@run existing
                throw ConflictException("executionGateIdempotency", command.idempotencyKey)
            }
            val current = gates.findCurrent(command.network, command.type)
            if (current?.status == ExecutionGateStatus.STOPPED) return@run current
            val candidate =
                ExecutionGateEvent(
                    ids.nextId(),
                    command.network,
                    command.type,
                    (current?.sequence ?: 0) + 1,
                    ExecutionGateStatus.STOPPED,
                    command.reason,
                    command.workTicket,
                    command.idempotencyKey,
                    now(),
                    command.actor,
                )
            try {
                gates.insert(candidate)
            } catch (conflict: ConflictException) {
                gates
                    .findCurrent(command.network, command.type)
                    ?.takeIf { it.status == ExecutionGateStatus.STOPPED }
                    ?: throw conflict
            }
        }

    private fun ExecutionGateEvent.sameStopAs(command: StopExecutionGateCommand): Boolean =
        network == command.network &&
            type == command.type &&
            reason == command.reason &&
            workTicket == command.workTicket

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)
}

data class StopExecutionGateCommand(
    val network: String,
    val type: ExecutionGateType,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val actor: AdminActor,
)
