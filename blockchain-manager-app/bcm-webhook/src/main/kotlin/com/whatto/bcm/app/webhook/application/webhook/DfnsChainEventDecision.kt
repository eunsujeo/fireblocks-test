package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.asset.AssetDecimals
import com.whatto.bcm.domain.event.ChainEvent
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.event.EventType
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.tx.BlockDepthFinality
import com.whatto.bcm.domain.tx.ChainHeadPort
import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.tx.NetworkChainTransactionId
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.NetworkChainAttribution
import com.whatto.bcm.domain.vendor.NetworkChainAttributionMiss
import com.whatto.bcm.domain.vendor.NetworkChainAttributionResult
import com.whatto.bcm.domain.vendor.NetworkChainEventParser
import com.whatto.bcm.domain.vendor.NetworkChainLedgerLookup
import com.whatto.bcm.domain.vendor.NetworkChainTransfer
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.webhook.VendorWebhookDelivery
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import com.whatto.bcm.support.time.BusinessDates
import com.whatto.bcm.support.time.CoreDateTimes
import java.time.Clock
import java.time.Instant

/**
 * Dfns 온체인 이동 사건의 입금 판단(계약13). 호출자의 트랜잭션 안에서 원장 전이와 outbox 적재를 함께 수행한다.
 *
 * 확정은 벤더 상태가 아니라 **블록 깊이**로 낸다(CLAUDE.md 3절) — 사건의 `blockNumber`와 체인 head의 깊이를 관찰 컨펌 수로 담아
 * 네트워크 임계와 비교한다. head를 읽지 못하면 예외가 그대로 올라가 확정을 보류하고 인박스가 재시도한다.
 *
 * 입금이 아닌 결과(우리 발신·미지원 자산·미등록 자산·미귀속·정밀도 없음)는 원장을 쓰지 않고 결과로만 돌려준다 —
 * 무엇을 경보로 올리고 무엇을 넘길지는 워커가 정한다. **실행 빈으로 등록하지 않는 내부 대역이며 인박스 연결은 후속이다.**
 */
