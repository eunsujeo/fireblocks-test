package com.whatto.bcm.app.application.webhook

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.tx.TxObservation
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.ChainEvent
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.event.EventType
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.webhook.UnattributedDepositAlert
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlert
import com.whatto.bcm.domain.webhook.WebhookFailureResult
import com.whatto.bcm.domain.webhook.WebhookInboxItem
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import com.whatto.bcm.infra.client.fireblocks.FireblocksStatusTranslator
import com.whatto.bcm.infra.client.fireblocks.FireblocksTransaction
import com.whatto.bcm.infra.client.fireblocks.FireblocksTransactionParser
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Clock

sealed interface WebhookDecisionOutcome {
    data object NoWork : WebhookDecisionOutcome

    data class Processed(
        val notificationId: String,
        val outboxEventCount: Int,
    ) : WebhookDecisionOutcome

    data class Ignored(
        val notificationId: String,
    ) : WebhookDecisionOutcome

    data class Unattributed(
        val alert: UnattributedDepositAlert,
    ) : WebhookDecisionOutcome

    data class UnregisteredVaultTransfer(
        val alert: UnregisteredVaultTransferAlert,
    ) : WebhookDecisionOutcome

    data class Retrying(
        val notificationId: String,
        val retryCount: Int,
    ) : WebhookDecisionOutcome

    data class Quarantined(
        val notificationId: String,
        val retryCount: Int,
    ) : WebhookDecisionOutcome
}

class WebhookDecisionProcessingException(
    val notificationId: String,
    cause: RuntimeException,
) : RuntimeException("webhook decision processing failed: notificationId=$notificationId", cause)

class WebhookDecisionConflictException(
    val notificationId: String,
    cause: ConflictException,
) : RuntimeException("webhook decision conflict: notificationId=$notificationId", cause)

