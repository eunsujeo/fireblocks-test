package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.tx.TxObservationOutcome
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.event.ChainEvent
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.event.EventType
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxObservationConsistency
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStateChange
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.NetworkTransferEvent
import com.whatto.bcm.domain.vendor.NetworkTransferEventKind
import com.whatto.bcm.domain.vendor.NetworkTransferEventParser
import com.whatto.bcm.domain.vendor.NetworkTransferObservation
import com.whatto.bcm.domain.vendor.NetworkTransferStatus
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.webhook.VendorWebhookDelivery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Dfns 전송 알림 판단(계약13) — 우리가 낸 발신 거래의 원장 전이와 outbox.
 * 계열은 제출 원장으로만 가르고, 확정은 여기서 내지 않는다(전송 알림에는 blockNumber가 없다).
 */
class DfnsTransferEventDecisionTest {
    private val submissions = mockk<SubmissionObservationService>(relaxed = true)
    private val txStates =
        mockk<TxStateService> {
            every { lockNetworkTransactionHash(any(), any()) } returns Unit
        }
    private val outboxEvents = mockk<OutboxEventService>(relaxed = true)

    /** 마지막으로 원장에 넘긴 관찰. */
    private val observed = slot<TxObservation>()

    /** 동일성 검사를 통과시킨다 — 이 경로는 거래 행 하나만 다루므로 입구도 하나다(03 V32). */
    private fun stubObserve(change: () -> TxStateChange) {
        every { txStates.observeConsistently(any(), capture(observed), any(), any(), any()) } answers {
            TxObservationOutcome.Applied(change())
        }
    }

    private fun verifyNoObservation() {
        verify(exactly = 0) { txStates.observeConsistently(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `제출 원장에 있는 전송은 원장 전이와 출금 이벤트를 만든다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns record()
        stubObserve { stateChange(TxStatus.SUBMITTED) }
        val enqueued = slot<List<OutboxEvent>>()
        every { outboxEvents.enqueue(capture(enqueued)) } returns Unit

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOf(DfnsTransferDecisionOutcome.Processed::class.java)
        // 계정·네트워크·심볼은 알림이 아니라 원장에 적힌 우리 요청에서 읽는다.
        assertThat(observed.captured.accountId).isEqualTo("acct-1")
        assertThat(observed.captured.network).isEqualTo("ETHEREUM_SEPOLIA")
        assertThat(observed.captured.externalTransactionId).isEqualTo(EXTERNAL_ID)
        assertThat(observed.captured.vendorTransactionId).isEqualTo(TRANSFER_ID)
        assertThat(enqueued.captured).hasSize(1)
        assertThat(enqueued.captured.single().topic).isEqualTo(EventType.WITHDRAWAL.topic)
    }

    @Test
    fun `이벤트의 심볼·금액·목적지는 알림이 아니라 제출 원장 값이다`() {
        // 알림은 벤더가 보낸 관찰이고 업무 귀속의 근거는 우리가 승인·기록한 요청이다.
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns record()
        stubObserve { stateChange(TxStatus.SUBMITTED) }
        every { outboxEvents.enqueue(any()) } returns Unit
        val serialized = mutableListOf<ChainEvent>()

        decision(
            serializer =
                ChainEventSerializer {
                    serialized += it
                    "{}"
                },
        ).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(serialized).singleElement().satisfies({ event ->
            assertThat(event.symbol).isEqualTo("USDC")
            assertThat(event.amount).isEqualTo("1")
            assertThat(event.to).isEqualTo(ADDRESS)
            assertThat(event.accountId).isEqualTo("acct-1")
            assertThat(event.network).isEqualTo("ETHEREUM_SEPOLIA")
            assertThat(event.externalTxId).isEqualTo(EXTERNAL_ID)
            assertThat(event.txId).isEqualTo(TRANSFER_ID)
            assertThat(event.numOfConfirmations).isEqualTo(0)
        })
    }

    @Test
    fun `전송 알림으로는 확정을 내지 않는다 — 블록 깊이를 알 수 없다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns record()
        val translated = slot<VendorStatusObservation>()
        val translator =
            object : VendorStatusTranslator {
                override fun translate(
                    observation: VendorStatusObservation,
                    network: String,
                ): TxStatus {
                    translated.captured = observation
                    return TxStatus.CONFIRMED
                }

                override fun terminalStatusForReconciliation(
                    observation: VendorStatusObservation,
                    sourceType: String,
                ): TxStatus? = null
            }
        stubObserve { stateChange(TxStatus.CONFIRMED) }

        decision(statusTranslator = translator).decide(NOTIFICATION_ID, PAYLOAD)

        // 컨펌 수 0으로 번역기에 넘겨야 벤더의 Confirmed 표기가 확정으로 새지 않는다(CLAUDE.md 3절).
        assertThat(translated.captured.confirmationCount).isEqualTo(0)
        assertThat(observed.captured.confirmationCount).isEqualTo(0)
    }

    @Test
    fun `응답보다 웹훅이 먼저 오면 비어 있던 벤더 전송 ID를 채운다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns null
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns record(vendorTransactionId = null)
        stubObserve { stateChange(TxStatus.SUBMITTED) }

        decision().decide(NOTIFICATION_ID, PAYLOAD)

        verify(exactly = 1) { submissions.markSubmitted(EXTERNAL_ID, TRANSFER_ID, any()) }
    }

    @Test
    fun `관찰이 이미 적힌 사실과 어긋나면 벤더 전송 ID 결속도 하지 않는다`() {
        // 워커의 즉시 격리는 같은 트랜잭션에서 일어난다 — 앞서 결속을 쓰면 격리와 함께 커밋된다.
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns null
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns record(vendorTransactionId = null)
        val conflict = TxObservationConsistency.Result.Conflict("amount", "1.5", "2.5")
        every { txStates.observeConsistently(any(), any(), any(), any(), any()) } returns TxObservationOutcome.Conflict(conflict)

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOfSatisfying(DfnsTransferDecisionOutcome.ObservationConflict::class.java) {
            assertThat(it.safeReason).contains("field=amount").doesNotContain("1.5")
        }
        verify(exactly = 0) { submissions.markSubmitted(any(), any(), any()) }
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    @Test
    fun `이미 연결된 전송은 원장을 다시 잇지 않는다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns record()
        stubObserve { stateChange(TxStatus.SUBMITTED) }

        decision().decide(NOTIFICATION_ID, PAYLOAD)

        verify(exactly = 0) { submissions.markSubmitted(any(), any(), any()) }
    }

    @Test
    fun `제출 원장에 없는 전송은 원장도 이벤트도 만들지 않는다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns null
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns null

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOf(DfnsTransferDecisionOutcome.UnknownSubmission::class.java)
        verifyNoObservation()
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    @Test
    fun `한 제출 키에 다른 전송 ID가 붙어 있으면 원장을 건드리지 않고 충돌로 올린다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns null
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns record(vendorTransactionId = "xfr-other")

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOfSatisfying(DfnsTransferDecisionOutcome.Conflicting::class.java) {
            assertThat(it.externalTransactionId).isEqualTo(EXTERNAL_ID)
        }
        verifyNoObservation()
        verify(exactly = 0) { submissions.markSubmitted(any(), any(), any()) }
    }

