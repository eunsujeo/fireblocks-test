package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.asset.AssetDecimals
import com.whatto.bcm.domain.event.ChainEvent
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.event.EventType
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.tx.BlockDepthFinality
import com.whatto.bcm.domain.tx.ChainHeadPort
import com.whatto.bcm.domain.tx.NetworkChainTransactionId
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.tx.TxType
import com.whatto.bcm.domain.vendor.NetworkChainAttribution
import com.whatto.bcm.domain.vendor.NetworkChainAttributionMiss
import com.whatto.bcm.domain.vendor.NetworkChainAttributionResult
import com.whatto.bcm.domain.vendor.NetworkChainCoordinateResult
import com.whatto.bcm.domain.vendor.NetworkChainEventParser
import com.whatto.bcm.domain.vendor.NetworkChainIncomingReceipt
import com.whatto.bcm.domain.vendor.NetworkChainIncomingResult
import com.whatto.bcm.domain.vendor.NetworkChainLedgerLookup
import com.whatto.bcm.domain.vendor.NetworkChainOutgoingCoordinate
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
 * 확정은 벤더 상태가 아니라 **블록 깊이**로 낸다(CLAUDE.md 3절) — 사건의 `blockNumber`와 체인 head의 깊이를 관찰 컨펌 수로 담고
 * 네트워크 임계와의 비교는 [VendorStatusTranslator] 구현이 수행한다. head를 읽지 못하면 예외가 그대로 올라가 확정을 보류하고 인박스가 재시도한다.
 *
 * **발신은 이 사건의 블록 좌표로 확정까지 낸다**(아래 발신 분기). 미지원 자산·미등록 자산·미귀속·정밀도 없음·발신 주소 없음은 원장을 쓰지 않고 결과로만 돌려준다 —
 * 무엇을 경보로 올리고 무엇을 넘길지는 워커가 정한다. `dfns`에서 조립되어 `DfnsWebhookDecisionTransaction`이 호출한다.
 */
