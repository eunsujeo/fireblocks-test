package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.event.ChainEvent
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.event.EventType
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.submission.NetworkTransferJudgement
import com.whatto.bcm.domain.submission.NetworkTransferJudgementResult
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.NetworkTransferEventParser
import com.whatto.bcm.domain.vendor.NetworkTransferObservation
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.webhook.VendorWebhookDelivery
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import com.whatto.bcm.support.time.BusinessDates
import com.whatto.bcm.support.time.CoreDateTimes
import java.time.Clock
import java.time.Instant

/**
 * Dfns 전송 알림(`wallet.transfer.*`)의 판단 — **우리가 낸 발신 거래**의 원장 전이와 outbox 적재다(계약13).
 *
 * 계열은 **제출 원장으로만** 가른다(02 "웹훅 계열 분류 — 제출 원장이 기준이다") — 알림 종류나 금액·주소 추정으로 가르지 않는다.
 * 원장에 없는 전송은 우리가 만든 게 아니므로 원장·이벤트를 만들지 않고 결과로만 돌려준다.
 *
 * **확정은 여기서 내지 않는다.** 전송 알림에는 `blockNumber`가 없어 블록 깊이를 계산할 수 없다(CLAUDE.md 3절) —
 * 관찰 컨펌 수를 0으로 두면 번역기가 확정을 내지 않는다. 발신의 확정은 온체인 이동 사건을 이 거래에 붙일 때 난다
 * ([DfnsChainEventDecision]의 발신 대조).
 */
