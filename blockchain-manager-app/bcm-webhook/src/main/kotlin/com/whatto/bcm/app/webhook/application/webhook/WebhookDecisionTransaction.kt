package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.sweep.SweepInvalidationService
import com.whatto.bcm.app.application.sweep.SweepObservationService
import com.whatto.bcm.app.application.tx.BoostObservationService
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.ChainEvent
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.event.EventType
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.FinalityPolicyConfigurationException
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.PhysicalTransactionEvidence
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.webhook.UnattributedDepositAlert
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlert
import com.whatto.bcm.domain.webhook.WebhookFailureResult
import com.whatto.bcm.domain.webhook.WebhookInboxItem
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import com.whatto.bcm.domain.webhook.WebhookRetryBackoff
import com.whatto.bcm.domain.webhook.WebhookTransaction
import com.whatto.bcm.domain.webhook.WebhookTransactionParser
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import com.whatto.bcm.support.time.BusinessDates
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * 인박스 한 건을 판단하는 트랜잭션 경계. 제공자마다 구현이 **하나만** 조립된다 —
 * 워커·경보 처리는 이 경계 뒤의 벤더 어휘를 모른다.
 */
interface WebhookDecisionWork {
    fun processNext(): WebhookDecisionOutcome

    fun recordUnexpectedFailure(notificationId: String): WebhookDecisionOutcome
}

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
@ConditionalOnFireblocksProtocol
class WebhookDecisionTransaction(
    private val inboxRepository: WebhookInboxRepository,
    private val transactionRunner: TransactionRunner,
    private val assetMappings: VendorAssetMappingQueryService,
    private val depositAddresses: DepositAddressQueryService,
    private val txStates: TxStateService,
    private val outboxEvents: OutboxEventService,
    private val submissions: SubmissionObservationService,
    private val boosts: BoostObservationService,
    private val sweepExecutions: SweepObservationService,
    private val sweepInvalidation: SweepInvalidationService,
    private val parser: WebhookTransactionParser,
    private val statusTranslator: VendorStatusTranslator,
    private val eventIdGenerator: EventIdGenerator,
    private val eventSerializer: ChainEventSerializer,
    private val clock: Clock,
    @param:Value("\${bcm.webhook-worker.max-attempts:3}") private val maxAttempts: Int,
    @param:Value("\${bcm.webhook-worker.retry-base-seconds:30}") private val retryBaseSeconds: Long,
    @param:Value("\${bcm.webhook-worker.outbox-max-attempts:5}") private val outboxMaxAttempts: Int,
) : WebhookDecisionWork {
    override fun processNext(): WebhookDecisionOutcome = transactionRunner.run { processNextInTransaction() }

    override fun recordUnexpectedFailure(notificationId: String): WebhookDecisionOutcome =
        transactionRunner.run {
            inboxRepository
                .recordFailure(notificationId, UNEXPECTED_FAILURE_REASON, maxAttempts, nextAttemptAt(1))
                .toOutcome(notificationId)
        }

    private fun processNextInTransaction(): WebhookDecisionOutcome {
        val inboxItem = inboxRepository.findNextPendingForUpdate(CoreDateTimes.now(clock)) ?: return WebhookDecisionOutcome.NoWork
        return try {
            process(inboxItem)
        } catch (exception: WebhookPayloadException) {
            failed(inboxItem, exception.safeReason)
        } catch (exception: FinalityPolicyConfigurationException) {
            // payload poison이 아니라 운영 설정 오류다. inbox를 P로 남겨 설정 복구 후 다시 처리한다.
            throw exception
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
            depositAddresses.findByAddress(destinationAddress, mapping.network, mapping.symbol)
                ?: return unattributed(inboxItem, transaction, mapping.network, mapping.symbol)
        val sourceAddress = transaction.sourceAddress ?: throw WebhookPayloadException("missing data.sourceAddress")
        val status = statusTranslator.translate(transaction.statusObservation, mapping.network)
        val stateChange =
            txStates.observe(
                TxObservation(
                    vendorTransactionId = transaction.vendorTransactionId,
                    externalTransactionId = transaction.externalTransactionId,
                    accountId = depositAddress.accountId,
                    network = depositAddress.network,
                    symbol = depositAddress.symbol,
                    transactionHash = transaction.transactionHash,
                    status = status,
                    confirmationCount = transaction.confirmationCount,
                    vendorSubStatus = transaction.subStatus,
                    vendorNetworkStatus = transaction.networkStatus,
                    observedAt = inboxItem.receivedAt,
                    vendorCreatedAt = CoreDateTimes.fromEpochMillis(transaction.createdAtEpochMillis),
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
        markProcessed(inboxItem, transaction)
        return WebhookDecisionOutcome.Processed(inboxItem.notificationId, events.size)
    }

    private fun processManagedVaultTransfer(
        inboxItem: WebhookInboxItem,
        transaction: WebhookTransaction,
    ): WebhookDecisionOutcome {
        val externalTransactionId = transaction.externalTransactionId
        val directSubmission = externalTransactionId?.let(submissions::findByExternalTransactionId)
        val boost =
            if (directSubmission == null) {
                externalTransactionId?.let(boosts::findByExternalTransactionId)
                    ?: boosts.findByNewVendorTransactionId(transaction.vendorTransactionId)
            } else {
                null
            }
        val submission =
            directSubmission
                ?: boost?.let { boostsAttempt ->
                    submissions.findByVendorTransactionId(boostsAttempt.rootVendorTransactionId)
                }
        if (submission == null) {
            return unregisteredVaultTransfer(inboxItem, transaction)
        }
        val rootVendorTransactionId =
            if (boost != null) {
                val registeredReplacement = boost.newVendorTransactionId
                if (registeredReplacement != null && registeredReplacement != transaction.vendorTransactionId) {
                    return quarantineNow(
                        inboxItem,
                        "boost vendor transaction id conflict: recorded=$registeredReplacement " +
                            "observed=${transaction.vendorTransactionId}",
                    )
                }
                boosts
                    .markSubmittedByObservation(
                        boost.externalTransactionId,
                        transaction.vendorTransactionId,
                        inboxItem.receivedAt,
                    ).rootVendorTransactionId
            } else {
                when (val registeredVendorTransactionId = submission.vendorTransactionId) {
                    null -> {
                        submissions.markSubmitted(
                            checkNotNull(externalTransactionId),
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
                transaction.vendorTransactionId
            }

        val status = statusTranslator.translate(transaction.statusObservation, submission.network)
        val viableBoost = boost ?: boosts.findLatestViableByRoot(rootVendorTransactionId)
        val stateChange =
            txStates.observeRoot(
                rootVendorTransactionId = rootVendorTransactionId,
                TxObservation(
                    vendorTransactionId = transaction.vendorTransactionId,
                    externalTransactionId = submission.externalTransactionId,
                    accountId = submission.senderAccountId,
                    network = submission.network,
                    symbol = submission.symbol,
                    transactionHash = transaction.transactionHash,
                    status = status,
                    confirmationCount = transaction.confirmationCount,
                    vendorSubStatus = transaction.subStatus,
                    vendorNetworkStatus = transaction.networkStatus,
                    observedAt = inboxItem.receivedAt,
                    vendorCreatedAt = CoreDateTimes.fromEpochMillis(transaction.createdAtEpochMillis),
                ),
                successEvidence =
                    viableBoost != null &&
                        PhysicalTransactionEvidence.hasSucceeded(transaction.statusObservation),
                deferFailure = viableBoost != null,
            )
        val eventType = submission.transactionType.customerEventType()
        if (eventType == null) {
            if (
                submission.transactionType == SubmissionTransactionType.SWEEP_BATCH &&
                (status.isTerminal() || inboxItem.eventType == NETWORK_RECORDS_COMPLETED_EVENT)
            ) {
                val executionId = checkNotNull(submission.sweepExecutionId) { "SWEEP_BATCH submission has no sweep execution id" }
                if (!sweepInvalidation.invalidate(
                        executionId,
                        transaction.vendorTransactionId,
                        transaction.transactionHash,
                        stateChange.record.lastPublishedStatus,
                        inboxItem.receivedAt,
                    )
                ) {
                    sweepExecutions.markReconciling(executionId, transaction.vendorTransactionId, transaction.transactionHash)
                }
            }
            markProcessed(inboxItem, transaction)
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
        markProcessed(inboxItem, transaction)
        return WebhookDecisionOutcome.Processed(inboxItem.notificationId, events.size)
    }

    private fun outboxEvent(
        inboxItem: WebhookInboxItem,
        transaction: WebhookTransaction,
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
                txHash = txRecord.transactionHash,
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
            eventDate = BusinessDates.now(clock),
            vendorTransactionId = txRecord.vendorTxId,
            eventType = OutboxEventType.forPublishedStatus(status),
            topic = eventType.topic,
            payload = eventSerializer.serialize(event),
            maxRetryCount = outboxMaxAttempts,
            traceId = inboxItem.notificationId,
        )
    }

    private fun unattributed(
        inboxItem: WebhookInboxItem,
        transaction: WebhookTransaction,
        network: String,
        symbol: String,
    ): WebhookDecisionOutcome.Unattributed {
        markProcessed(inboxItem, transaction)
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
        transaction: WebhookTransaction,
    ): WebhookDecisionOutcome.UnregisteredVaultTransfer {
        markProcessed(inboxItem, transaction)
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
        val failure =
            inboxRepository.recordFailure(
                inboxItem.notificationId,
                safeReason,
                maxAttempts,
                nextAttemptAt(
                    inboxItem.retryCount + 1,
                ),
            )
        return failure.toOutcome(inboxItem.notificationId)
    }

    /** 재시도가 결과를 바꾸지 못하는 영구 충돌은 상한을 기다리지 않고 한 번에 격리한다. */
    private fun quarantineNow(
        inboxItem: WebhookInboxItem,
        safeReason: String,
    ): WebhookDecisionOutcome {
        val failure = inboxRepository.recordFailure(inboxItem.notificationId, safeReason, IMMEDIATE_QUARANTINE, null)
        return failure.toOutcome(inboxItem.notificationId)
    }

    private fun WebhookFailureResult.toOutcome(notificationId: String): WebhookDecisionOutcome =
        if (quarantined) {
            WebhookDecisionOutcome.Quarantined(notificationId, retryCount)
        } else {
            WebhookDecisionOutcome.Retrying(notificationId, retryCount)
        }

    private fun markProcessed(
        inboxItem: WebhookInboxItem,
        transaction: WebhookTransaction? = null,
    ) {
        inboxRepository.markProcessed(
            inboxItem.notificationId,
            CoreDateTimes.now(clock),
            vendorCompleted = transaction?.statusObservation?.rawStatus == "COMPLETED",
        )
    }

    private fun TxStatus.isTerminal(): Boolean = this == TxStatus.FINALIZED || this == TxStatus.REJECTED || this == TxStatus.FAILED

    /**
     * [attempt]번째 실패 뒤 다음 시도 시각. backoff가 없으면 워커 주기(기본 500ms)마다 다시 집혀
     * 상한을 몇 초 만에 소진하고, 일시적 사정으로 실패한 건이 해소될 시간을 얻지 못한다(03 V29).
     */
    private fun nextAttemptAt(attempt: Int): String =
        CoreDateTimes.format(CoreDateTimes.current(clock).plusSeconds(WebhookRetryBackoff.delaySeconds(attempt, retryBaseSeconds)))

    private companion object {
        const val UNEXPECTED_FAILURE_REASON = "decision processing failed"
        const val IMMEDIATE_QUARANTINE = 1
        const val NETWORK_RECORDS_COMPLETED_EVENT = "transaction.network_records.processing_completed"
        val SUPPORTED_EVENT_TYPES =
            setOf(
                "transaction.created",
                "transaction.status.updated",
                "transaction.approval_status.updated",
                NETWORK_RECORDS_COMPLETED_EVENT,
            )
    }
}
