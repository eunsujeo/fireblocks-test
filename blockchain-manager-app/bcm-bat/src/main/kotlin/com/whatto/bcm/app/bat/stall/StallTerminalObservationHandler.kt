package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.app.application.sweep.SweepInvalidationService
import com.whatto.bcm.app.application.tx.TxObservationOutcome
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.ChainEvent
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventType
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import com.whatto.bcm.domain.tx.BoostAttemptRepository
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxObservationConsistency
import com.whatto.bcm.domain.tx.TxObservationQuarantine
import com.whatto.bcm.domain.tx.TxObservationQuarantineEvidence
import com.whatto.bcm.domain.tx.TxObservationQuarantineFingerprint
import com.whatto.bcm.domain.tx.TxObservationQuarantineRepository
import com.whatto.bcm.domain.tx.TxObservationQuarantineSource
import com.whatto.bcm.domain.tx.TxObservationQuarantineStatus
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.ObservedVendorTransaction
import com.whatto.bcm.domain.vendor.PhysicalTransactionEvidence
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionEvidencePort
import com.whatto.bcm.support.id.UuidV7Generator
import com.whatto.bcm.support.time.BusinessDates
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock

fun interface StallTerminalObservationHandler {
    fun observe(
        candidate: StallCandidate,
        observed: ObservedVendorTransaction,
        observedAt: String,
        source: TxObservationQuarantineSource,
    ): TerminalObservationOutcome
}

/**
 * 종결 재관찰의 결과. **격리를 복구 성공으로 세지 않으려고** 가른다(03 V37) —
 * 대사가 이 구분 없이 세면 격리가 "놓친 웹훅을 복구했다"로 보고된다.
 */
sealed interface TerminalObservationOutcome {
    /** 원장·outbox 에 반영했다. */
    data object Applied : TerminalObservationOutcome

    /** 관찰이 원장과 어긋나 격리했다. 원장·outbox 는 그대로다. */
    data class Quarantined(
        val quarantineId: String,
        val field: String,
    ) : TerminalObservationOutcome

    /** RBF 계열의 승패가 아직 안 갈렸다 — 다음 주기에 다시 본다. */
    data object Deferred : TerminalObservationOutcome
}