    @Test
    fun `고객 이벤트가 없는 계열은 원장만 잇고 이벤트를 만들지 않는다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns
            record(transactionType = SubmissionTransactionType.SWEEP_BATCH)
        stubObserve { stateChange(TxStatus.SUBMITTED) }

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOfSatisfying(DfnsTransferDecisionOutcome.Processed::class.java) {
            assertThat(it.events).isEmpty()
        }
        verify(exactly = 1) { txStates.observeConsistently(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    @Test
    fun `거래를 만들기 전에 network와 hash의 직렬화 경계를 잡는다`() {
        // 발신 붙임의 후보 조회와 직렬화되어야 팬텀 삽입이 생기지 않는다.
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns record()
        stubObserve { stateChange(TxStatus.SUBMITTED) }

        decision().decide(NOTIFICATION_ID, PAYLOAD)

        verify(exactly = 1) { txStates.lockNetworkTransactionHash("ETHEREUM_SEPOLIA", "0x" + "a".repeat(64)) }
    }

    @Test
    fun `전송 알림이 아니면 다음 판단 경로에 넘긴다`() {
        val outcome = decision(parser = NetworkTransferEventParser { null }).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isEqualTo(DfnsTransferDecisionOutcome.NotTransferEvent)
        verifyNoObservation()
    }

    private fun decision(
        parser: NetworkTransferEventParser = NetworkTransferEventParser { event() },
        statusTranslator: VendorStatusTranslator = translator(TxStatus.SUBMITTED),
        serializer: ChainEventSerializer = ChainEventSerializer { "{}" },
    ) = DfnsTransferEventDecision(
        parser,
        submissions,
        statusTranslator,
        txStates,
        outboxEvents,
        EventIdGenerator { "evt-1" },
        serializer,
        CLOCK,
        outboxMaxAttempts = 5,
    )

    private fun translator(status: TxStatus) =
        object : VendorStatusTranslator {
            override fun translate(
                observation: VendorStatusObservation,
                network: String,
            ): TxStatus = status

            override fun terminalStatusForReconciliation(
                observation: VendorStatusObservation,
                sourceType: String,
            ): TxStatus? = null
        }

    private fun event() =
        NetworkTransferEvent(
            delivery = VendorWebhookDelivery(NOTIFICATION_ID, "2026-09-17T09:00:00Z", 1, null),
            kind = NetworkTransferEventKind.BROADCASTED,
            observation =
                NetworkTransferObservation(
                    transferId = TRANSFER_ID,
                    network = "EthereumSepolia",
                    vendorWalletId = "wa-1",
                    vendorAssetId = "EthereumSepolia:Native",
                    // 원장 목적지와 **다른** 값이다 — 구현이 알림 목적지를 쓰도록 회귀하면 테스트가 깨져야 한다.
                    destinationAddress = NOTIFIED_ADDRESS,
                    amountBaseUnits = "9000000",
                    status = NetworkTransferStatus.BROADCASTED,
                    externalId = EXTERNAL_ID,
                    transactionHash = "0x" + "a".repeat(64),
                    requestedAt = "2026-09-17T09:00:00Z",
                    failureReason = null,
                ),
        )

    @Test
    fun `내부이체 이벤트의 to 는 계정이 아니라 해소한 온체인 주소다`() {
        // 02·공개 계약에서 to 는 온체인 목적지 주소다. 논리 목적지(recipientValue)는 내부이체에서 accountId라 그대로 실으면 안 된다(03 V30).
        val internal =
            record(
                transactionType = SubmissionTransactionType.INTERNAL,
                recipientType = SubmissionRecipientType.ACCOUNT,
                recipientValue = "acct-receiver",
                vendorCanonical =
                    SubmissionVendorCanonical(
                        vendorWalletId = "wa-1",
                        vendorAssetId = "EthereumSepolia:Native",
                        amountBaseUnits = "1000000",
                        decimals = 6,
                        destinationAddress = ADDRESS,
                    ),
            )
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns internal
        stubObserve { stateChange(TxStatus.CONFIRMED) }
        every { outboxEvents.enqueue(any()) } returns Unit

        val serialized = mutableListOf<ChainEvent>()

        decision(
            serializer =
                ChainEventSerializer {
                    serialized += it
                    "{}"
                },
        ).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(serialized).singleElement().satisfies({ event -> assertThat(event.to).isEqualTo(ADDRESS) })
    }

    private fun record(
        vendorTransactionId: String? = TRANSFER_ID,
        transactionType: SubmissionTransactionType = SubmissionTransactionType.WITHDRAWAL,
        recipientType: SubmissionRecipientType = SubmissionRecipientType.ADDRESS,
        recipientValue: String = ADDRESS,
        vendorCanonical: SubmissionVendorCanonical? = null,
    ) = SubmissionRecord(
        externalTransactionId = EXTERNAL_ID,
        requestHash = "0".repeat(64),
        hashVersion = "v1",
        status = SubmissionStatus.SUBMITTED,
        claimId = null,
        claimExpiresAt = null,
        transactionType = transactionType,
        vendorTransactionId = vendorTransactionId,
        senderAccountId = "acct-1",
        recipientType = recipientType,
        recipientValue = recipientValue,
        network = "ETHEREUM_SEPOLIA",
        symbol = "USDC",
        amount = "1",
        requestedAt = "20260917090000",
        respondedAt = null,
        vendorCanonical = vendorCanonical,
    )

    private fun stateChange(status: TxStatus) =
        TxStateChange(
            record =
                TxRecord(
                    vendorTxId = TRANSFER_ID,
                    accountId = "acct-1",
                    network = "ETHEREUM_SEPOLIA",
                    symbol = "USDC",
                    lastPublishedStatus = status,
                    confirmationCount = 0,
                    // 관찰을 이미 통과한 행이다 — 첫 관찰 전 행만 vendorCreatedAt 을 비운다(03 V32).
                    vendorCreatedAt = "20260917090000",
                    firstDetectedAt = "20260917090000",
                    lastChangedAt = "20260917090000",
                ),
            statusesToPublish = listOf(status),
        )

    private companion object {
        const val NOTIFICATION_ID = "wh-1"
        const val TRANSFER_ID = "xfr-1"
        const val EXTERNAL_ID = "ext-1"

        /** 제출 원장에 적힌 우리 요청의 목적지 — 이벤트는 이 값이어야 한다. */
        const val ADDRESS = "0x1111111111111111111111111111111111111111"

        /** 알림이 알려준 목적지 — 업무 귀속의 근거가 아니다. */
        const val NOTIFIED_ADDRESS = "0x2222222222222222222222222222222222222222"
        val PAYLOAD = "{}".toByteArray()
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC)
    }
}
