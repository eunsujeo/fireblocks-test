package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.ChainEvent
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStateMachine
import com.whatto.bcm.domain.vendor.PhysicalTransactionEvidence
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.support.id.UuidV7Generator
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
    transactions: TxRecordRepository,
    private val statusTranslator: VendorStatusTranslator,
    private val transactionRunner: TransactionRunner,
    private val outbox: OutboxEventRepository,
    private val eventSerializer: ChainEventSerializer,
    clock: Clock,
    @param:Value("\${bcm.webhook-worker.outbox-max-attempts:5}") private val outboxMaxAttempts: Int,
) : StallTerminalObservationHandler {
    private val stateMachine = TxStateMachine(transactions)
    private val eventIds = UuidV7Generator(clock)

    override fun observe(
        candidate: StallCandidate,
        transaction: VendorTransaction,
        observedAt: String,
    ) {
        transactionRunner.run {
            val status =
                statusTranslator.translate(
                    VendorStatusObservation(
                        transaction.rawStatus,
                        transaction.subStatus,
                        transaction.confirmationCount,
                    ),
                    candidate.record.network,
                )
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
                    ),
                    successEvidence = PhysicalTransactionEvidence.hasSucceeded(transaction.statusObservation()),
                )
            val eventType = candidate.submissionType?.customerEventType() ?: return@run
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
                        eventDate = observedAt.take(8),
                        vendorTransactionId = stateChange.record.vendorTxId,
                        eventType = OutboxEventType.forPublishedStatus(publishedStatus),
                        topic = eventType.topic,
                        payload = eventSerializer.serialize(event),
                        maxRetryCount = outboxMaxAttempts,
                        traceId = "stall-check:${stateChange.record.vendorTxId}",
                    )
                }
            outbox.insertAll(events)
        }
    }
}
