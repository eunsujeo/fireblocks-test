package com.whatto.bcm.app.application.admin

import com.whatto.bcm.app.application.submission.ManagedTransactionSubmissionCommand
import com.whatto.bcm.app.application.submission.TransactionSubmissionService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminDecisionRecord
import com.whatto.bcm.domain.admin.AdminPolicyLifecycle
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.BandSDirection
import com.whatto.bcm.domain.admin.BandSExecutionBoundary
import com.whatto.bcm.domain.admin.BandSExecutionEvent
import com.whatto.bcm.domain.admin.BandSExecutionEventStatus
import com.whatto.bcm.domain.admin.BandSExecutionGuard
import com.whatto.bcm.domain.admin.BandSExecutionRecord
import com.whatto.bcm.domain.admin.BandSExecutionView
import com.whatto.bcm.domain.admin.BandSInputSnapshot
import com.whatto.bcm.domain.admin.BandSItemSubmissionDecision
import com.whatto.bcm.domain.admin.BandSItemSubmissionGuard
import com.whatto.bcm.domain.admin.BandSLegType
import com.whatto.bcm.domain.admin.BandSProposal
import com.whatto.bcm.domain.admin.BandSProposalItem
import com.whatto.bcm.domain.admin.BandSRepository
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.admin.PolicyChangeRequest
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class BandSCommandService(
    private val bands: BandSRepository,
    private val policies: AdminPolicyRepository,
    private val boundary: BandSExecutionBoundary,
    private val transactions: TransactionRunner,
    private val ids: EventIdGenerator,
    private val clock: Clock,
    private val submissions: TransactionSubmissionService,
) {
    fun registerSnapshot(command: RegisterBandSSnapshotCommand): BandSInputSnapshot =
        transactions.run {
            requireOperator(command.actor)
            val policy =
                policies.findPolicyVersion(command.policyVersionId)
                    ?: throw ResourceNotFoundException("policyVersion", command.policyVersionId)
            val binding =
                policies.lockPolicyBinding(policy.scopeId)
                    ?: throw ResourceNotFoundException("policyBinding", policy.scopeId)
            check(binding.activeVersionId == policy.versionId) { "band S snapshot policy is not active" }
            val inputHash = sha256(command.inputPayload)
            val snapshotHash =
                sha256(
                    listOf(
                        command.sourceRequestId,
                        policy.versionId,
                        inputHash,
                        command.observedAt,
                        command.expiresAt,
                        command.complete,
                        command.issueCodes.sorted(),
                    ).joinToString("|"),
                )
            val candidate =
                BandSInputSnapshot(
                    ids.nextId(),
                    command.sourceRequestId,
                    policy.versionId,
                    snapshotHash,
                    inputHash,
                    command.observedAt.truncatedTo(ChronoUnit.SECONDS),
                    command.expiresAt.truncatedTo(ChronoUnit.SECONDS),
                    command.complete,
                    command.totalAssetKrwAmount,
                    command.observedHotKrwAmount,
                    command.observedColdKrwAmount,
                    command.effectiveHotKrwAmount,
                    command.hotRatio,
                    command.lowerRatio,
                    command.targetRatio,
                    command.upperRatio,
                    command.inputPayload,
                    command.issueCodes.distinct().sorted(),
                )
            bands.findSnapshotBySourceRequest(command.sourceRequestId)?.let { existing ->
                if (existing.sameSnapshotAs(candidate)) return@run existing
                throw ConflictException("bandSSnapshotIdempotency", command.sourceRequestId)
            }
            bands.insertSnapshot(candidate, command.actor)
        }

    fun registerProposal(command: RegisterBandSProposalCommand): BandSProposal =
        transactions.run {
            requireOperator(command.actor)
            val snapshot =
                bands.findSnapshot(command.snapshotId)
                    ?: throw ResourceNotFoundException("bandSSnapshot", command.snapshotId)
            val policy =
                policies.findPolicyVersion(snapshot.policyVersionId)
                    ?: throw ResourceNotFoundException("policyVersion", snapshot.policyVersionId)
            val binding =
                policies.lockPolicyBinding(policy.scopeId)
                    ?: throw ResourceNotFoundException("policyBinding", policy.scopeId)
            val items = command.items.mapIndexed { index, item -> item.toDomain(index + 1) }
            val proposalHash =
                sha256(
                    listOf(
                        command.sourceProposalId,
                        snapshot.snapshotId,
                        snapshot.inputHash,
                        policy.versionId,
                        command.direction,
                        command.totalKrwAmount.canonical(),
                        command.afterHotRatio.canonical(),
                        items.joinToString(",") { it.itemHash },
                        sha256(command.proposalPayload),
                    ).joinToString("|"),
                )
            val candidate =
                BandSProposal(
                    ids.nextId(),
                    command.sourceProposalId,
                    snapshot.snapshotId,
                    policy.versionId,
                    command.direction,
                    proposalHash,
                    snapshot.inputHash,
                    command.totalKrwAmount,
                    command.afterHotRatio,
                    command.executable,
                    command.blockReasons.distinct().sorted(),
                    command.proposalPayload,
                    items,
                )
            bands.findProposalBySourceId(command.sourceProposalId)?.let { existing ->
                if (existing.sameProposalAs(candidate)) return@run existing
                throw ConflictException("bandSProposalIdempotency", command.sourceProposalId)
            }
            if (candidate.executable) {
                BandSExecutionGuard.validate(snapshot, candidate, requireNotNull(binding.activeVersionId), boundary, now())
            } else {
                check(candidate.blockReasons.isNotEmpty()) { "blocked band S proposal requires a reason" }
            }
            bands.insertProposal(candidate, command.actor, now())
        }

    fun requestExecution(command: RequestBandSExecutionCommand): AdminChangeRequest =
        transactions.run {
            requireOperator(command.actor)
            val proposal =
                bands.findProposal(command.proposalId)
                    ?: throw ResourceNotFoundException("bandSProposal", command.proposalId)
            val snapshot =
                bands.findSnapshot(proposal.snapshotId)
                    ?: throw ResourceNotFoundException("bandSSnapshot", proposal.snapshotId)
            val policy =
                policies.findPolicyVersion(proposal.policyVersionId)
                    ?: throw ResourceNotFoundException("policyVersion", proposal.policyVersionId)
            val binding =
                policies.lockPolicyBinding(policy.scopeId)
                    ?: throw ResourceNotFoundException("policyBinding", policy.scopeId)
            BandSExecutionGuard.validate(snapshot, proposal, requireNotNull(binding.activeVersionId), boundary, now())
            val diff = "{\"proposalHash\":\"${proposal.proposalHash}\"}"
            val impact = "{\"itemCount\":${proposal.items.size},\"totalKrwAmount\":${proposal.totalKrwAmount.canonical()}}"
            val candidate =
                AdminChangeRequest(
                    PolicyChangeRequest(
                        ids.nextId(),
                        command.actor,
                        ChangeRisk.FUND,
                        proposal.proposalHash,
                        binding.revision,
                        proposal.proposalId,
                        now().plus(command.validFor),
                    ),
                    ChangeTargetType.BAND_S,
                    policy.scopeId,
                    null,
                    null,
                    diff,
                    sha256(diff),
                    impact,
                    sha256(impact),
                    command.reason,
                    command.workTicket,
                    command.idempotencyKey,
                    AdminRole.BCM_OPERATOR,
                    now(),
                )
            policies.findChangeRequestByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
                if (existing.targetType == ChangeTargetType.BAND_S &&
                    existing.lifecycle.targetVersionId == proposal.proposalId &&
                    existing.lifecycle.targetSnapshotHash == proposal.proposalHash
                ) {
                    return@run existing
                }
                throw ConflictException("bandSChangeIdempotency", command.idempotencyKey)
            }
            policies.insertChangeRequest(candidate)
        }

    fun reserveExecution(command: ReserveBandSExecutionCommand): BandSExecutionRecord =
        transactions.run {
            requireOperator(command.actor)
            bands.findExecution(command.requestId, command.idempotencyKey)?.let { return@run it }
            val request =
                policies.findChangeRequest(command.requestId)
                    ?: throw ResourceNotFoundException("bandSChangeRequest", command.requestId)
            check(request.targetType == ChangeTargetType.BAND_S) { "request ${command.requestId} is not a Band S execution" }
            val proposal =
                bands.findProposal(request.lifecycle.targetVersionId)
                    ?: throw ResourceNotFoundException("bandSProposal", request.lifecycle.targetVersionId)
            val snapshot =
                bands.findSnapshot(proposal.snapshotId)
                    ?: throw ResourceNotFoundException("bandSSnapshot", proposal.snapshotId)
            val policy =
                policies.findPolicyVersion(proposal.policyVersionId)
                    ?: throw ResourceNotFoundException("policyVersion", proposal.policyVersionId)
            val binding =
                policies.lockPolicyBinding(policy.scopeId)
                    ?: throw ResourceNotFoundException("policyBinding", policy.scopeId)
            // 최초 조회 뒤 같은 요청이 먼저 예약할 수 있다. 바인딩 잠금 획득 뒤 다시 읽어
            // 동시 요청도 기존 실행 원장으로 수렴시킨다.
            bands.findExecution(command.requestId, command.idempotencyKey)?.let { return@run it }
            val current = now()
            BandSExecutionGuard.validate(snapshot, proposal, requireNotNull(binding.activeVersionId), boundary, current)
            AdminPolicyLifecycle.validateActivation(
                request.lifecycle,
                policies.findDecisions(request.lifecycle.requestId).map(AdminDecisionRecord::decision),
                binding.revision,
                proposal.proposalHash,
                true,
                current,
            )
            val requestHash = sha256("${request.lifecycle.requestId}|${command.idempotencyKey}|${proposal.proposalHash}")
            val expected = "{\"inputHash\":\"${snapshot.inputHash}\",\"proposalHash\":\"${proposal.proposalHash}\"}"
            bands.insertExecutionIntent(
                ids.nextId(),
                ids.nextId(),
                request,
                command.idempotencyKey,
                requestHash,
                expected,
                sha256(expected),
                command.actor,
                current,
            )
            val executionHash = sha256("${proposal.proposalHash}|${snapshot.inputHash}|${command.idempotencyKey}")
            bands.insertExecution(
                BandSExecutionRecord(
                    ids.nextId(),
                    request.lifecycle.requestId,
                    proposal.proposalId,
                    snapshot.snapshotId,
                    policy.versionId,
                    proposal.proposalHash,
                    snapshot.inputHash,
                    command.idempotencyKey,
                    executionHash,
                    current,
                    command.actor,
                ),
                proposal.items,
            )
        }

    fun recordEvent(command: RecordBandSExecutionEventCommand): BandSExecutionView =
        transactions.run {
            requireOperatorOrSystem(command.actor)
            val current =
                bands.findExecution(command.executionId)
                    ?: throw ResourceNotFoundException("bandSExecution", command.executionId)
            val previous =
                current.itemStates.singleOrNull { it.sequence == command.itemSequence }
                    ?: throw ResourceNotFoundException("bandSExecutionItem", "${command.executionId}:${command.itemSequence}")
            if (previous.status == command.status) return@run current
            val event =
                BandSExecutionEvent(
                    command.executionId,
                    command.itemSequence,
                    eventSequence(current.execution.executionId, command.itemSequence),
                    command.status,
                    command.externalTransactionId,
                    command.vendorTransactionId,
                    command.observationPayload,
                    sha256(command.observationPayload),
                    now(),
                    command.actor,
                )
            bands.appendEvent(event)
            requireNotNull(bands.findExecution(command.executionId))
        }

    fun submitItem(command: SubmitBandSItemCommand): BandSExecutionView {
        requireOperator(command.actor)
        val context =
            transactions.run {
                val execution =
                    bands.findExecution(command.executionId)
                        ?: throw ResourceNotFoundException("bandSExecution", command.executionId)
                val proposal =
                    bands.findProposal(execution.execution.proposalId)
                        ?: throw ResourceNotFoundException("bandSProposal", execution.execution.proposalId)
                val item =
                    proposal.items.singleOrNull { it.sequence == command.itemSequence }
                        ?: throw ResourceNotFoundException(
                            "bandSExecutionItem",
                            "${command.executionId}:${command.itemSequence}",
                        )
                BandSItemSubmissionContext(
                    execution,
                    item,
                    BandSItemSubmissionGuard.decide(item, execution.itemStates),
                )
            }
        if (context.decision == BandSItemSubmissionDecision.ALREADY_SUBMITTED) return context.execution

        val externalTransactionId = "band-${command.executionId}-${command.itemSequence}"
        if (context.decision == BandSItemSubmissionDecision.START) {
            appendEventConverging(
                RecordBandSExecutionEventCommand(
                    command.executionId,
                    command.itemSequence,
                    BandSExecutionEventStatus.SUBMIT_INTENT,
                    externalTransactionId,
                    null,
                    SUBMIT_OBSERVATION,
                    command.actor,
                ),
            )
        }
        val result =
            try {
                submissions.submitManaged(context.item.toManagedSubmission(externalTransactionId, command.executionId))
            } catch (rejected: RelayRejectedException) {
                try {
                    appendEventConverging(
                        RecordBandSExecutionEventCommand(
                            command.executionId,
                            command.itemSequence,
                            BandSExecutionEventStatus.FAILED,
                            null,
                            null,
                            REJECTED_OBSERVATION,
                            command.actor,
                        ),
                    )
                } catch (conflict: ConflictException) {
                    rejected.addSuppressed(conflict)
                }
                throw rejected
            }
        return appendEventConverging(
            RecordBandSExecutionEventCommand(
                command.executionId,
                command.itemSequence,
                BandSExecutionEventStatus.SUBMITTED,
                null,
                result.transactionId,
                SUBMITTED_OBSERVATION,
                command.actor,
            ),
        )
    }

    private fun appendEventConverging(command: RecordBandSExecutionEventCommand): BandSExecutionView =
        try {
            recordEvent(command)
        } catch (conflict: ConflictException) {
            val current = bands.findExecution(command.executionId) ?: throw conflict
            if (current.itemStates.singleOrNull { it.sequence == command.itemSequence }?.status == command.status) {
                current
            } else {
                throw conflict
            }
        }

    private fun BandSProposalItem.toManagedSubmission(
        externalTransactionId: String,
        executionId: String,
    ): ManagedTransactionSubmissionCommand {
        val source = checkNotNull(sourceVaultId) { "band S item $sequence has no source vault" }
        val (recipientType, recipientValue, destination, gasless) =
            when (legType) {
                BandSLegType.EXTERNAL_COLD -> {
                    val address = checkNotNull(destinationAddress) { "band S item $sequence has no cold address" }
                    ManagedDestination(
                        SubmissionRecipientType.ADDRESS,
                        address,
                        VendorTransactionDestination.Address(address),
                        false,
                    )
                }

                BandSLegType.INTERNAL_TO_EGRESS,
                BandSLegType.HOT_REDISTRIBUTE,
                -> {
                    val vault = checkNotNull(destinationVaultId) { "band S item $sequence has no destination vault" }
                    ManagedDestination(
                        SubmissionRecipientType.ACCOUNT,
                        vault,
                        VendorTransactionDestination.Account(vault),
                        false,
                    )
                }

                BandSLegType.COLD_DEPOSIT -> error("cold deposit must not reach BCM submission")
            }
        return ManagedTransactionSubmissionCommand(
            externalTransactionId,
            source,
            recipientType,
            recipientValue,
            destination,
            network,
            tokenSymbol,
            amount.canonical(),
            gasless,
            "band S execution $executionId item $sequence",
        )
    }

    private fun eventSequence(
        executionId: String,
        itemSequence: Int,
    ): Int {
        val view = requireNotNull(bands.findExecution(executionId))
        // Repository view exposes only latest state; the event count is encoded by valid transition depth.
        return when (view.itemStates.single { it.sequence == itemSequence }.status) {
            BandSExecutionEventStatus.RESERVED -> 2
            BandSExecutionEventStatus.SUBMIT_INTENT -> 3
            BandSExecutionEventStatus.SUBMITTED -> 4
            BandSExecutionEventStatus.FINALIZED -> 5
            else -> error("band S item is terminal")
        }
    }

    private fun RegisterBandSProposalItem.toDomain(sequence: Int): BandSProposalItem {
        val hash =
            sha256(
                listOf(
                    sequence,
                    dependsOnSequence,
                    legType,
                    network,
                    tokenSymbol,
                    sourceVaultId,
                    destinationVaultId,
                    destinationAddress,
                    amount.canonical(),
                    krwAmount.canonical(),
                    expectedFeeAmount.canonical(),
                    executable,
                    blockReason,
                ).joinToString("|"),
            )
        return BandSProposalItem(
            sequence,
            dependsOnSequence,
            legType,
            network,
            tokenSymbol,
            sourceVaultId,
            destinationVaultId,
            destinationAddress,
            amount,
            krwAmount,
            expectedFeeAmount,
            hash,
            executable,
            blockReason,
        )
    }

    private fun BandSInputSnapshot.sameSnapshotAs(other: BandSInputSnapshot): Boolean = snapshotHash == other.snapshotHash

    private fun BandSProposal.sameProposalAs(other: BandSProposal): Boolean = proposalHash == other.proposalHash

    private fun requireOperator(actor: AdminActor) {
        check(AdminRole.BCM_OPERATOR in actor.roles) { "BCM_OPERATOR role is required" }
    }

    private fun requireOperatorOrSystem(actor: AdminActor) {
        check(actor.employeeNo == "SYSTEM" || AdminRole.BCM_OPERATOR in actor.roles) { "BCM_OPERATOR role is required" }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun BigDecimal.canonical(): String = stripTrailingZeros().toPlainString()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private data class BandSItemSubmissionContext(
        val execution: BandSExecutionView,
        val item: BandSProposalItem,
        val decision: BandSItemSubmissionDecision,
    )

    private data class ManagedDestination(
        val recipientType: SubmissionRecipientType,
        val recipientValue: String,
        val destination: VendorTransactionDestination,
        val gasless: Boolean,
    )

    private companion object {
        const val SUBMIT_OBSERVATION = "{\"source\":\"BCM\",\"action\":\"SUBMIT\"}"
        const val SUBMITTED_OBSERVATION = "{\"source\":\"BCM\",\"result\":\"ACCEPTED\"}"
        const val REJECTED_OBSERVATION = "{\"source\":\"BCM\",\"result\":\"REJECTED\"}"
    }
}

