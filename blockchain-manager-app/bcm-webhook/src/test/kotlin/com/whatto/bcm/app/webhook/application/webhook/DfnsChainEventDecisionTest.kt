package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.tx.ChainHeadPort
import com.whatto.bcm.domain.tx.TxObservation
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

    private val txStates = mockk<TxStateService>()
    private val outboxEvents = mockk<OutboxEventService>(relaxed = true)
    private val chainHeads = mockk<ChainHeadPort>()
    private var eventSequence = 1

    @Test
    fun `발급 주소 입금은 블록 깊이로 확정을 판정하고 등록 정밀도로 금액을 만든다`() {
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_130
        val observed = slot<com.whatto.bcm.domain.tx.TxObservation>()
        every { txStates.observe(capture(observed)) } answers { stateChange(TxStatus.FINALIZED) }
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
    fun `깊이가 임계에 못 미치면 벤더가 확인했다고 해도 미확정이다`() {
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_119
        val observed = slot<com.whatto.bcm.domain.tx.TxObservation>()
        every { txStates.observe(capture(observed)) } answers { stateChange(TxStatus.CONFIRMED) }

        decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(observed.captured.confirmationCount).isEqualTo(1)
        assertThat(observed.captured.status).isEqualTo(TxStatus.CONFIRMED)
    }

    @Test
    fun `앞 단계를 발행하지 않았으면 감지와 확정을 순서대로 같은 트랜잭션에 적재한다`() {
        // 02의 순서 계약 — 소비 쪽은 "감지 없는 확정"을 다루지 않는다. 원장이 합성한 순서를 그대로 옮긴다.
        every { chainHeads.headBlockNumber(NETWORK) } returns 8_452_130
        every { txStates.observe(any()) } returns
            TxStateChange(record(), listOf(TxStatus.CONFIRMED, TxStatus.FINALIZED))
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
        every { txStates.observe(any()) } returns TxStateChange(record(), emptyList())

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat((outcome as DfnsChainDecisionOutcome.Processed).events).isEmpty()
        verify { outboxEvents.enqueue(emptyList()) }
    }

    @Test
    fun `발신 이동은 새 거래를 만들지 않고 기존 거래에 붙여 블록 깊이로 확정한다`() {
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        val existing = txRecord()
        every { txStates.findByNetworkAndTransactionHash(NETWORK, TX_HASH) } returns listOf(existing)
        every { submissions.findByVendorTransactionId("xfr-1") } returns submissionRecord()
        every { chainHeads.headBlockNumber(NETWORK) } returns outgoing.blockNumber + 11
        val observed = slot<TxObservation>()
        every { txStates.observe(capture(observed)) } answers { stateChange(TxStatus.FINALIZED) }
        every { outboxEvents.enqueue(any()) } returns Unit

        val outcome = decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOfSatisfying(DfnsChainDecisionOutcome.OutgoingAttached::class.java) {
            assertThat(it.status).isEqualTo(TxStatus.FINALIZED)
        }
        // 키는 기존 거래의 벤더 전송 ID다 — 파생 ID를 만들지 않는다.
        assertThat(observed.captured.vendorTransactionId).isEqualTo("xfr-1")
        assertThat(observed.captured.externalTransactionId).isEqualTo("ext-1")
        assertThat(observed.captured.confirmationCount).isEqualTo(12)
    }

    @Test
    fun `붙일 거래가 없거나 제출 원장 대응이 없으면 원장을 쓰지 않는다`() {
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        every { txStates.findByNetworkAndTransactionHash(any(), any()) } returns emptyList()

        assertThat(decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.OutgoingUnmatched(outgoing))

        // 대응 없는 경우도 같다 — hash 일치만으로 확정을 붙이지 않는다.
        every { txStates.findByNetworkAndTransactionHash(any(), any()) } returns listOf(txRecord())
        every { submissions.findByVendorTransactionId(any()) } returns null

        assertThat(decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.OutgoingUnmatched(outgoing))

        verify(exactly = 0) { txStates.observe(any()) }
        verify(exactly = 0) { chainHeads.headBlockNumber(any()) }
    }

    @Test
    fun `같은 hash에 거래가 여럿이면 하나를 고르지 않고 중단한다`() {
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        every { txStates.findByNetworkAndTransactionHash(any(), any()) } returns
            listOf(txRecord(), txRecord(vendorTxId = "xfr-2"))

        assertThat(decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.OutgoingAmbiguous(outgoing, 2))

        verify(exactly = 0) { txStates.observe(any()) }
    }

    @Test
    fun `입금이 아닌 결과는 원장도 이벤트도 쓰지 않는다`() {
        val outgoing = transfer(direction = NetworkChainDirection.OUT)
        every { txStates.findByNetworkAndTransactionHash(any(), any()) } returns emptyList()
        assertThat(decision(event = event(outgoing)).decide(NOTIFICATION_ID, PAYLOAD))
            .isEqualTo(DfnsChainDecisionOutcome.OutgoingUnmatched(outgoing))

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

        verify(exactly = 0) { txStates.observe(any()) }
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
        verify(exactly = 0) { chainHeads.headBlockNumber(any()) }
    }

    @Test
    fun `정밀도가 없는 등록 자산은 금액을 지어내지 않고 판단을 멈춘다`() {
        val observation = transfer()

        val outcome =
            decision(event = event(observation), asset = LedgerAsset(NETWORK, "USDC", null)).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isEqualTo(DfnsChainDecisionOutcome.MissingDecimals(observation))
        verify(exactly = 0) { txStates.observe(any()) }
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    @Test
    fun `발신 주소가 없으면 입금 이벤트를 만들지 않는다`() {
        // 02는 입금 이벤트에 발신 주소가 항상 실린다고 확정했다 — 명세상 선택 필드라고 비운 채 내보내지 않는다.
        val observation = transfer(from = null)

        val outcome = decision(event = event(observation)).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isEqualTo(DfnsChainDecisionOutcome.MissingSender(observation))
        verify(exactly = 0) { txStates.observe(any()) }
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
        verify(exactly = 0) { txStates.observe(any()) }
    }

    @Test
    fun `체인 head를 읽지 못하면 확정을 보류하도록 예외가 그대로 오른다`() {
        every { chainHeads.headBlockNumber(NETWORK) } throws IllegalStateException("EVM RPC is not configured")

        assertThatThrownBy { decision().decide(NOTIFICATION_ID, PAYLOAD) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not configured")
        verify(exactly = 0) { txStates.observe(any()) }
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    private fun decision(
        event: NetworkChainEvent? = event(transfer()),
        asset: LedgerAsset? = LedgerAsset(NETWORK, "USDC", 6),
        accountId: String? = ACCOUNT_ID,
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
        vendorAssetId: String? = "EthereumSepolia:Erc20:0x1c7d4b196cb0c7b01d743fbc6116a902379c7238",
        vendorAssetKind: String = "Erc20Transfer",
        amount: String? = "1500000",
        eventIndex: String? = "3",
        from: String? = SENDER,
    ) = NetworkChainTransfer(
        network = NETWORK,
        vendorWalletId = "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx",
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

    private fun record() =
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
        )

    private fun txRecord(vendorTxId: String = "xfr-1") =
        com.whatto.bcm.domain.tx.TxRecord(
            vendorTxId = vendorTxId,
            accountId = "acct-1",
            network = NETWORK,
            symbol = "USDC",
            transactionHash = TX_HASH,
            lastPublishedStatus = TxStatus.SUBMITTED,
            confirmationCount = 0,
            firstDetectedAt = "20260916010203",
            lastChangedAt = "20260916010203",
        )

    private fun submissionRecord() =
        com.whatto.bcm.domain.submission.SubmissionRecord(
            externalTransactionId = "ext-1",
            requestHash = "0".repeat(64),
            hashVersion = "v1",
            status = com.whatto.bcm.domain.submission.SubmissionStatus.SUBMITTED,
            claimId = null,
            claimExpiresAt = null,
            transactionType = com.whatto.bcm.domain.submission.SubmissionTransactionType.WITHDRAWAL,
            vendorTransactionId = "xfr-1",
            senderAccountId = "acct-1",
            recipientType = com.whatto.bcm.domain.submission.SubmissionRecipientType.ADDRESS,
            recipientValue = "0x1111111111111111111111111111111111111111",
            network = NETWORK,
            symbol = "USDC",
            amount = "1",
            requestedAt = "20260916010203",
            respondedAt = null,
        )

    private companion object {
        const val NOTIFICATION_ID = "whe-544ul-uqgad-jkgltj5p6fvd04cj"
        const val NETWORK = "ETHEREUM_SEPOLIA"
        const val ACCOUNT_ID = "acct_dfns_1"
        const val DESTINATION = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47"
        const val SENDER = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
        const val TX_HASH = "0x2a6f0c9b6bd0a7b9f4e3e0b33d2ff4c7cf9a7a0f9b4d2a8f8c1b3e5d7a9c0b11"
        val PAYLOAD = "{}".toByteArray()
    }
}
