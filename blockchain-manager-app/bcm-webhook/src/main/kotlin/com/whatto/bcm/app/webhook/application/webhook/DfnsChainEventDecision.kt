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
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical
import com.whatto.bcm.domain.tx.BlockDepthFinality
import com.whatto.bcm.domain.tx.ChainHeadPort
import com.whatto.bcm.domain.tx.NetworkChainTransactionId
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.NetworkChainAttachmentResult
import com.whatto.bcm.domain.vendor.NetworkChainAttribution
import com.whatto.bcm.domain.vendor.NetworkChainAttributionMiss
import com.whatto.bcm.domain.vendor.NetworkChainAttributionResult
import com.whatto.bcm.domain.vendor.NetworkChainEventParser
import com.whatto.bcm.domain.vendor.NetworkChainLedgerLookup
import com.whatto.bcm.domain.vendor.NetworkChainOutgoingAttachment
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
 * **발신은 기존 거래에 붙여 확정까지 낸다**(아래 발신 분기). 미지원 자산·미등록 자산·미귀속·정밀도 없음·발신 주소 없음은 원장을 쓰지 않고 결과로만 돌려준다 —
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
        // 입금도 같은 (network, tx_hash)의 거래 행을 만든다 — 발신 붙임의 후보 조회가 그 삽입과 직렬화되려면
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
     * 발신 이동의 대조(계약13). **새 거래를 만들지 않는다** — `(ntwk_cd, tx_hash)`로 기존 거래를 찾아 그 거래의 전이로 반영한다.
     * 붙임 조건은 네 가지다 — **후보가 정확히 하나**, **제출 원장에 대응**, **관찰이 그 제출의 지갑·자산 키·최소 단위 금액·목적지와 일치**,
     * **같은 값의 미결 제출이 없음**
     * ([NetworkChainOutgoingAttachment]). 후보 없음과 미결 제출 배제는 재시도로 남기고(전송 알림이 늦을 수 있다),
     * 여럿·대응 없음·불일치는 워커가 즉시 격리한다.
     *
     * 출금의 **확정이 여기서 난다** — 전송 알림에는 `blockNumber`가 없어 깊이를 계산할 수 없고, 이 사건에만 블록 좌표가 있다.
     */
    private fun outgoing(
        notificationId: String,
        observation: NetworkChainTransfer,
    ): DfnsChainDecisionOutcome {
        val network = observation.network
        val hash = observation.transactionHash
        // 경계는 decide 진입부에서 이미 잡았다 — 그 안에서 후보를 읽는다.
        val attached =
            NetworkChainOutgoingAttachment.attach(
                observation,
                txStates.findByNetworkAndTransactionHash(network, hash),
                submissionLookup,
            )
        return when (attached) {
            // 아직 못 붙이는 것이지 잘못된 것이 아니다 — 전송 알림이 늦게 올 수 있다. 처리 완료로 닫으면 그 출금은 영영 확정되지 않는다.
            is NetworkChainAttachmentResult.NoCandidate -> DfnsChainDecisionOutcome.OutgoingPending(observation)
            is NetworkChainAttachmentResult.Ambiguous ->
                DfnsChainDecisionOutcome.OutgoingAmbiguous(observation, attached.candidateCount)

            // 시간이 지나도 해소되지 않는 이상 신호다 — 재처리로 풀리지 않는다.
            is NetworkChainAttachmentResult.NoSubmission ->
                DfnsChainDecisionOutcome.OutgoingUnattachable(observation, OutgoingAttachMiss.NO_SUBMISSION)

            is NetworkChainAttachmentResult.Mismatched ->
                DfnsChainDecisionOutcome.OutgoingUnattachable(observation, OutgoingAttachMiss.MISMATCH)

            // 같은 값의 제출이 아직 hash를 못 받았다 — 그쪽 알림이 hash와 함께 오면 해소된다(보장은 아니다·계약13). 보류한다.
            is NetworkChainAttachmentResult.Unresolved -> DfnsChainDecisionOutcome.OutgoingPending(observation)

            is NetworkChainAttachmentResult.Attach -> attachOutgoing(notificationId, observation, attached)
        }
    }

    private val submissionLookup =
        object : NetworkChainOutgoingAttachment.SubmissionLookup {
            override fun byVendorTransactionId(vendorTransactionId: String) = submissions.findByVendorTransactionId(vendorTransactionId)

            override fun hasUnresolvedWithSameCanonical(
                excludingExternalTransactionId: String,
                canonical: SubmissionVendorCanonical,
                recipientValue: String,
            ) = submissions.existsUnresolvedWithSameCanonical(excludingExternalTransactionId, canonical, recipientValue)
        }

    private fun attachOutgoing(
        notificationId: String,
        observation: NetworkChainTransfer,
        attached: NetworkChainAttachmentResult.Attach,
    ): DfnsChainDecisionOutcome {
        val submission = attached.submission
        val confirmations =
            BlockDepthFinality.confirmationCount(
                headBlockNumber = chainHeads.headBlockNumber(observation.network),
                blockNumber = observation.blockNumber,
            )
        val status =
            statusTranslator.translate(
                VendorStatusObservation(observation.status.vendorValue, subStatus = null, confirmationCount = confirmations),
                submission.network,
            )
        val stateChange =
            txStates.observe(
                TxObservation(
                    // 붙이는 것이지 만드는 게 아니다 — 키는 기존 거래의 벤더 전송 ID다.
                    vendorTransactionId = attached.record.vendorTxId,
                    externalTransactionId = submission.externalTransactionId,
                    accountId = submission.senderAccountId,
                    network = submission.network,
                    symbol = submission.symbol,
                    transactionHash = observation.transactionHash,
                    status = status,
                    confirmationCount = confirmations,
                    vendorSubStatus = null,
                    vendorNetworkStatus = null,
                    observedAt = CoreDateTimes.now(clock),
                    vendorCreatedAt = CoreDateTimes.now(clock),
                ),
            )
        val eventType = submission.transactionType.customerEventType()
        if (eventType == null) {
            return DfnsChainDecisionOutcome.OutgoingAttached(observation, status, emptyList())
        }
        val events =
            stateChange.statusesToPublish.map { published ->
                outgoingEvent(notificationId, eventType, submission, attached.record, observation, confirmations, published)
            }
        outboxEvents.enqueue(events)
        return DfnsChainDecisionOutcome.OutgoingAttached(observation, status, events)
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
                to = submission.recipientValue,
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
                ),
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

    /** 발신 이동을 기존 거래에 붙였다 — 출금의 확정이 여기서 난다. */
    data class OutgoingAttached(
        val observation: NetworkChainTransfer,
        val status: TxStatus,
        val events: List<OutboxEvent>,
    ) : DfnsChainDecisionOutcome

    /**
     * 붙일 거래가 아직 없다. **거래를 만들지 않고 재처리 가능한 상태로 남긴다** —
     * 전송 알림이 늦게 올 수 있고(도착 순서는 수용 항목), 처리 완료로 닫으면 그 출금은 영영 확정되지 않는다.
     */
    data class OutgoingPending(
        val observation: NetworkChainTransfer,
    ) : DfnsChainDecisionOutcome

    /**
     * 붙일 수 없다 — 제출 원장 대응이 없거나(우리가 낸 전송이 아니다) 관찰이 그 제출의 값과 다르다.
     * 시간이 지나도 해소되지 않으므로 재처리하지 않고 운영 신호로 남긴다.
     */
    data class OutgoingUnattachable(
        val observation: NetworkChainTransfer,
        val miss: OutgoingAttachMiss,
    ) : DfnsChainDecisionOutcome

    /** 같은 `(ntwk_cd, tx_hash)`에 거래가 여럿이다 — 하나를 고르는 규칙을 지어내지 않고 중단한다. */
    data class OutgoingAmbiguous(
        val observation: NetworkChainTransfer,
        val candidateCount: Int,
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

/** 발신 이동을 붙이지 못한 이유 — 경보·격리 사유에 쓰이며 원문·주소·금액을 담지 않는다. */
enum class OutgoingAttachMiss {
    /** 후보 거래는 있는데 제출 원장에 대응이 없다. */
    NO_SUBMISSION,

    /** 후보·제출은 있는데 관찰이 그 제출의 canonical 값과 다르다(또는 저장값이 없어 증명할 수 없다). */
    MISMATCH,
}