@Service
class TransactionalStallTerminalObservationHandler(
    private val transactions: TxRecordRepository,
    private val txStates: TxStateService,
    private val quarantines: TxObservationQuarantineRepository,
    private val statusTranslator: VendorStatusTranslator,
    private val transactionRunner: TransactionRunner,
    private val outbox: OutboxEventRepository,
    private val sweepExecutions: SweepExecutionRepository,
    private val sweepInvalidation: SweepInvalidationService,
    private val boosts: BoostAttemptRepository,
    private val vendor: VendorTransactionEvidencePort,
    private val eventSerializer: ChainEventSerializer,
    private val clock: Clock,
    @param:Value("\${bcm.webhook-worker.outbox-max-attempts:5}") private val outboxMaxAttempts: Int,
) : StallTerminalObservationHandler {
    private val eventIds = UuidV7Generator(clock)

    override fun observe(
        candidate: StallCandidate,
        observed: ObservedVendorTransaction,
        observedAt: String,
        source: TxObservationQuarantineSource,
    ): TerminalObservationOutcome {
        val resolved = resolveFamilyObservation(candidate, observed) ?: return TerminalObservationOutcome.Deferred
        return transactionRunner.run {
            observeAndPublish(candidate, resolved, observedAt, source)
        }
    }

    /**
     * RBF 계열에서 어느 물리 거래를 root 에 확정할지 고른다.
     *
     * **여기서는 원장을 쓰지 않는다.** 예전에는 회수한 부스트 결속을 이 자리에서 별도 트랜잭션으로 커밋했는데,
     * 그 쓰기는 부스트 행뿐 아니라 root 의 `actv_tx_id`·`tx_hash` 도 바꾼다(`switchRoot`). 뒤에서 관찰을 격리해도
     * 이미 커밋된 변경은 남아 "아무것도 적용하지 않는다"가 깨진다. 그래서 **쓸 것만 모아** 돌려주고,
     * 실제 쓰기는 동일성 검사를 통과한 뒤 같은 트랜잭션에서 한다.
     */
    private fun resolveFamilyObservation(
        candidate: StallCandidate,
        observed: ObservedVendorTransaction,
    ): ResolvedFamilyObservation? {
        val transaction = observed.transaction
        val status = statusTranslator.translate(transaction.statusObservation(), candidate.record.network)
        if (status != TxStatus.FAILED) return ResolvedFamilyObservation(observed)
        val viableBoosts = boosts.findViableByRoot(candidate.record.vendorTxId)
        if (viableBoosts.isEmpty()) return ResolvedFamilyObservation(observed)
        val pendingBindings = mutableListOf<PendingBoostBinding>()
        val replacementIds =
            viableBoosts.map { attempt ->
                attempt.newVendorTransactionId
                    ?: vendor.observedTransactionByExternalTransactionId(attempt.externalTransactionId)?.let { recovered ->
                        pendingBindings += PendingBoostBinding(attempt.externalTransactionId, recovered.transaction.transactionId)
                        recovered.transaction.transactionId
                    }
                    ?: error(
                        "pending boost recovery not found: " +
                            "rootVendorTransactionId=${candidate.record.vendorTxId} " +
                            "externalTransactionId=${attempt.externalTransactionId}",
                    )
            }
        val familyIds =
            buildSet {
                add(candidate.record.vendorTxId)
                addAll(replacementIds)
            }
        val observations =
            familyIds.associateWith { transactionId ->
                if (transactionId == transaction.transactionId) observed else vendor.observedTransaction(transactionId)
            }
        val winner =
            observations.values.filterNotNull().firstOrNull {
                PhysicalTransactionEvidence.hasSucceeded(it.transaction.statusObservation())
            }
        if (winner != null) return ResolvedFamilyObservation(winner, pendingBindings)
        if (observations.values.all { it?.transaction?.isFailed(candidate) == true }) {
            val activeVendorTransactionId = transactions.findByVendorTxId(candidate.record.vendorTxId)?.activeVendorTxId
            return ResolvedFamilyObservation(observations[activeVendorTransactionId] ?: observed, pendingBindings)
        }
        return null
    }

    /** 고른 관찰과, 검사를 통과한 뒤에야 쓸 부스트 결속. */
    private data class ResolvedFamilyObservation(
        val observed: ObservedVendorTransaction,
        val pendingBoostBindings: List<PendingBoostBinding> = emptyList(),
    )

    private data class PendingBoostBinding(
        val externalTransactionId: String,
        val vendorTransactionId: String,
    )

    private fun VendorTransaction.isFailed(candidate: StallCandidate): Boolean =
        statusTranslator.translate(statusObservation(), candidate.record.network) == TxStatus.FAILED

    private fun observeAndPublish(
        candidate: StallCandidate,
        resolved: ResolvedFamilyObservation,
        observedAt: String,
        source: TxObservationQuarantineSource,
    ): TerminalObservationOutcome {
        val transaction = resolved.observed.transaction
        val status = statusTranslator.translate(transaction.statusObservation(), candidate.record.network)
        val outcome =
            txStates.observeConsistentlyAround(
                rootVendorTransactionId = candidate.record.vendorTxId,
                observation =
                    TxObservation(
                        vendorTransactionId = transaction.transactionId,
                        externalTransactionId = candidate.record.externalTxId,
                        accountId = candidate.record.accountId,
                        network = candidate.record.network,
                        symbol = candidate.record.symbol,
                        transactionHash = transaction.transactionHash,
                        status = status,
                        confirmationCount = transaction.confirmationCount,
                        vendorSubStatus = transaction.subStatus,
                        vendorNetworkStatus = candidate.record.vendorNetworkStatus,
                        observedAt = observedAt,
                        vendorCreatedAt = CoreDateTimes.fromEpochMillis(transaction.createdAtEpochMillis),
                        // 조회가 본 금액·주소를 실어 보낸다 — 이게 없으면 검사할 것이 없다.
                        // 거래 구분은 싣지 않는다: 벤더 관찰값이 아니라 제출에서 오는 권위 값이다.
                        observedAmount = transaction.amount,
                        observedSourceAddress = transaction.sourceAddress,
                        observedDestinationAddress = transaction.destinationAddress,
                    ),
                successEvidence = PhysicalTransactionEvidence.hasSucceeded(transaction.statusObservation()),
            ) {
                // 검사를 통과한 뒤에만 쓴다. 이 쓰기는 root 의 활성 거래도 갈아 끼우므로 전이보다 앞서야 한다.
                resolved.pendingBoostBindings.forEach { binding ->
                    boosts.markSubmittedByObservation(binding.externalTransactionId, binding.vendorTransactionId, observedAt)
                }
            }
        val stateChange =
            when (outcome) {
                is TxObservationOutcome.Applied -> outcome.change
                is TxObservationOutcome.Conflict -> return quarantine(candidate, resolved, outcome.detail, observedAt, source)
            }
        val eventType =
            candidate.submissionType?.customerEventType()
                ?: if (candidate.submissionType == null) {
                    EventType.DEPOSIT
                } else {
                    if (candidate.submissionType == SubmissionTransactionType.SWEEP_BATCH) {
                        val executionId = checkNotNull(candidate.sweepExecutionId) { "SWEEP_BATCH submission has no sweep execution id" }
                        if (!sweepInvalidation.invalidate(
                                executionId,
                                transaction.transactionId,
                                transaction.transactionHash,
                                stateChange.record.lastPublishedStatus,
                                observedAt,
                            )
                        ) {
                            sweepExecutions.markReconciling(executionId, transaction.transactionId, transaction.transactionHash)
                        }
                    }
                    return TerminalObservationOutcome.Applied
                }
        val events =
            stateChange.statusesToPublish.map { publishedStatus ->
                val eventId = eventIds.nextId()
                val event =
                    ChainEvent(
                        eventId = eventId,
                        type = eventType,
                        txId = stateChange.record.vendorTxId,
                        txHash = stateChange.record.transactionHash,
                        externalTxId = stateChange.record.externalTxId,
                        accountId = stateChange.record.accountId,
                        network = stateChange.record.network,
                        symbol = stateChange.record.symbol,
                        // **원장 값을 싣는다.** 조회값을 그대로 쓰면 원장이 최초값을 지키는 동안
                        // 이벤트만 새 값을 실어 나가 둘이 갈린다(03 V32·V34).
                        to = stateChange.record.destinationAddress,
                        from = stateChange.record.sourceAddress,
                        amount = checkNotNull(stateChange.record.amount) { "terminal observation ledger row must carry an amount" },
                        status = publishedStatus,
                        numOfConfirmations = transaction.confirmationCount,
                    )
                OutboxEvent(
                    eventId = eventId,
                    eventDate = BusinessDates.now(clock),
                    vendorTransactionId = stateChange.record.vendorTxId,
                    eventType = OutboxEventType.forPublishedStatus(publishedStatus),
                    topic = eventType.topic,
                    payload = eventSerializer.serialize(event),
                    maxRetryCount = outboxMaxAttempts,
                    traceId = "stall:${stateChange.record.vendorTxId}",
                )
            }
        outbox.insertAll(events)
        return TerminalObservationOutcome.Applied
    }

    /**
     * 관찰이 원장과 어긋난다 — **원장·outbox 를 건드리지 않고** 관찰 하나를 격리한다(03 V32·V37).
     * 증적은 판정에 쓴 응답 원문이다. 어느 조회였는지와 목록 안 위치까지 함께 남긴다.
     */
    private fun quarantine(
        candidate: StallCandidate,
        resolved: ResolvedFamilyObservation,
        detail: TxObservationConsistency.Result.Conflict,
        observedAt: String,
        source: TxObservationQuarantineSource,
    ): TerminalObservationOutcome {
        val evidence = resolved.observed.evidence
        val observedTransactionId = resolved.observed.transaction.transactionId
        val fingerprint =
            TxObservationQuarantineFingerprint.of(
                rootVendorTransactionId = candidate.record.vendorTxId,
                observedVendorTransactionId = observedTransactionId,
                field = detail.field,
                recordedValue = detail.recorded,
                observedValue = detail.observed,
                body = evidence.body,
            )
        val stored =
            quarantines.open(
                TxObservationQuarantine(
                    quarantineId = eventIds.nextId(),
                    rootVendorTransactionId = candidate.record.vendorTxId,
                    observedVendorTransactionId = observedTransactionId,
                    network = candidate.record.network,
                    source = source,
                    status = TxObservationQuarantineStatus.OPEN,
                    field = detail.field,
                    recordedValue = detail.recorded,
                    observedValue = detail.observed,
                    evidence =
                        TxObservationQuarantineEvidence(
                            body = evidence.body,
                            contentType = evidence.contentType,
                            lookup = evidence.lookup,
                            indexInBody = evidence.indexInBody,
                        ),
                    fingerprint = fingerprint,
                    detectionCount = 1,
                    firstDetectedAt = observedAt,
                    lastChangedAt = observedAt,
                ),
                observedAt,
            )
        return TerminalObservationOutcome.Quarantined(stored.quarantineId, detail.field)
    }
}
