package com.whatto.bcm.domain.admin.fixture

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ContractEvidenceCandidate
import com.whatto.bcm.domain.admin.ContractExpectedState
import com.whatto.bcm.domain.admin.ExternalControlEvidence
import com.whatto.bcm.domain.admin.PolicyChangeRequest
import com.whatto.bcm.domain.admin.RpcObservation
import java.math.BigInteger
import java.time.Instant

object AdminPolicyFixture {
    fun actor(
        employeeNo: String = "123456",
        role: AdminRole = AdminRole.BCM_OPERATOR,
    ) = AdminActor(employeeNo, "0001", setOf(role))

    fun request(
        requester: AdminActor = actor(),
        risk: ChangeRisk = ChangeRisk.GENERAL,
        snapshotHash: String = "a".repeat(64),
        baseBindingRevision: Long = 0,
        expiresAt: Instant = Instant.parse("2026-08-17T13:00:00Z"),
    ) = PolicyChangeRequest(
        requestId = "req-1",
        requester = requester,
        risk = risk,
        targetSnapshotHash = snapshotHash,
        baseBindingRevision = baseBindingRevision,
        targetVersionId = "policy-v2",
        expiresAt = expiresAt,
    )

    fun decision(
        employeeNo: String = "222222",
        role: AdminRole = AdminRole.BCM_APPROVER,
        decision: ChangeDecision = ChangeDecision.APPROVE,
        snapshotHash: String = "a".repeat(64),
    ) = com.whatto.bcm.domain.admin.PolicyDecision(
        requestId = "req-1",
        actor = actor(employeeNo, role),
        decision = decision,
        snapshotHash = snapshotHash,
        decidedAt = Instant.parse("2026-08-17T12:10:00Z"),
    )

    fun evidence(
        first: RpcObservation? = observation("rpc-a"),
        second: RpcObservation? = observation("rpc-b"),
        controls: ExternalControlEvidence = ExternalControlEvidence.allPassed(),
        validUntil: Instant = Instant.parse("2026-08-17T13:00:00Z"),
    ) = ContractEvidenceCandidate(
        expected = ContractExpectedState(8453, "b".repeat(64), "c".repeat(64), BigInteger.valueOf(1234)),
        first = first,
        second = second,
        controls = controls,
        observedAt = Instant.parse("2026-08-17T12:00:00Z"),
        validUntil = validUntil,
    )

    fun observation(
        endpointId: String,
        chainId: Long = 8453,
        codeHash: String = "b".repeat(64),
        immutableHash: String = "c".repeat(64),
        blockNumber: BigInteger = BigInteger.valueOf(1234),
    ) = RpcObservation(
        endpointId = endpointId,
        chainId = chainId,
        codeHash = codeHash,
        immutableHash = immutableHash,
        blockNumber = blockNumber,
        observedAt = Instant.parse("2026-08-17T12:00:00Z"),
    )
}
