package com.whatto.bcm.infra.persistence.admin.fixture

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import java.time.Instant

object ExecutionGateFixture {
    fun event(
        eventId: String = "gate-event-1",
        network: String = "BASE",
        type: ExecutionGateType = ExecutionGateType.WITHDRAWAL,
        idempotencyKey: String = "stop-base-withdrawal",
    ) = ExecutionGateEvent(
        eventId,
        network,
        type,
        1,
        ExecutionGateStatus.STOPPED,
        "이상 거래 조사",
        "INC-100",
        idempotencyKey,
        Instant.parse("2026-08-17T12:00:00Z"),
        AdminActor("830001", "0001", emptySet()),
    )
}