class DfnsTransferEventDecision(
    private val parser: NetworkTransferEventParser,
    private val submissions: SubmissionObservationService,
    private val statusTranslator: VendorStatusTranslator,
    private val txStates: TxStateService,
    private val outboxEvents: OutboxEventService,
    private val eventIdGenerator: EventIdGenerator,
    private val eventSerializer: ChainEventSerializer,
    private val clock: Clock,
    private val outboxMaxAttempts: Int,
) {
    fun decide(
        notificationId: String,
        payload: ByteArray,
    ): DfnsTransferDecisionOutcome {
        val event = parser.parse(payload) ?: return DfnsTransferDecisionOutcome.NotTransferEvent
        val observation = event.observation
        return when (val judged = NetworkTransferJudgement.judge(observation, lookup)) {
            is NetworkTransferJudgementResult.Unknown -> DfnsTransferDecisionOutcome.UnknownSubmission(observation)
            is NetworkTransferJudgementResult.Conflicting ->
                DfnsTransferDecisionOutcome.Conflicting(observation, judged.submission.externalTransactionId)

            is NetworkTransferJudgementResult.Ours -> ours(notificationId, event.delivery, observation, judged.submission)
        }
    }

    private fun ours(
        notificationId: String,
        delivery: VendorWebhookDelivery,
        observation: NetworkTransferObservation,
        submission: SubmissionRecord,
    ): DfnsTransferDecisionOutcome {
        // 업무 계열은 원장의 거래 구분에서 읽는다. 고객 이벤트가 없는 계열(Sweep·밴드S)은 원장만 잇고 이벤트를 만들지 않는다.
        val eventType = submission.transactionType.customerEventType()

        // 응답을 못 받아 비어 있던 벤더 전송 ID를 이 알림이 채운다(02) — 같은 결과에 도달하는 두 번째 경로다.
        if (submission.vendorTransactionId == null) {
            submissions.markSubmitted(submission.externalTransactionId, observation.transferId, CoreDateTimes.now(clock))
        }

        // 거래를 만드는 쪽도 같은 경계를 잡는다 — 발신 붙임의 후보 조회와 직렬화되어야 팬텀 삽입이 생기지 않는다.
        observation.transactionHash?.let { txStates.lockNetworkTransactionHash(submission.network, it) }

        val status =
            statusTranslator.translate(
                // 전송 알림에는 blockNumber가 없다 — 컨펌 수를 0으로 두어 번역기가 확정을 내지 않게 한다.
                VendorStatusObservation(observation.status.vendorValue, subStatus = null, confirmationCount = 0),
                submission.network,
            )
        val stateChange =
            txStates.observe(
                TxObservation(
                    vendorTransactionId = observation.transferId,
                    externalTransactionId = submission.externalTransactionId,
                    accountId = submission.senderAccountId,
                    // 계정·네트워크·심볼·금액은 **원장에 적힌 우리 요청**에서 읽는다 — 알림이 알려준 값으로 업무 귀속을 바꾸지 않는다.
                    network = submission.network,
                    symbol = submission.symbol,
                    transactionHash = observation.transactionHash,
                    status = status,
                    confirmationCount = 0,
                    // Dfns에는 Fireblocks의 subStatus·networkStatus에 해당하는 원어가 없다.
                    vendorSubStatus = null,
                    vendorNetworkStatus = null,
                    observedAt = CoreDateTimes.now(clock),
                    vendorCreatedAt = CoreDateTimes.fromEpochMillis(occurredAtMillis(delivery)),
                ),
            )
        if (eventType == null) {
            return DfnsTransferDecisionOutcome.Processed(observation, status, emptyList())
        }
        val events =
            stateChange.statusesToPublish.map { published ->
                outboxEvent(notificationId, eventType, submission, observation, published)
            }
        outboxEvents.enqueue(events)
        return DfnsTransferDecisionOutcome.Processed(observation, status, events)
    }

    private fun outboxEvent(
        notificationId: String,
        eventType: EventType,
        submission: SubmissionRecord,
        observation: NetworkTransferObservation,
        status: TxStatus,
    ): OutboxEvent {
        val eventId = eventIdGenerator.nextId()
        val event =
            ChainEvent(
                eventId = eventId,
                type = eventType,
                txId = observation.transferId,
                txHash = observation.transactionHash,
                externalTxId = submission.externalTransactionId,
                accountId = submission.senderAccountId,
                network = submission.network,
                symbol = submission.symbol,
                to = submission.recipientValue,
                // 발신은 우리 지갑이 보낸 것이라 from은 제출 원장이 아니라 관찰에서 읽을 값이 없다 — 02의 발신 이벤트는 to만 요구한다.
                from = null,
                // 금액은 원장의 사람 단위 값을 그대로 쓴다. 최소 단위 환산은 제출 때 이미 했고 여기서 다시 하지 않는다.
                amount = submission.amount,
                status = status,
                numOfConfirmations = 0,
            )
        return OutboxEvent(
            eventId = eventId,
            eventDate = BusinessDates.now(clock),
            vendorTransactionId = event.txId,
            eventType = OutboxEventType.forPublishedStatus(status),
            topic = eventType.topic,
            payload = eventSerializer.serialize(event),
            maxRetryCount = outboxMaxAttempts,
            traceId = notificationId,
        )
    }

    private val lookup =
        object : NetworkTransferJudgement.SubmissionLookup {
            override fun byVendorTransactionId(vendorTransactionId: String): SubmissionRecord? =
                submissions.findByVendorTransactionId(vendorTransactionId)

            override fun byExternalTransactionId(externalTransactionId: String): SubmissionRecord? =
                submissions.findByExternalTransactionId(externalTransactionId)
        }

    private fun occurredAtMillis(delivery: VendorWebhookDelivery): Long =
        runCatching { Instant.parse(delivery.occurredAt).toEpochMilli() }
            .getOrElse { throw WebhookPayloadException("Dfns 웹훅 필드 형식 오류: date", it) }
}

/** 전송 알림 판단 결과 — 우리가 낸 전송만 원장·outbox를 쓴다. */
sealed interface DfnsTransferDecisionOutcome {
    /** 전송 알림이 아니다(온체인 이동·지갑/정책 사건). */
    data object NotTransferEvent : DfnsTransferDecisionOutcome

    data class Processed(
        val observation: NetworkTransferObservation,
        val status: TxStatus,
        val events: List<OutboxEvent>,
    ) : DfnsTransferDecisionOutcome

    /**
     * 제출 원장에 없는 전송이다. 벤더 ID만으로 계정·업무 계열을 지어낼 수 없으므로 원장·이벤트를 만들지 않는다 —
     * 남의 자금이 우리 원장에 들어오는 것보다 경보가 낫다.
     */
    data class UnknownSubmission(
        val observation: NetworkTransferObservation,
    ) : DfnsTransferDecisionOutcome

    /** 한 제출 키에 다른 전송 ID가 이미 붙어 있다 — 재시도로 풀리지 않으므로 즉시 격리한다. */
    data class Conflicting(
        val observation: NetworkTransferObservation,
        val externalTransactionId: String,
    ) : DfnsTransferDecisionOutcome
}
