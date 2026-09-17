package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.submission.SubmissionObservationService
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
import com.whatto.bcm.domain.tx.TxObservation
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
    private val txStates = mockk<TxStateService>()
    private val outboxEvents = mockk<OutboxEventService>(relaxed = true)

    @Test
    fun `제출 원장에 있는 전송은 원장 전이와 출금 이벤트를 만든다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns record()
        val observed = slot<TxObservation>()
        every { txStates.observe(capture(observed)) } returns stateChange(TxStatus.SUBMITTED)
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
        every { txStates.observe(any()) } returns stateChange(TxStatus.SUBMITTED)
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
        val observed = slot<TxObservation>()
        every { txStates.observe(capture(observed)) } returns stateChange(TxStatus.CONFIRMED)

        decision(statusTranslator = translator).decide(NOTIFICATION_ID, PAYLOAD)

        // 컨펌 수 0으로 번역기에 넘겨야 벤더의 Confirmed 표기가 확정으로 새지 않는다(CLAUDE.md 3절).
        assertThat(translated.captured.confirmationCount).isEqualTo(0)
        assertThat(observed.captured.confirmationCount).isEqualTo(0)
    }

    @Test
    fun `응답보다 웹훅이 먼저 오면 비어 있던 벤더 전송 ID를 채운다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns null
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns record(vendorTransactionId = null)
        every { txStates.observe(any()) } returns stateChange(TxStatus.SUBMITTED)

        decision().decide(NOTIFICATION_ID, PAYLOAD)

        verify(exactly = 1) { submissions.markSubmitted(EXTERNAL_ID, TRANSFER_ID, any()) }
    }

    @Test
    fun `이미 연결된 전송은 원장을 다시 잇지 않는다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns record()
        every { txStates.observe(any()) } returns stateChange(TxStatus.SUBMITTED)

        decision().decide(NOTIFICATION_ID, PAYLOAD)

        verify(exactly = 0) { submissions.markSubmitted(any(), any(), any()) }
    }

    @Test
    fun `제출 원장에 없는 전송은 원장도 이벤트도 만들지 않는다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns null
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns null

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOf(DfnsTransferDecisionOutcome.UnknownSubmission::class.java)
        verify(exactly = 0) { txStates.observe(any()) }
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
        verify(exactly = 0) { txStates.observe(any()) }
        verify(exactly = 0) { submissions.markSubmitted(any(), any(), any()) }
    }

    @Test
    fun `고객 이벤트가 없는 계열은 원장만 잇고 이벤트를 만들지 않는다`() {
        every { submissions.findByVendorTransactionId(TRANSFER_ID) } returns
            record(transactionType = SubmissionTransactionType.SWEEP_BATCH)
        every { txStates.observe(any()) } returns stateChange(TxStatus.SUBMITTED)

        val outcome = decision().decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isInstanceOfSatisfying(DfnsTransferDecisionOutcome.Processed::class.java) {
            assertThat(it.events).isEmpty()
        }
        verify(exactly = 1) { txStates.observe(any()) }
        verify(exactly = 0) { outboxEvents.enqueue(any()) }
    }

    @Test
    fun `전송 알림이 아니면 다음 판단 경로에 넘긴다`() {
        val outcome = decision(parser = NetworkTransferEventParser { null }).decide(NOTIFICATION_ID, PAYLOAD)

        assertThat(outcome).isEqualTo(DfnsTransferDecisionOutcome.NotTransferEvent)
        verify(exactly = 0) { txStates.observe(any()) }
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
                    destinationAddress = ADDRESS,
                    amountBaseUnits = "1000000",
                    status = NetworkTransferStatus.BROADCASTED,
                    externalId = EXTERNAL_ID,
                    transactionHash = "0x" + "a".repeat(64),
                    requestedAt = "2026-09-17T09:00:00Z",
                    failureReason = null,
                ),
        )

    private fun record(
        vendorTransactionId: String? = TRANSFER_ID,
        transactionType: SubmissionTransactionType = SubmissionTransactionType.WITHDRAWAL,
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
        recipientType = SubmissionRecipientType.ADDRESS,
        recipientValue = ADDRESS,
        network = "ETHEREUM_SEPOLIA",
        symbol = "USDC",
        amount = "1",
        requestedAt = "20260917090000",
        respondedAt = null,
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
                    firstDetectedAt = "20260917090000",
                    lastChangedAt = "20260917090000",
                ),
            statusesToPublish = listOf(status),
        )

    private companion object {
        const val NOTIFICATION_ID = "wh-1"
        const val TRANSFER_ID = "xfr-1"
        const val EXTERNAL_ID = "ext-1"
        const val ADDRESS = "0x1111111111111111111111111111111111111111"
        val PAYLOAD = "{}".toByteArray()
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC)
    }
}