@Service
class WebhookDecisionTransaction(
    private val inboxRepository: WebhookInboxRepository,
    private val transactionRunner: TransactionRunner,
    private val assetMappings: VendorAssetMappingQueryService,
    private val depositAddresses: DepositAddressQueryService,
    private val txStates: TxStateService,
    private val outboxEvents: OutboxEventService,
    private val submissions: SubmissionRecordRepository,
    private val parser: FireblocksTransactionParser,
    private val statusTranslator: FireblocksStatusTranslator,
    private val eventIdGenerator: EventIdGenerator,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @param:Value("\${bcm.webhook-worker.max-attempts:3}") private val maxAttempts: Int,
    @param:Value("\${bcm.webhook-worker.outbox-max-attempts:5}") private val outboxMaxAttempts: Int,
) {
    fun processNext(): WebhookDecisionOutcome = transactionRunner.run { processNextInTransaction() }

    fun recordUnexpectedFailure(notificationId: String): WebhookDecisionOutcome =
        transactionRunner.run {
            inboxRepository
                .recordFailure(notificationId, UNEXPECTED_FAILURE_REASON, maxAttempts)
                .toOutcome(notificationId)
        }

    private fun processNextInTransaction(): WebhookDecisionOutcome {
        val inboxItem = inboxRepository.findNextPendingForUpdate() ?: return WebhookDecisionOutcome.NoWork
        return try {
            process(inboxItem)
        } catch (exception: WebhookPayloadException) {
            failed(inboxItem, exception.safeReason)
        } catch (exception: ConflictException) {
            throw WebhookDecisionConflictException(inboxItem.notificationId, exception)
        } catch (exception: RuntimeException) {
            throw WebhookDecisionProcessingException(inboxItem.notificationId, exception)
        }
    }

    private fun process(inboxItem: WebhookInboxItem): WebhookDecisionOutcome {
        if (inboxItem.eventType !in SUPPORTED_EVENT_TYPES) {
            markProcessed(inboxItem)
            return WebhookDecisionOutcome.Ignored(inboxItem.notificationId)
        }

        val transaction = parser.parse(inboxItem.payload)
        if (transaction.managedVaultSource) {
            return processManagedVaultTransfer(inboxItem, transaction)
        }

        val mapping =
            assetMappings.findByVendorAssetId(transaction.vendorAssetId)
                ?: throw WebhookPayloadException("unmapped vendor assetId")
        // 입금은 목적지 주소로 귀속하므로 여기서는 필수다. vault 발신(출금·내부이체·sweep)은
        // 체인 등장 전 알림에 이 값이 비어 있을 수 있어 제출 원장으로 가른다.
        val destinationAddress =
            transaction.destinationAddress
                ?: throw WebhookPayloadException("missing data.destinationAddress")
        val depositAddress =
            depositAddresses.findByAddress(destinationAddress, mapping.network)
                ?: return unattributed(inboxItem, transaction, mapping.network, mapping.symbol)
        val sourceAddress = transaction.sourceAddress ?: throw WebhookPayloadException("missing data.sourceAddress")
        val status = statusTranslator.translate(transaction, mapping.network)
        val stateChange =
            txStates.observe(
                TxObservation(
                    vendorTransactionId = transaction.vendorTransactionId,
                    externalTransactionId = transaction.externalTransactionId,
                    accountId = depositAddress.accountId,
                    network = depositAddress.network,
                    symbol = depositAddress.symbol,
                    status = status,
                    confirmationCount = transaction.confirmationCount,
                    vendorSubStatus = transaction.subStatus,
                    vendorNetworkStatus = transaction.networkStatus,
                    observedAt = inboxItem.receivedAt,
                ),
            )
        val events =
            stateChange.statusesToPublish.map { publishedStatus ->
                outboxEvent(
                    inboxItem = inboxItem,
                    transaction = transaction,
                    txRecord = stateChange.record,
                    sourceAddress = sourceAddress,
                    status = publishedStatus,
                    eventType = EventType.DEPOSIT,
                )
            }
        outboxEvents.enqueue(events)
        markProcessed(inboxItem)
        return WebhookDecisionOutcome.Processed(inboxItem.notificationId, events.size)
    }

    private fun processManagedVaultTransfer(
        inboxItem: WebhookInboxItem,
        transaction: FireblocksTransaction,
    ): WebhookDecisionOutcome {
        val externalTransactionId = transaction.externalTransactionId
        val submission = externalTransactionId?.let(submissions::findByExternalTransactionId)
        if (submission == null) {
            return unregisteredVaultTransfer(inboxItem, transaction)
        }
        when (val registeredVendorTransactionId = submission.vendorTransactionId) {
            null -> {
                submissions.markSubmitted(
                    externalTransactionId,
                    transaction.vendorTransactionId,
                    inboxItem.receivedAt,
                )
            }

            transaction.vendorTransactionId -> {
                Unit
            }

            else -> {
                // 한 요청 키에 거래가 둘 붙었다 — 재시도로 풀릴 성질이 아니라 사람이 봐야 한다.
                // 03 sbmt_stcd 전이 표: "다른 vndr_tx_id 가 오면 충돌로 보고 격리한다" (즉시 격리)
                return quarantineNow(
                    inboxItem,
                    "vendor transaction id conflict: recorded=$registeredVendorTransactionId " +
                        "observed=${transaction.vendorTransactionId}",
                )
            }
        }

        val status = statusTranslator.translate(transaction, submission.network)
        val stateChange =
            txStates.observe(
                TxObservation(
                    vendorTransactionId = transaction.vendorTransactionId,
                    externalTransactionId = submission.externalTransactionId,
                    accountId = submission.senderAccountId,
                    network = submission.network,
                    symbol = submission.symbol,
                    status = status,
                    confirmationCount = transaction.confirmationCount,
                    vendorSubStatus = transaction.subStatus,
                    vendorNetworkStatus = transaction.networkStatus,
                    observedAt = inboxItem.receivedAt,
                ),
            )
        val eventType = submission.transactionType.customerEventType()
        if (eventType == null) {
            markProcessed(inboxItem)
            return WebhookDecisionOutcome.Ignored(inboxItem.notificationId)
        }
        val events =
            stateChange.statusesToPublish.map { publishedStatus ->
                outboxEvent(
                    inboxItem = inboxItem,
                    transaction = transaction,
                    txRecord = stateChange.record,
                    sourceAddress = transaction.sourceAddress,
                    status = publishedStatus,
                    eventType = eventType,
                )
            }
        outboxEvents.enqueue(events)
        markProcessed(inboxItem)
        return WebhookDecisionOutcome.Processed(inboxItem.notificationId, events.size)
    }

    private fun outboxEvent(
        inboxItem: WebhookInboxItem,
        transaction: FireblocksTransaction,
        txRecord: TxRecord,
        sourceAddress: String?,
        status: TxStatus,
        eventType: EventType,
    ): OutboxEvent {
        val eventId = eventIdGenerator.nextId()
        val event =
            ChainEvent(
                eventId = eventId,
                type = eventType,
                txId = txRecord.vendorTxId,
                txHash = transaction.transactionHash,
                externalTxId = txRecord.externalTxId,
                accountId = txRecord.accountId,
                network = txRecord.network,
                symbol = txRecord.symbol,
                to = transaction.destinationAddress,
                from = sourceAddress,
                amount = transaction.amount,
                status = status,
                numOfConfirmations = transaction.confirmationCount,
            )
        return OutboxEvent(
            eventId = eventId,
            eventDate = CoreDateTimes.now(clock).take(8),
            vendorTransactionId = txRecord.vendorTxId,
            eventType = OutboxEventType.forPublishedStatus(status),
            topic = eventType.topic,
            payload = objectMapper.writeValueAsString(event),
            maxRetryCount = outboxMaxAttempts,
            traceId = inboxItem.notificationId,
        )
    }

    private fun unattributed(
        inboxItem: WebhookInboxItem,
        transaction: FireblocksTransaction,
        network: String,
        symbol: String,
    ): WebhookDecisionOutcome.Unattributed {
        markProcessed(inboxItem)
        return WebhookDecisionOutcome.Unattributed(
            UnattributedDepositAlert(
                notificationId = inboxItem.notificationId,
                vendorTransactionId = transaction.vendorTransactionId,
                network = network,
                symbol = symbol,
            ),
        )
    }

    private fun unregisteredVaultTransfer(
        inboxItem: WebhookInboxItem,
        transaction: FireblocksTransaction,
    ): WebhookDecisionOutcome.UnregisteredVaultTransfer {
        markProcessed(inboxItem)
        return WebhookDecisionOutcome.UnregisteredVaultTransfer(
            UnregisteredVaultTransferAlert(
                notificationId = inboxItem.notificationId,
                vendorTransactionId = transaction.vendorTransactionId,
                externalTransactionId = transaction.externalTransactionId,
            ),
        )
    }

    private fun failed(
        inboxItem: WebhookInboxItem,
        safeReason: String,
    ): WebhookDecisionOutcome {
        val failure = inboxRepository.recordFailure(inboxItem.notificationId, safeReason, maxAttempts)
        return failure.toOutcome(inboxItem.notificationId)
    }

    /** 재시도가 결과를 바꾸지 못하는 영구 충돌은 상한을 기다리지 않고 한 번에 격리한다. */
    private fun quarantineNow(
        inboxItem: WebhookInboxItem,
        safeReason: String,
    ): WebhookDecisionOutcome {
        val failure = inboxRepository.recordFailure(inboxItem.notificationId, safeReason, IMMEDIATE_QUARANTINE)
        return failure.toOutcome(inboxItem.notificationId)
    }

    private fun WebhookFailureResult.toOutcome(notificationId: String): WebhookDecisionOutcome =
        if (quarantined) {
            WebhookDecisionOutcome.Quarantined(notificationId, retryCount)
        } else {
            WebhookDecisionOutcome.Retrying(notificationId, retryCount)
        }

    private fun markProcessed(inboxItem: WebhookInboxItem) {
        inboxRepository.markProcessed(inboxItem.notificationId, CoreDateTimes.now(clock))
    }

    private companion object {
        const val UNEXPECTED_FAILURE_REASON = "decision processing failed"
        const val IMMEDIATE_QUARANTINE = 1
        val SUPPORTED_EVENT_TYPES =
            setOf(
                "transaction.created",
                "transaction.status.updated",
                "transaction.approval_status.updated",
            )
    }
}