data class RegisterBandSSnapshotCommand(
    val sourceRequestId: String,
    val policyVersionId: String,
    val observedAt: Instant,
    val expiresAt: Instant,
    val complete: Boolean,
    val totalAssetKrwAmount: BigDecimal,
    val observedHotKrwAmount: BigDecimal,
    val observedColdKrwAmount: BigDecimal,
    val effectiveHotKrwAmount: BigDecimal,
    val hotRatio: BigDecimal,
    val lowerRatio: BigDecimal,
    val targetRatio: BigDecimal,
    val upperRatio: BigDecimal,
    val inputPayload: String,
    val issueCodes: List<String>,
    val actor: AdminActor,
)

data class RegisterBandSProposalCommand(
    val sourceProposalId: String,
    val snapshotId: String,
    val direction: BandSDirection,
    val totalKrwAmount: BigDecimal,
    val afterHotRatio: BigDecimal,
    val executable: Boolean,
    val blockReasons: List<String>,
    val proposalPayload: String,
    val items: List<RegisterBandSProposalItem>,
    val actor: AdminActor,
)

data class RegisterBandSProposalItem(
    val dependsOnSequence: Int?,
    val legType: BandSLegType,
    val network: String,
    val tokenSymbol: String,
    val sourceVaultId: String?,
    val destinationVaultId: String?,
    val destinationAddress: String?,
    val amount: BigDecimal,
    val krwAmount: BigDecimal,
    val expectedFeeAmount: BigDecimal,
    val executable: Boolean,
    val blockReason: String?,
)

data class RequestBandSExecutionCommand(
    val proposalId: String,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val actor: AdminActor,
    val validFor: Duration = Duration.ofHours(3),
)

data class ReserveBandSExecutionCommand(
    val requestId: String,
    val idempotencyKey: String,
    val actor: AdminActor,
)

data class RecordBandSExecutionEventCommand(
    val executionId: String,
    val itemSequence: Int,
    val status: BandSExecutionEventStatus,
    val externalTransactionId: String?,
    val vendorTransactionId: String?,
    val observationPayload: String,
    val actor: AdminActor,
)

data class SubmitBandSItemCommand(
    val executionId: String,
    val itemSequence: Int,
    val actor: AdminActor,
)
