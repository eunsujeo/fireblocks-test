package com.whatto.bcm.domain.admin.fixture

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import java.time.Instant

object ExecutionGateFixture {
    fun event(
        eventId: String = "gate-event-1",
        network: String = "BASE",
        type: ExecutionGateType = ExecutionGateType.WITHDRAWAL,
        sequence: Int = 1,
        reason: String = "이상 거래 조사",
        workTicket: String = "INC-100",
        idempotencyKey: String = "stop-base-withdrawal",
        actor: AdminActor = operator(),
    ) = ExecutionGateEvent(
        eventId,
        network,
        type,
        sequence,
        ExecutionGateStatus.STOPPED,
        reason,
        workTicket,
        idempotencyKey,
        Instant.parse("2026-08-17T12:00:00Z"),
        actor,
    )

    fun operator() = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR))
}
