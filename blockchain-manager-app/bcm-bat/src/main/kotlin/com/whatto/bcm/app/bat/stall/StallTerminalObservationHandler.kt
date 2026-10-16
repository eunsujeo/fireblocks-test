package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.app.application.sweep.SweepInvalidationService
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
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStateMachine
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.PhysicalTransactionEvidence
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.support.id.UuidV7Generator
import com.whatto.bcm.support.time.BusinessDates
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock

fun interface StallTerminalObservationHandler {
    fun observe(
        candidate: StallCandidate,
        transaction: VendorTransaction,
        observedAt: String,
    )
}

@Service
class TransactionalStallTerminalObservationHandler(
    private val transactions: TxRecordRepository,
    private val statusTranslator: VendorStatusTranslator,
    private val transactionRunner: TransactionRunner,
    private val outbox: OutboxEventRepository,
    private val sweepExecutions: SweepExecutionRepository,
    private val sweepInvalidation: SweepInvalidationService,
    private val boosts: BoostAttemptRepository,
    private val vendor: VendorTransactionPort,
    private val eventSerializer: ChainEventSerializer,
    private val clock: Clock,
    @param:Value("\${bcm.webhook-worker.outbox-max-attempts:5}") private val outboxMaxAttempts: Int,
) : StallTerminalObservationHandler {
    private val stateMachine = TxStateMachine(transactions)
    private val eventIds = UuidV7Generator(clock)

    override fun observe(
        candidate: StallCandidate,
        transaction: VendorTransaction,
        observedAt: String,
    ) {
        val resolved = resolveFamilyObservation(candidate, transaction, observedAt) ?: return
        transactionRunner.run {
            observeAndPublish(candidate, resolved, observedAt)
        }
    }

    private fun resolveFamilyObservation(
        candidate: StallCandidate,
        transaction: VendorTransaction,
        observedAt: String,
    ): VendorTransaction? {
        val status = statusTranslator.translate(transaction.statusObservation(), candidate.record.network)
        if (status != TxStatus.FAILED) return transaction
        val viableBoosts = boosts.findViableByRoot(candidate.record.vendorTxId)
        if (viableBoosts.isEmpty()) return transaction
        val replacementIds =
            viableBoosts.map { attempt ->
                attempt.newVendorTransactionId
                    ?: vendor.transactionByExternalTransactionId(attempt.externalTransactionId)?.let { recovered ->
                        transactionRunner.run {
                            boosts.markSubmittedByObservation(
                                attempt.externalTransactionId,
                                recovered.transactionId,
                                observedAt,
                            )
                        }
                        recovered.transactionId
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
                if (transactionId == transaction.transactionId) transaction else vendor.transaction(transactionId)
            }
        return observations.values.filterNotNull().firstOrNull {
            PhysicalTransactionEvidence.hasSucceeded(it.statusObservation())
        } ?: if (observations.values.all { it?.isFailed(candidate) == true }) {
            val activeVendorTransactionId = transactions.findByVendorTxId(candidate.record.vendorTxId)?.activeVendorTxId
            observations[activeVendorTransactionId] ?: transaction
        } else {
            null
        }
    }

    private fun VendorTransaction.isFailed(candidate: StallCandidate): Boolean =
        statusTranslator.translate(statusObservation(), candidate.record.network) == TxStatus.FAILED

    private fun observeAndPublish(
        candidate: StallCandidate,
        transaction: VendorTransaction,
        observedAt: String,
    ) {
        val status = statusTranslator.translate(transaction.statusObservation(), candidate.record.network)
        val stateChange =
            stateMachine.observeRoot(
                candidate.record.vendorTxId,
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
                    // 조회가 본 금액·주소를 실어 보낸다. 싣지 않으면 원장은 옛 값을 지키는데 아래 이벤트는
                    // 조회값을 실어 **원장과 이벤트가 갈린다** — V32가 막으려는 내부 모순이다.
                    // 거래 구분은 싣지 않는다 — 벤더 관찰값이 아니라 제출에서 오는 권위 값이다.
                    observedAmount = transaction.amount,
                    observedSourceAddress = transaction.sourceAddress,
                    observedDestinationAddress = transaction.destinationAddress,
                ),
                successEvidence = PhysicalTransactionEvidence.hasSucceeded(transaction.statusObservation()),
            )
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
                    return
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
    }
}