class DfnsChainEventDecision(
    private val parser: NetworkChainEventParser,
    private val ledger: NetworkChainLedgerLookup,
    private val submissions: SubmissionObservationService,
    private val chainHeads: ChainHeadPort,
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
        // 입금도 같은 (network, tx_hash)의 거래 행을 만든다 — 발신 좌표의 후보 조회가 그 삽입과 직렬화되려면
        // **모든 온체인 사건이** 같은 경계에 참여해야 한다. 한 경로라도 빠지면 팬텀 삽입이 남는다.
        txStates.lockNetworkTransactionHash(event.observation.network, event.observation.transactionHash)
        return when (val attribution = NetworkChainAttribution.attribute(event.observation, ledger)) {
            is NetworkChainAttributionResult.Deposit -> deposit(notificationId, event.delivery, attribution)
            is NetworkChainAttributionResult.Outgoing -> outgoing(notificationId, attribution.observation)
            is NetworkChainAttributionResult.UnsupportedAsset -> DfnsChainDecisionOutcome.UnsupportedAsset(attribution.observation)
            is NetworkChainAttributionResult.UnmappedAsset -> DfnsChainDecisionOutcome.UnmappedAsset(attribution.observation)
            is NetworkChainAttributionResult.Unattributed ->
                DfnsChainDecisionOutcome.Unattributed(attribution.observation, attribution.miss, attribution.network, attribution.symbol)
        }
    }

    /**
     * 발신 확정의 블록 좌표(계약13). **새 거래를 만들지 않는다** — 우리가 낸 전송은 전송 알림이 이미 `bcm_tx_l`에 만들어 뒀다.
     *
     * 이 사건에서 읽는 것은 **`txHash`가 어느 블록에 있는가** 하나이고, 그건 어느 이동이 실어 왔든 같은 답이다.
     * 그래서 **이 이동이 어느 제출의 것인지 묻지 않는다** — 벤더가 이동과 제출을 잇는 키를 주지 않아 증명할 수 없고,
     * 증명할 수 없는 판정을 확정의 관문으로 세우면 원장 밖 이동이 우리 제출의 확정을 만들어 낸다(2026-09-17 사용자 확정).
     * 귀속의 근거는 **벤더가 우리 전송 요청에 결속해 준 `txHash`**이며 전송 알림이 그것을 원장에 적었다.
     *
     * 출금의 **확정이 여기서 난다** — 전송 알림에는 `blockNumber`가 없어 깊이를 계산할 수 없고, 이 사건에만 블록 좌표가 있다.
     * 아직 그 hash의 발신 거래가 없으면(전송 알림이 늦다) 만들지 않고 재시도로 남긴다.
     */
    private fun outgoing(
        notificationId: String,
        observation: NetworkChainTransfer,
    ): DfnsChainDecisionOutcome {
        // 경계는 decide 진입부에서 이미 잡았다 — 그 안에서 후보를 읽는다.
        val candidates = txStates.findByNetworkAndTransactionHash(observation.network, observation.transactionHash)
        return when (val coordinate = NetworkChainOutgoingCoordinate.apply(candidates)) {
            is NetworkChainCoordinateResult.NoOutgoingRecord -> DfnsChainDecisionOutcome.OutgoingPending(observation)
            is NetworkChainCoordinateResult.Advance -> advanceOutgoing(notificationId, observation, coordinate.records)
        }
    }

    private fun advanceOutgoing(
        notificationId: String,
        observation: NetworkChainTransfer,
        records: List<TxRecord>,
    ): DfnsChainDecisionOutcome {
        val confirmations =
            BlockDepthFinality.confirmationCount(
                headBlockNumber = chainHeads.headBlockNumber(observation.network),
                blockNumber = observation.blockNumber,
            )
        // 같은 블록의 같은 관찰이므로 번역 결과도 하나다 — 거래마다 다시 계산하지 않는다.
        val status =
            statusTranslator.translate(
                VendorStatusObservation(observation.status.vendorValue, subStatus = null, confirmationCount = confirmations),
                observation.network,
            )
        val events = records.flatMap { record -> advance(notificationId, observation, record, status, confirmations) }
        outboxEvents.enqueue(events)
        return DfnsChainDecisionOutcome.OutgoingAdvanced(observation, status, records.size, events)
    }

    private fun advance(
        notificationId: String,
        observation: NetworkChainTransfer,
        record: TxRecord,
        status: TxStatus,
        confirmations: Int,
    ): List<OutboxEvent> {
        val externalTransactionId = requireNotNull(record.externalTxId) { "outgoing record must carry a submission key" }
        // 제출 키로 **직접** 찾는다 — 값이 닮았는지 대조해 고르는 것이 아니다. 없으면 원장 결함이므로 감추지 않고 올린다.
        val submission =
            checkNotNull(submissions.findByExternalTransactionId(externalTransactionId)) {
                "submission ledger row is missing for an outgoing transaction"
            }
        val stateChange =
            txStates.observe(
                TxObservation(
                    // 좌표를 주는 것이지 만드는 게 아니다 — 키는 기존 거래의 벤더 전송 ID다.
                    vendorTransactionId = record.vendorTxId,
                    externalTransactionId = externalTransactionId,
                    accountId = record.accountId,
                    network = record.network,
                    symbol = record.symbol,
                    transactionHash = observation.transactionHash,
                    status = status,
                    confirmationCount = confirmations,
                    vendorSubStatus = null,
                    vendorNetworkStatus = null,
                    observedAt = CoreDateTimes.now(clock),
                    vendorCreatedAt = CoreDateTimes.now(clock),
                    // 금액은 **제출 시점에 확정한 값**을 쓴다 — 현재 매핑을 다시 읽으면 그 사이 교체된 정밀도로 다른 값이 나온다(03 V34).
                    observedAmount = submission.amount,
                    observedAmountBaseUnits = submission.vendorCanonical?.amountBaseUnits,
                    observedAmountDecimals = submission.vendorCanonical?.decimals,
                    observedSourceAddress = observation.fromAddress,
                    // 우리가 실제로 보낸 목적지다(03 V30) — 논리 목적지(`recipientValue`)는 내부이체에서 accountId다.
                    observedDestinationAddress = submission.vendorCanonical?.destinationAddress ?: observation.toAddress,
                ),
                attributedType = submission.transactionType.txType(),
            )
        val eventType = submission.transactionType.customerEventType() ?: return emptyList()
        return stateChange.statusesToPublish.map { published ->
            outgoingEvent(notificationId, eventType, submission, record, observation, confirmations, published)
        }
    }

    private fun outgoingEvent(
        notificationId: String,
        eventType: EventType,
        submission: SubmissionRecord,
        record: TxRecord,
        observation: NetworkChainTransfer,
        confirmations: Int,
        status: TxStatus,
    ): OutboxEvent {
        val eventId = eventIdGenerator.nextId()
        val event =
            ChainEvent(
                eventId = eventId,
                type = eventType,
                txId = record.vendorTxId,
                txHash = observation.transactionHash,
                externalTxId = submission.externalTransactionId,
                accountId = submission.senderAccountId,
                network = submission.network,
                symbol = submission.symbol,
                // **온체인 목적지 주소**다(02·공개 계약). 논리 목적지(recipientValue)는 내부이체에서 accountId라 그대로 실으면 안 된다(03 V30).
                to = submission.vendorCanonical?.destinationAddress ?: submission.recipientValue,
                from = null,
                // 금액은 원장의 사람 단위 값이다 — 관찰의 최소 단위를 다시 환산하지 않는다(단위가 뒤섞이면 조용한 금액 사고다).
                amount = submission.amount,
                status = status,
                numOfConfirmations = confirmations,
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

    private fun deposit(
        notificationId: String,
        delivery: VendorWebhookDelivery,
        deposit: NetworkChainAttributionResult.Deposit,
    ): DfnsChainDecisionOutcome {
        val observation = deposit.observation
        // 우리 내부이체의 수신측이면 입금을 만들지 않는다 — 업무 이벤트는 제출 원장 쪽에서 이미 났다(계약13).
        // 경계는 decide 진입부에서 이미 잡았다.
        val receipt =
            NetworkChainIncomingReceipt.judge(
                txStates.findByNetworkAndTransactionHash(observation.network, observation.transactionHash),
                senderIsOurWallet = observation.fromAddress?.let { ledger.ownsWalletAddress(observation.network, it) } == true,
            )
        when (receipt) {
            is NetworkChainIncomingResult.OurOutgoing -> return DfnsChainDecisionOutcome.InternalReceipt(observation)
            is NetworkChainIncomingResult.Unresolved -> return DfnsChainDecisionOutcome.IncomingUnresolved(observation)
            is NetworkChainIncomingResult.External -> Unit
        }
        // 금액이 없는 관찰은 모델링한 자산이 아니므로 귀속 단계에서 이미 갈린다 — 여기 오면 계약 위반이다.
        val baseUnits = checkNotNull(observation.amountBaseUnits) { "deposit observation must carry base units" }
        // 정밀도가 없으면 사람 단위 금액을 만들 수 없다. 0이나 최소 단위를 그대로 싣지 않는다 — 단위가 뒤섞이면 조용한 금액 사고다.
        val decimals = deposit.decimals ?: return DfnsChainDecisionOutcome.MissingDecimals(observation)
        // 02는 입금 이벤트에 발신 주소가 항상 실린다고 확정했고 DAW-CORE의 입금 판별 게이트가 그 값을 쓴다.
        // 명세상 `Native`·`Spl` 변형의 `from`은 선택이라 없을 수 있다 — 없으면 이벤트를 만들지 않고 멈춘다.
        val sender = observation.fromAddress ?: return DfnsChainDecisionOutcome.MissingSender(observation)
        // 결정적 payload 오류(순번 결손)는 외부 호출 전에 드러낸다 — RPC 장애에 가려 불필요하게 재시도하지 않는다.
        val transactionId = transactionId(observation)
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
                    vendorTransactionId = transactionId,
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
                    // 환산은 **여기서 한 번만** 한다. 저장은 매핑을 다시 읽지 않고, 재처리는 아래 근거로 재환산한다(03 V34).
                    observedAmount = AssetDecimals.amountOf(baseUnits, decimals),
                    observedAmountBaseUnits = baseUnits,
                    observedAmountDecimals = decimals,
                    observedSourceAddress = sender,
                    observedDestinationAddress = observation.toAddress,
                ),
                attributedType = TxType.DEPOSIT,
            )
        val events =
            stateChange.statusesToPublish.map { published ->
                outboxEvent(notificationId, transactionId, deposit, observation, sender, baseUnits, decimals, confirmations, published)
            }
        outboxEvents.enqueue(events)
        return DfnsChainDecisionOutcome.Processed(observation, status, events)
    }

    private fun outboxEvent(
        notificationId: String,
        transactionId: String,
        deposit: NetworkChainAttributionResult.Deposit,
        observation: NetworkChainTransfer,
        sender: String,
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
                txId = transactionId,
                txHash = observation.transactionHash,
                externalTxId = null,
                accountId = deposit.accountId,
                network = deposit.network,
                symbol = deposit.symbol,
                to = observation.toAddress,
                from = sender,
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

/** 판단 결과 — 입금과 **붙은 발신**이 원장·outbox를 쓴다. 나머지는 워커가 재시도·격리·무시를 정한다. */
sealed interface DfnsChainDecisionOutcome {
    /** 온체인 이동 사건이 아니다(전송 알림·지갑/정책 사건). */
    data object NotChainEvent : DfnsChainDecisionOutcome

    data class Processed(
        val observation: NetworkChainTransfer,
        val status: TxStatus,
        val events: List<OutboxEvent>,
    ) : DfnsChainDecisionOutcome

    /** 이 hash의 우리 발신 거래에 블록 좌표를 적용했다 — 출금의 확정이 여기서 난다. */
    data class OutgoingAdvanced(
        val observation: NetworkChainTransfer,
        val status: TxStatus,
        val recordCount: Int,
        val events: List<OutboxEvent>,
    ) : DfnsChainDecisionOutcome

    /**
     * 이 hash로 기록된 우리 발신 거래가 아직 없다. **거래를 만들지 않고 재처리 가능한 상태로 남긴다** —
     * 전송 알림이 늦게 올 수 있고(도착 순서는 수용 항목), 처리 완료로 닫으면 그 출금은 영영 확정되지 않는다.
     * 우리가 내지 않은 발신이면 상한까지 해소되지 않아 격리된다.
     */
    data class OutgoingPending(
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
        val network: String,
        val symbol: String,
    ) : DfnsChainDecisionOutcome

    /**
     * 우리 내부이체의 **수신측**이다 — 같은 hash에 우리 발신 거래가 있다. 업무 이벤트는 제출 원장 쪽에서 이미 났으므로
     * 입금을 만들지 않는다. 만들면 한 번의 이동에 `INTERNAL`과 `DEPOSIT`이 둘 다 나가 없는 입금이 인정된다(계약13).
     */
    data class InternalReceipt(
        val observation: NetworkChainTransfer,
    ) : DfnsChainDecisionOutcome

    /**
     * 발신이 우리 지갑인데 그 hash의 발신 거래가 아직 없다. **입금으로 확정하지 않고 보류한다** —
     * 전송 알림이 늦을 수 있고, 이벤트는 취소가 안 되므로 확정보다 보류가 맞다.
     */
    data class IncomingUnresolved(
        val observation: NetworkChainTransfer,
    ) : DfnsChainDecisionOutcome

    /** 등록 자산인데 정밀도가 없어 이벤트 금액을 만들 수 없다(03 V27 이전 등록 행). */
    data class MissingDecimals(
        val observation: NetworkChainTransfer,
    ) : DfnsChainDecisionOutcome

    /**
     * 발신 주소가 없어 입금 이벤트를 만들 수 없다. 02는 입금 이벤트에 발신 주소가 항상 실린다고 확정했고
     * DAW-CORE의 입금 판별이 그 값을 쓴다 — 명세상 선택 필드라고 비운 채 내보내지 않는다.
     */
    data class MissingSender(
        val observation: NetworkChainTransfer,
    ) : DfnsChainDecisionOutcome
}
