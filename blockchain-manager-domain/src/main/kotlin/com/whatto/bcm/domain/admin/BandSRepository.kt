package com.whatto.bcm.domain.admin

import java.time.Instant

data class BandSExecutionRecord(
    val executionId: String,
    val requestId: String,
    val proposalId: String,
    val snapshotId: String,
    val policyVersionId: String,
    val proposalHash: String,
    val inputHash: String,
    val idempotencyKey: String,
    val executionHash: String,
    val reservedAt: Instant,
    val reservedBy: AdminActor,
)

data class BandSExecutionEvent(
    val executionId: String,
    val itemSequence: Int,
    val eventSequence: Int,
    val status: BandSExecutionEventStatus,
    val externalTransactionId: String?,
    val vendorTransactionId: String?,
    val observationPayload: String,
    val observationHash: String,
    val occurredAt: Instant,
    val actor: AdminActor,
)

data class BandSExecutionView(
    val execution: BandSExecutionRecord,
    val itemStates: List<BandSItemState>,
    val status: BandSExecutionStatus,
)

interface BandSRepository {
    fun insertSnapshot(
        snapshot: BandSInputSnapshot,
        actor: AdminActor,
    ): BandSInputSnapshot

    fun findSnapshot(snapshotId: String): BandSInputSnapshot?

    fun findSnapshotBySourceRequest(sourceRequestId: String): BandSInputSnapshot?

    fun insertProposal(
        proposal: BandSProposal,
        actor: AdminActor,
        now: Instant,
    ): BandSProposal

    fun findProposal(proposalId: String): BandSProposal?

    fun findProposalBySourceId(sourceProposalId: String): BandSProposal?

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

    fun insertExecution(
        record: BandSExecutionRecord,
        items: List<BandSProposalItem>,
    ): BandSExecutionRecord

    fun findExecution(
        requestId: String,
        idempotencyKey: String,
    ): BandSExecutionRecord?

    fun findExecution(executionId: String): BandSExecutionView?

    fun appendEvent(event: BandSExecutionEvent): BandSExecutionEvent
}
