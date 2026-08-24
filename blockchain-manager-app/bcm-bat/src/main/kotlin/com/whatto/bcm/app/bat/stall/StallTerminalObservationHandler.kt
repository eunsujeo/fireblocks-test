package com.whatto.bcm.app.bat.stall

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
                ),
                successEvidence = PhysicalTransactionEvidence.hasSucceeded(transaction.statusObservation()),
            )
        val eventType =
            candidate.submissionType?.customerEventType()
                ?: if (candidate.submissionType == null) {
                    EventType.DEPOSIT
                } else {
                    if (candidate.submissionType == SubmissionTransactionType.SWEEP_BATCH) {
                        sweepExecutions.markReconciling(
                            checkNotNull(candidate.sweepExecutionId) {
                                "SWEEP_BATCH submission has no sweep execution id"
                            },
                            transaction.transactionId,
                            transaction.transactionHash,
                        )
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
                        to = transaction.destinationAddress,
                        from = transaction.sourceAddress,
                        amount = transaction.amount,
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
