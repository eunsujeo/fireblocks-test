package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateRepository
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.sweep.SweepExecutionGatePort
import com.whatto.bcm.domain.sweep.SweepExecutionGateSnapshot
import java.time.Instant

class FakeExecutionGates(
    var stopped: Set<Pair<String, ExecutionGateType>> = emptySet(),
    var resumed: Set<Pair<String, ExecutionGateType>> = emptySet(),
) : ExecutionGateRepository,
    SweepExecutionGatePort {
    override fun findCurrent(network: String): SweepExecutionGateSnapshot =
        SweepExecutionGateSnapshot(
            network,
            if (network to ExecutionGateType.SWEEP in stopped || network to ExecutionGateType.SWEEP in resumed) 1 else 0,
            if (network to ExecutionGateType.SWEEP in stopped) "STOPPED" else "OPEN",
        )

    override fun findCurrent(
        network: String,
        type: ExecutionGateType,
    ): ExecutionGateEvent? =
        if (network to type in stopped || network to type in resumed) {
            ExecutionGateEvent(
                "gate-$network-$type",
                network,
                type,
                1,
                if (network to type in stopped) ExecutionGateStatus.STOPPED else ExecutionGateStatus.RESUMED,
                "test stop",
                "TEST-1",
                "stop-$network-$type",
                Instant.parse("2026-08-17T12:00:00Z"),
                AdminActor("SYSTEM", "9999", emptySet()),
            )
        } else {
            null
        }

    override fun findByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): ExecutionGateEvent? = error("not used")

    override fun insert(event: ExecutionGateEvent): ExecutionGateEvent = error("not used")
}