class DfnsChainEventDecision(
    private val parser: NetworkChainEventParser,
    private val ledger: NetworkChainLedgerLookup,
    private val chainHeads: ChainHeadPort,
    private val finalityPolicy: FinalityPolicy,
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
    ): DfnsChainDecisionOutcome {
        val event = parser.parse(payload) ?: return DfnsChainDecisionOutcome.NotChainEvent
        return when (val attribution = NetworkChainAttribution.attribute(event.observation, ledger)) {
            is NetworkChainAttributionResult.Deposit -> deposit(notificationId, event.delivery, attribution)
            is NetworkChainAttributionResult.Outgoing -> DfnsChainDecisionOutcome.Outgoing(attribution.observation)
            is NetworkChainAttributionResult.UnsupportedAsset -> DfnsChainDecisionOutcome.UnsupportedAsset(attribution.observation)
            is NetworkChainAttributionResult.UnmappedAsset -> DfnsChainDecisionOutcome.UnmappedAsset(attribution.observation)
            is NetworkChainAttributionResult.Unattributed ->
                DfnsChainDecisionOutcome.Unattributed(attribution.observation, attribution.miss)
        }
    }

    private fun deposit(
        notificationId: String,
        delivery: VendorWebhookDelivery,
        deposit: NetworkChainAttributionResult.Deposit,
    ): DfnsChainDecisionOutcome {
        val observation = deposit.observation
        // 금액이 없는 관찰은 모델링한 자산이 아니므로 귀속 단계에서 이미 갈린다 — 여기 오면 계약 위반이다.
        val baseUnits = checkNotNull(observation.amountBaseUnits) { "deposit observation must carry base units" }
        // 정밀도가 없으면 사람 단위 금액을 만들 수 없다. 0이나 최소 단위를 그대로 싣지 않는다 — 단위가 뒤섞이면 조용한 금액 사고다.
        val decimals = deposit.decimals ?: return DfnsChainDecisionOutcome.MissingDecimals(observation)
        val confirmations =
            BlockDepthFinality.confirmationCount(
                headBlockNumber = chainHeads.headBlockNumber(observation.network),
                blockNumber = observation.blockNumber,
            )
        val status =
            statusTranslator.translate(
                VendorStatusObservation(observation.status.vendorValue, subStatus = null, confirmationCount = confirmations),
                observation.network,
            )
        val stateChange =
            txStates.observe(
                TxObservation(
                    vendorTransactionId = transactionId(observation),
                    // 입금은 우리가 낸 제출이 아니므로 제출 키가 없다(Fireblocks 입금과 같다).
                    externalTransactionId = null,
                    accountId = deposit.accountId,
                    network = deposit.network,
                    symbol = deposit.symbol,
                    transactionHash = observation.transactionHash,
                    status = status,
                    confirmationCount = confirmations,
                    // Dfns에는 Fireblocks의 subStatus·networkStatus에 해당하는 원어가 없다 — 없는 값을 만들지 않는다.
                    vendorSubStatus = null,
                    vendorNetworkStatus = null,
                    observedAt = CoreDateTimes.now(clock),
                    // 벤더 시각은 형식이 서술된 알림 `date`를 쓴다 — 사건의 `timestamp`는 형식·시간대 서술이 없다(계약13).
                    vendorCreatedAt = CoreDateTimes.fromEpochMillis(occurredAtMillis(delivery)),
                ),
            )
        val events =
            stateChange.statusesToPublish.map { published ->
                outboxEvent(notificationId, deposit, observation, baseUnits, decimals, confirmations, published)
            }
        outboxEvents.enqueue(events)
        return DfnsChainDecisionOutcome.Processed(observation, status, events)
    }

    private fun outboxEvent(
        notificationId: String,
        deposit: NetworkChainAttributionResult.Deposit,
        observation: NetworkChainTransfer,
        baseUnits: String,
        decimals: Int,
        confirmations: Int,
        status: TxStatus,
    ): OutboxEvent {
        val eventId = eventIdGenerator.nextId()
        val event =
            ChainEvent(
                eventId = eventId,
                type = EventType.DEPOSIT,
                txId = transactionId(observation),
                txHash = observation.transactionHash,
                externalTxId = null,
                accountId = deposit.accountId,
                network = deposit.network,
                symbol = deposit.symbol,
                to = observation.toAddress,
                from = observation.fromAddress,
                // 등록 정밀도로 사람 단위 금액을 만든다 — 제공자와 무관하게 이벤트 금액의 단위는 하나다(02).
                amount = AssetDecimals.amountOf(baseUnits, decimals),
                status = status,
                numOfConfirmations = confirmations,
            )
        return OutboxEvent(
            eventId = eventId,
            eventDate = BusinessDates.now(clock),
            vendorTransactionId = event.txId,
            eventType = OutboxEventType.forPublishedStatus(status),
            topic = EventType.DEPOSIT.topic,
            payload = eventSerializer.serialize(event),
            maxRetryCount = outboxMaxAttempts,
            traceId = notificationId,
        )
    }

    /** 벤더가 입금에 거래 ID를 주지 않으므로 hash·순번에서 파생한다(03 V26). 순번이 없으면 고유 키를 만들 수 없어 실패한다. */
    private fun transactionId(observation: NetworkChainTransfer): String {
        val index = observation.eventIndex ?: throw WebhookPayloadException("Dfns 온체인 이동 사건 결손: index")
        return NetworkChainTransactionId.of(observation.network, observation.transactionHash, index)
    }

    private fun occurredAtMillis(delivery: VendorWebhookDelivery): Long =
        runCatching { Instant.parse(delivery.occurredAt).toEpochMilli() }
            .getOrElse { throw WebhookPayloadException("Dfns 웹훅 필드 형식 오류: date", it) }
}

/** 판단 결과 — 입금만 원장·outbox를 쓴다. 나머지는 워커가 경보·무시를 정한다. */
sealed interface DfnsChainDecisionOutcome {
    /** 온체인 이동 사건이 아니다(전송 알림·지갑/정책 사건). */
    data object NotChainEvent : DfnsChainDecisionOutcome

    data class Processed(
        val observation: NetworkChainTransfer,
        val status: TxStatus,
        val events: List<OutboxEvent>,
    ) : DfnsChainDecisionOutcome

    /** 우리 지갑 발신 — 제출 원장 대조가 필요하다(후속). */
    data class Outgoing(
        val observation: NetworkChainTransfer,
    ) : DfnsChainDecisionOutcome

    data class UnsupportedAsset(
        val observation: NetworkChainTransfer,
    ) : DfnsChainDecisionOutcome

    data class UnmappedAsset(
        val observation: NetworkChainTransfer,
    ) : DfnsChainDecisionOutcome

    data class Unattributed(
        val observation: NetworkChainTransfer,
        val miss: NetworkChainAttributionMiss,
    ) : DfnsChainDecisionOutcome

    /** 등록 자산인데 정밀도가 없어 이벤트 금액을 만들 수 없다(03 V27 이전 등록 행). */
    data class MissingDecimals(
        val observation: NetworkChainTransfer,
    ) : DfnsChainDecisionOutcome
}
