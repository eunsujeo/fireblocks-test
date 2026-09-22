package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.tx.TxObservationBatchOutcome
import com.whatto.bcm.app.application.tx.TxObservationOutcome
import com.whatto.bcm.app.application.tx.TxObservationRequest
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.tx.ChainHeadPort
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxObservationConsistency
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStateChange
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.LedgerAsset
import com.whatto.bcm.domain.vendor.NetworkChainAttributionMiss
import com.whatto.bcm.domain.vendor.NetworkChainDirection
import com.whatto.bcm.domain.vendor.NetworkChainEvent
import com.whatto.bcm.domain.vendor.NetworkChainEventKind
import com.whatto.bcm.domain.vendor.NetworkChainEventParser
import com.whatto.bcm.domain.vendor.NetworkChainLedgerLookup
import com.whatto.bcm.domain.vendor.NetworkChainTransfer
import com.whatto.bcm.domain.vendor.NetworkChainTransferStatus
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.webhook.VendorWebhookDelivery
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Dfns 온체인 이동 사건의 입금 판단(계약13) — 확정은 블록 깊이로 내고, 입금이 아닌 결과는 원장을 쓰지 않는다.
 */
class DfnsChainEventDecisionTest {
    private val submissions = mockk<SubmissionObservationService>(relaxed = true)

    private val txStates =
        mockk<TxStateService> {
            every { lockNetworkTransactionHash(any(), any()) } returns Unit
            // 기본은 '그 hash의 거래 없음' — 입금 경로도 수신측 중복 입금 방지를 위해 후보를 읽는다(계약13).
            every { findByNetworkAndTransactionHash(any(), any()) } returns emptyList()
        }
    private val outboxEvents = mockk<OutboxEventService>(relaxed = true)
    private val chainHeads = mockk<ChainHeadPort>()
    private var eventSequence = 1

    /** 마지막으로 원장에 넘긴 관찰. 두 입구 어느 쪽으로 들어와도 여기 담긴다. */
    private val observed = slot<TxObservation>()

    /** 여러 행을 한 번에 넘긴 요청 — 후보마다 제 관찰이 실렸는지 본다. */
    private val batch = slot<List<TxObservationRequest>>()

    /**
     * 동일성 검사를 통과시킨다. 경로마다 입구가 다르다 — 한 행이면 `observeConsistently`,
     * 한 트랜잭션에서 여러 행이면 `lockAndCheck` 전부 뒤 `applyChecked` 전부다(03 V32).
     */
    private fun stubObserve(change: () -> TxStateChange) {
        every { txStates.observeConsistently(any(), capture(observed), any(), any(), any()) } answers {
            TxObservationOutcome.Applied(change())
        }
        every { txStates.observeAllConsistently(capture(batch)) } answers {
            observed.captured = batch.captured.last().observation
            TxObservationBatchOutcome.Applied(batch.captured.map { change() })
        }
    }

    /** 이 관찰을 충돌로 판정하게 한다 — 두 입구 모두 막아 어느 경로로 와도 같은 답을 준다. */
    private fun stubConflict() {
        val conflict = TxObservationConsistency.Result.Conflict("amount", "1.5", "2.5")
        every { txStates.observeConsistently(any(), any(), any(), any(), any()) } returns TxObservationOutcome.Conflict(conflict)
        every { txStates.observeAllConsistently(any()) } returns TxObservationBatchOutcome.Conflict(conflict)
    }

