package com.whatto.bcm.app.application.admin.fixture

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import java.time.Instant

object ExecutionGateFixture {
    fun event(
        idempotencyKey: String = "stop-1",
        actor: AdminActor = operator(),
    ) = ExecutionGateEvent(
        "gate-event-1",
        "BASE",
        ExecutionGateType.WITHDRAWAL,
        1,
        ExecutionGateStatus.STOPPED,
        "이상 거래 조사",
        "INC-100",
        idempotencyKey,
        Instant.parse("2026-08-17T12:00:00Z"),
        actor,
    )

    fun operator() = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR))
}