    /** 원장에 아무 관찰도 반영하지 않았다 — 두 입구 모두 닫혀 있어야 한다. */
    private fun verifyNoObservation() {
        verify(exactly = 0) { txStates.observeConsistently(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { txStates.observeAllConsistently(any()) }
    }

    @Test
    fun `발급 주소 입금은 블록 깊이로 확정을 판정하고 등록 정밀도로 금액을 만든다`() {
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_130
        stubObserve { stateChange(TxStatus.FINALIZED) }
        val enqueued = slot<List<OutboxEvent>>()
        every { outboxEvents.enqueue(capture(enqueued)) } returns Unit

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOf(DfnsChainDecisionOutcome.Processed::class.java)
        // 12블록 깊이(8452130 - 8452119 + 1)가 임계 12 이상이라 확정이다.
        assertThat(observed.captured.confirmationCount).isEqualTo(12)
        assertThat(observed.captured.status).isEqualTo(TxStatus.FINALIZED)
        assertThat(observed.captured.accountId).isEqualTo(ACCOUNT_ID)
        assertThat(observed.captured.externalTransactionId).isNull()
        assertThat(observed.captured.vendorSubStatus).isNull()
        assertThat(observed.captured.transactionHash).isEqualTo(TX_HASH)
        // 벤더 시각은 형식이 서술된 알림 `date`에서 만든다.
        assertThat(observed.captured.vendorCreatedAt).isEqualTo("20260916000005")
        // 입금의 거래 ID는 hash·순번에서 파생한다(03 V26).
        assertThat(observed.captured.vendorTransactionId).startsWith("dfns-").hasSize(57)

        val event = enqueued.captured.single()
        assertThat(observed.captured.symbol).isEqualTo("USDC")
        assertThat(event.eventType).isEqualTo(OutboxEventType.CONFIRMED)
        assertThat(event.topic).isEqualTo("deposit-events")
        assertThat(event.traceId).isEqualTo(NOTIFICATION_ID)
        assertThat(event.vendorTransactionId).isEqualTo(observed.captured.vendorTransactionId)
        // 최소 단위 1500000을 등록 정밀도 6으로 환산한다 — 제공자와 무관하게 이벤트 금액의 단위는 하나다.
        assertThat(event.payload).contains("\"amount\":\"1.5\"")
    }

    @Test
    fun `입금 이벤트 금액은 현재 매핑 환산값이 아니라 원장 값이다`() {
        // 정밀도가 바뀐 뒤 같은 사건을 재처리하면 현재 매핑 환산값은 달라진다 — 원장(최초값 보존)과 갈리면 안 된다(03 V34).
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_130
        stubObserve { TxStateChange(record(amount = "1.5"), listOf(TxStatus.FINALIZED)) }
        val enqueued = slot<List<OutboxEvent>>()
        every { outboxEvents.enqueue(capture(enqueued)) } returns Unit

        // 등록 정밀도를 8로 바꾸면 같은 최소 단위 1500000이 0.015로 환산된다.
        decision(asset = LedgerAsset(NETWORK, "USDC", 8)).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(enqueued.captured.single().payload).contains("\"amount\":\"1.5\"").doesNotContain("0.015")
    }

    @Test
    fun `깊이가 임계에 못 미치면 벤더가 확인했다고 해도 미확정이다`() {
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_119
        stubObserve { stateChange(TxStatus.CONFIRMED) }

        decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(observed.captured.confirmationCount).isEqualTo(1)
        assertThat(observed.captured.status).isEqualTo(TxStatus.CONFIRMED)
    }

    @Test
    fun `앞 단계를 발행하지 않았으면 감지와 확정을 순서대로 같은 트랜잭션에 적재한다`() {
        // 02의 순서 계약 — 소비 쪽은 "감지 없는 확정"을 다루지 않는다. 원장이 합성한 순서를 그대로 옮긴다.
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_130
        stubObserve { TxStateChange(record(), listOf(TxStatus.CONFIRMED, TxStatus.FINALIZED)) }
        val enqueued = slot<List<OutboxEvent>>()
        every { outboxEvents.enqueue(capture(enqueued)) } returns Unit

        decision().decide(NOTIFICATION_ID, PAYLOAD)

        val events = enqueued.captured
        assertThat(events).hasSize(2)
        assertThat(events.map { it.eventType }).containsExactly(OutboxEventType.CHECKING, OutboxEventType.CONFIRMED)
        // 같은 거래의 서로 다른 사건이므로 evnt_id는 달라야 하고 거래 ID는 같아야 한다.
        assertThat(events.map { it.eventId }).doesNotHaveDuplicates()
        assertThat(events.map { it.vendorTransactionId }.distinct()).hasSize(1)
        assertThat(events.map { it.payload }).containsExactly(
            """{"amount":"1.5","status":"CONFIRMED"}""",
            """{"amount":"1.5","status":"FINALIZED"}""",
        )
    }

    @Test
    fun `원장이 발행할 상태가 없으면 이벤트도 없다`() {
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_130
        stubObserve { TxStateChange(record(), emptyList()) }

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat((outcome as DfnsChainDecisionOutcome.Processed).events).isEmpty()
        verify { outboxEvents.enqueue(emptyList()) }
    }

    @Test
    fun `발신 이동 사건은 그 hash의 발신 거래에 블록 좌표를 적용해 확정한다`() {
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        val existing = txRecord()
        every { txStates.findByNetworkAndTransactionHash(NETWORK, TX_HASH) } returns listOf(existing)
        every { submissions.findByExternalTransactionId("ext-1") } returns submissionRecord()
        every { chainHeads.headBlockNumber(NETWORK) } returns outgoing.blockNumber + 11
        stubObserve { stateChange(TxStatus.FINALIZED) }
        every { outboxEvents.enqueue(any()) } returns Unit

        val outcome = decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOfSatisfying(DfnsChainDecisionOutcome.OutgoingAdvanced::class.java) {
            assertThat(it.status).isEqualTo(TxStatus.FINALIZED)
            assertThat(it.recordCount).isEqualTo(1)
        }
        // 키는 기존 거래의 벤더 전송 ID다 — 파생 ID를 만들지 않는다.
        assertThat(observed.captured.vendorTransactionId).isEqualTo("xfr-1")
        assertThat(observed.captured.externalTransactionId).isEqualTo("ext-1")
        assertThat(observed.captured.confirmationCount).isEqualTo(12)
    }

    @Test
    fun `제출은 대조가 아니라 거래에 적힌 제출 키로 직접 찾는다`() {
        // 관찰값이 어느 제출과 닮았는지로 고르지 않는다 — 벤더는 이동과 제출을 잇는 키를 주지 않아 증명할 수 없다.
        // 귀속은 벤더가 전송 요청에 결속해 준 txHash가 이미 해결했고, 원장에는 그 거래의 제출 키가 적혀 있다.
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        every { txStates.findByNetworkAndTransactionHash(NETWORK, TX_HASH) } returns listOf(txRecord())
        every { submissions.findByExternalTransactionId("ext-1") } returns submissionRecord()
        every { chainHeads.headBlockNumber(NETWORK) } returns outgoing.blockNumber
        stubObserve { stateChange(TxStatus.CONFIRMED) }
        every { outboxEvents.enqueue(any()) } returns Unit

        decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD)

        verify(exactly = 1) { submissions.findByExternalTransactionId("ext-1") }
        verify(exactly = 0) { submissions.findByVendorTransactionId(any()) }
    }

    @Test
    fun `같은 hash에 우리 발신이 여럿이면 모두 같은 블록이므로 모두 적용한다`() {
        // 하나를 고르는 문제가 아니다 — 벤더가 여러 전송을 한 트랜잭션으로 냈어도 블록 좌표는 하나다.
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        every { txStates.findByNetworkAndTransactionHash(NETWORK, TX_HASH) } returns
            listOf(txRecord(), txRecord(vendorTxId = "xfr-2", externalTxId = "ext-2"))
        every { submissions.findByExternalTransactionId("ext-1") } returns submissionRecord()
        every { submissions.findByExternalTransactionId("ext-2") } returns
            submissionRecord(externalTransactionId = "ext-2", vendorTransactionId = "xfr-2")
        every { chainHeads.headBlockNumber(NETWORK) } returns outgoing.blockNumber + 11
        stubObserve { stateChange(TxStatus.FINALIZED) }
        every { outboxEvents.enqueue(any()) } returns Unit

        val outcome = decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat((outcome as DfnsChainDecisionOutcome.OutgoingAdvanced).recordCount).isEqualTo(2)
        // 후보마다 **제 관찰**이 실려야 한다 — 하나를 두 번 보내면 한 거래가 남의 관찰로 갱신된다.
        assertThat(batch.captured.map { it.rootVendorTransactionId }).containsExactly("xfr-1", "xfr-2")
        assertThat(batch.captured.map { it.observation.externalTransactionId }).containsExactly("ext-1", "ext-2")
    }

    @Test
    fun `입금 관찰이 이미 적힌 사실과 어긋나면 이벤트를 만들지 않고 충돌로 올린다`() {
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_130
        stubConflict()

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOfSatisfying(DfnsChainDecisionOutcome.ObservationConflict::class.java) {
            // 인박스에 남길 사유에는 어긋난 항목만 들어간다 — 금액은 남기지 않는다.
            assertThat(it.safeReason).contains("field=amount").doesNotContain("1.5")
        }
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    @Test
    fun `발신 후보 하나가 충돌하면 앞선 후보의 전이도 남기지 않는다`() {
        // 행마다 검사·쓰기를 붙여 돌면 뒤 행의 충돌로 격리할 때 앞 행의 전이가 같은 트랜잭션에 실려 함께 커밋된다.
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        every { txStates.findByNetworkAndTransactionHash(NETWORK, TX_HASH) } returns
            listOf(txRecord(), txRecord(vendorTxId = "xfr-2", externalTxId = "ext-2"))
        every { submissions.findByExternalTransactionId(any()) } returns submissionRecord()
        every { chainHeads.headBlockNumber(NETWORK) } returns outgoing.blockNumber + 11
        stubConflict()

        val outcome = decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOf(DfnsChainDecisionOutcome.ObservationConflict::class.java)
        // 한 번에 다뤘으므로 앞 후보의 전이도 남지 않는다 — 거래 경계가 그 순서를 지킨다.
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    @Test
    fun `그 hash의 발신 거래가 아직 없으면 재처리 가능한 상태로 남긴다`() {
        // 전송 알림이 늦게 올 수 있다 — 처리 완료로 닫으면 그 출금은 영영 확정되지 않는다.
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        every { txStates.findByNetworkAndTransactionHash(any(), any()) } returns emptyList()

        assertThat(decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.OutgoingPending(outgoing))

        // 같은 hash의 입금 행만 있는 경우도 마찬가지다 — 발신 사건이 입금 행의 상태를 옮기지 않는다.
        every { txStates.findByNetworkAndTransactionHash(any(), any()) } returns listOf(txRecord(externalTxId = null))

        assertThat(decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.OutgoingPending(outgoing))

        verifyNoObservation()
        verify(exactly = 0) { chainHeads.headBlockNumber(any()) }
    }

    @Test
    fun `우리 내부이체의 수신측은 입금을 만들지 않는다`() {
        // 같은 hash에 우리 발신 거래가 있으면 업무 이벤트는 제출 원장 쪽에서 이미 났다 — 또 만들면 없는 입금이 인정된다(계약13).
        val deposit = transfer()
        every { txStates.findByNetworkAndTransactionHash(NETWORK, TX_HASH) } returns listOf(txRecord())

        assertThat(decision(event = event(deposit)).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.InternalReceipt(deposit))

        verifyNoObservation()
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
        verify(exactly = 0) { chainHeads.headBlockNumber(any()) }
    }

    @Test
    fun `발신이 우리 지갑인데 결속이 없으면 입금으로 확정하지 않고 보류한다`() {
        // 전송 알림이 늦을 수 있다. 이벤트는 취소가 안 되므로 확정보다 보류가 맞다.
        val deposit = transfer()

        assertThat(decision(event = event(deposit), senderIsOurWallet = true).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.IncomingUnresolved(deposit))

        verifyNoObservation()
        verify(exactly = 0) { chainHeads.headBlockNumber(any()) }
    }

    @Test
    fun `입금 판단도 같은 직렬화 경계에 참여한다`() {
        // 입금도 같은 (network, tx_hash)로 거래 행을 만든다 — 한 경로라도 빠지면 발신 좌표의 후보 조회에 팬텀 삽입이 남는다.
        val deposit = transfer()
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_130
        stubObserve { stateChange(TxStatus.FINALIZED) }

        decision(event = event(deposit)).decide(NOTIFICATION_ID, PAYLOAD)

        verify(exactly = 1) { txStates.lockNetworkTransactionHash(NETWORK, TX_HASH) }
    }

    @Test
    fun `후보 조회 전에 network와 hash의 직렬화 경계를 잡는다`() {
        // 조회와 전이 사이에 같은 hash 행이 새로 삽입될 수 있다 — 거래를 만드는 쪽과 같은 경계를 공유해야 한다.
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        every { txStates.findByNetworkAndTransactionHash(any(), any()) } returns emptyList()

        decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD)

        verify(exactly = 1) { txStates.lockNetworkTransactionHash(NETWORK, TX_HASH) }
    }

    @Test
    fun `미지원·미등록 자산과 이벤트를 만들 수 없는 관찰은 원장도 이벤트도 쓰지 않는다`() {
        val unsupported = transfer(vendorAssetId = null, vendorAssetKind = "Erc721Transfer", amount = null)
        assertThat(decision(event = event(unsupported)).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.UnsupportedAsset(unsupported))

        val unmapped = transfer()
        assertThat(decision(event = event(unmapped), asset = null).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.UnmappedAsset(unmapped))

        val unattributed = transfer()
        assertThat(decision(event = event(unattributed), accountId = null).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(
                DfnsChainDecisionOutcome.Unattributed(unattributed, NetworkChainAttributionMiss.UNKNOWN_ADDRESS, NETWORK, "USDC"),
            )

        // 온체인 이동 사건이 아닌 알림은 이 판단의 대상이 아니다.
        assertThat(decision(event = null).decide(NOTIFICATION_ID, PAYLOAD)).isEqualTo(DfnsChainDecisionOutcome.NotChainEvent)

        verifyNoObservation()
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
        verify(exactly = 0) { chainHeads.headBlockNumber(any()) }
    }

    @Test
    fun `정밀도가 없는 등록 자산은 금액을 지어내지 않고 판단을 멈춘다`() {
        val observation = transfer()

        val outcome =
            decision(event = event(observation), asset = LedgerAsset(NETWORK, "USDC", null)).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isEqualTo(DfnsChainDecisionOutcome.MissingDecimals(observation))
        verifyNoObservation()
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    @Test
    fun `발신 주소가 없으면 입금 이벤트를 만들지 않는다`() {
        // 02는 입금 이벤트에 발신 주소가 항상 실린다고 확정했다 — 명세상 선택 필드라고 비운 채 내보내지 않는다.
        val observation = transfer(from = null)

        val outcome = decision(event = event(observation)).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isEqualTo(DfnsChainDecisionOutcome.MissingSender(observation))
        verifyNoObservation()
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
        verify(exactly = 0) { chainHeads.headBlockNumber(any()) }
    }

    @Test
    fun `순번 없는 사건은 외부 호출 전에 거래 ID를 지어내지 않고 실패한다`() {
        assertThatThrownBy { decision(event = event(transfer(eventIndex = null))).decide(NOTIFICATION_ID, PAYLOAD) }
            .isInstanceOf(WebhookPayloadException::class.java)
            .hasMessageContaining("index")
        // 결정적 payload 오류는 RPC 장애에 가려지지 않는다 — 체인 head를 읽기 전에 드러난다.
        verify(exactly = 0) { chainHeads.headBlockNumber(any()) }
        verifyNoObservation()
    }

    @Test
    fun `체인 head를 읽지 못하면 확정을 보류하도록 예외가 그대로 오른다`() {
        every { chainHeads.headBlockNumber(NETWORK) } throws IllegalStateException("EVM RPC is not configured")

        assertThatThrownBy { decision().decide(NOTIFICATION_ID, PAYLOAD) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not configured")
        verifyNoObservation()
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    private fun decision(
        event: NetworkChainEvent? = event(transfer()),
        asset: LedgerAsset? = LedgerAsset(NETWORK, "USDC", 6),
        accountId: String? = ACCOUNT_ID,
        senderIsOurWallet: Boolean = false,
    ) = DfnsChainEventDecision(
        parser = NetworkChainEventParser { event },
        ledger =
            object : NetworkChainLedgerLookup {
                override fun assetOf(vendorAssetId: String) = asset

                override fun accountOfDepositAddress(
                    address: String,
                    network: String,
                    symbol: String,
                ) = accountId

                override fun ownsWalletAddress(
                    network: String,
                    address: String,
                ) = senderIsOurWallet
            },
        submissions = submissions,
        chainHeads = chainHeads,
        statusTranslator = translator,
        txStates = txStates,
        outboxEvents = outboxEvents,
        eventIdGenerator = EventIdGenerator { "evt-${eventSequence++}" },
        eventSerializer = ChainEventSerializer { """{"amount":"${it.amount}","status":"${it.status}"}""" },
        clock = Clock.fixed(Instant.parse("2026-09-16T01:02:03Z"), ZoneOffset.UTC),
        outboxMaxAttempts = 5,
    )

    /** 상태 번역은 실제 Dfns 구현과 같은 규칙을 쓴다 — 깊이가 임계 이상일 때만 확정이다. */
    private val translator =
        object : VendorStatusTranslator {
            override fun translate(
                observation: VendorStatusObservation,
                network: String,
            ): TxStatus = if (observation.confirmationCount >= 12) TxStatus.FINALIZED else TxStatus.CONFIRMED

            override fun terminalStatusForReconciliation(
                observation: VendorStatusObservation,
                sourceType: String,
            ): TxStatus? = null
        }

    private fun event(observation: NetworkChainTransfer) =
        NetworkChainEvent(
            delivery = VendorWebhookDelivery(NOTIFICATION_ID, "2026-09-16T00:00:05.000Z", 1, null),
            kind = NetworkChainEventKind.DETECTED,
            observation = observation,
        )

    private fun transfer(
        direction: NetworkChainDirection = NetworkChainDirection.IN,
        vendorAssetId: String? = ASSET_KEY,
        vendorAssetKind: String = "Erc20Transfer",
        amount: String? = "1500000",
        eventIndex: String? = "3",
        from: String? = SENDER,
    ) = NetworkChainTransfer(
        network = NETWORK,
        vendorWalletId = WALLET_ID,
        vendorWalletAddress = DESTINATION,
        vendorAssetId = vendorAssetId,
        vendorAssetKind = vendorAssetKind,
        direction = direction,
        status = NetworkChainTransferStatus.CONFIRMED,
        amountBaseUnits = amount,
        fromAddress = from,
        toAddress = DESTINATION,
        transactionHash = TX_HASH,
        blockNumber = 8_452_119,
        eventIndex = eventIndex,
        observedAt = "1758067200",
    )

    private fun stateChange(status: TxStatus) = TxStateChange(record(), listOf(status))

    /** 원장은 최초 관찰의 금액을 지킨다 — 입금 이벤트는 이 값을 싣는다(03 V34). */
    private fun record(amount: String? = "1.5") =
        TxRecord(
            vendorTxId = "dfns-x",
            activeVendorTxId = "dfns-x",
            externalTxId = null,
            accountId = ACCOUNT_ID,
            network = NETWORK,
            symbol = "USDC",
            transactionHash = TX_HASH,
            lastPublishedStatus = TxStatus.CONFIRMED,
            confirmationCount = 12,
            vendorCreatedAt = "20260916000005",
            firstDetectedAt = "20260916000005",
            lastChangedAt = "20260916000005",
            amount = amount,
        )

    private fun txRecord(
        vendorTxId: String = "xfr-1",
        externalTxId: String? = "ext-1",
    ) = com.whatto.bcm.domain.tx.TxRecord(
        vendorTxId = vendorTxId,
        externalTxId = externalTxId,
        accountId = "acct-1",
        network = NETWORK,
        symbol = "USDC",
        transactionHash = TX_HASH,
        lastPublishedStatus = TxStatus.SUBMITTED,
        confirmationCount = 0,
        firstDetectedAt = "20260916010203",
        lastChangedAt = "20260916010203",
    )

    private fun submissionRecord(
        amountBaseUnits: String = AMOUNT_BASE_UNITS,
        externalTransactionId: String = "ext-1",
        vendorTransactionId: String = "xfr-1",
    ) = com.whatto.bcm.domain.submission.SubmissionRecord(
        externalTransactionId = externalTransactionId,
        requestHash = "0".repeat(64),
        hashVersion = "v1",
        status = com.whatto.bcm.domain.submission.SubmissionStatus.SUBMITTED,
        claimId = null,
        claimExpiresAt = null,
        transactionType = com.whatto.bcm.domain.submission.SubmissionTransactionType.WITHDRAWAL,
        vendorTransactionId = vendorTransactionId,
        senderAccountId = "acct-1",
        recipientType = com.whatto.bcm.domain.submission.SubmissionRecipientType.ADDRESS,
        recipientValue = DESTINATION,
        network = NETWORK,
        symbol = "USDC",
        amount = "1",
        requestedAt = "20260916010203",
        respondedAt = null,
        vendorCanonical =
            com.whatto.bcm.domain.submission.SubmissionVendorCanonical(
                vendorWalletId = WALLET_ID,
                vendorAssetId = ASSET_KEY,
                amountBaseUnits = amountBaseUnits,
                decimals = 6,
                destinationAddress = DESTINATION,
            ),
    )

    private companion object {
        const val NOTIFICATION_ID = "whe-544ul-uqgad-jkgltj5p6fvd04cj"
        const val NETWORK = "ETHEREUM_SEPOLIA"
        const val ACCOUNT_ID = "acct_dfns_1"
        const val DESTINATION = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47"
        const val SENDER = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
        const val WALLET_ID = "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx"

        /** 관찰과 제출 canonical 이 같은 자산 키를 가리켜야 대조를 통과한다. */
        const val ASSET_KEY = "EthereumSepolia:Erc20:0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"

        /** 관찰의 기본 금액 — 제출 원장 canonical과 같아야 대조를 통과한다. */
        const val AMOUNT_BASE_UNITS = "1500000"

        const val TX_HASH = "0x2a6f0c9b6bd0a7b9f4e3e0b33d2ff4c7cf9a7a0f9b4d2a8f8c1b3e5d7a9c0b11"
        val PAYLOAD = "{}".toByteArray()
    }
}
