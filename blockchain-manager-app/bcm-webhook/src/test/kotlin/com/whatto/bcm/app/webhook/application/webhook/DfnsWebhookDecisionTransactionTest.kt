package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.tx.FinalityPolicyConfigurationException
import com.whatto.bcm.domain.vendor.NetworkChainAttributionMiss
import com.whatto.bcm.domain.vendor.NetworkChainDirection
import com.whatto.bcm.domain.vendor.NetworkChainTransfer
import com.whatto.bcm.domain.vendor.NetworkChainTransferStatus
import com.whatto.bcm.domain.webhook.WebhookFailureResult
import com.whatto.bcm.domain.webhook.WebhookInboxItem
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
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
 * Dfns 인박스 판단 — 결과를 인박스 상태로 옮기는 경계(계약13).
 * 이 클래스의 시나리오는 모두 온체인 이동 사건이며, 전송 알림 판단은 [DfnsTransferEventDecisionTest]가 다룬다.
 */
class DfnsWebhookDecisionTransactionTest {
    private val inbox = mockk<WebhookInboxRepository>(relaxed = true)
    private val decision = mockk<DfnsChainEventDecision>()
    private val transferDecision =
        mockk<DfnsTransferEventDecision> {
            // 기존 시나리오는 전부 온체인 이동 사건이다 — 전송 알림 판단은 그 앞에서 비켜선다.
            every { decide(any(), any()) } returns DfnsTransferDecisionOutcome.NotTransferEvent
        }

    @Test
    fun `붙일 수 없는 발신은 즉시 격리하고 아직 못 붙이는 발신은 재시도로 남긴다`() {
        // 대응 없음·불일치는 시간이 지나도 해소되지 않는다. 반대로 후보 없음은 전송 알림이 늦은 것일 수 있어
        // 처리 완료로 닫으면 그 출금이 영영 확정되지 않는다 — 재시도로 남겨 알림이 오면 붙는다.
        every { inbox.findNextPendingForUpdate(any()) } returns inboxItem()
        every { decision.decide(any(), any()) } returns
            DfnsChainDecisionOutcome.OutgoingUnattachable(
                transfer(direction = NetworkChainDirection.OUT),
                OutgoingAttachMiss.MISMATCH,
            )
        every {
            inbox.recordFailure(NOTIFICATION_ID, "outgoing transfer cannot be attached: MISMATCH", 1, any(), any())
        } returns WebhookFailureResult(quarantined = true, retryCount = 1)

        assertThat(transaction().processNext()).isInstanceOf(WebhookDecisionOutcome.Quarantined::class.java)

        every { decision.decide(any(), any()) } returns
            DfnsChainDecisionOutcome.OutgoingPending(transfer(direction = NetworkChainDirection.OUT))
        every {
            inbox.recordFailure(NOTIFICATION_ID, "outgoing transfer has no matching transaction yet", 3, any(), any())
        } returns WebhookFailureResult(quarantined = false, retryCount = 1)

        assertThat(transaction().processNext()).isInstanceOf(WebhookDecisionOutcome.Retrying::class.java)
        verify(exactly = 0) { inbox.markProcessed(any(), any(), any()) }
    }

    @Test
    fun `같은 hash에 거래가 여럿인 발신은 상한을 기다리지 않고 즉시 격리한다`() {
        // 하나를 고르는 규칙을 지어내면 다른 거래에 남의 확정이 붙는다 — 처리 완료로 소거하지 않는다(계약13).
        every { inbox.findNextPendingForUpdate(any()) } returns inboxItem()
        every { decision.decide(any(), any()) } returns
            DfnsChainDecisionOutcome.OutgoingAmbiguous(transfer(direction = NetworkChainDirection.OUT), 2)
        every {
            inbox.recordFailure(NOTIFICATION_ID, "outgoing transfer matches multiple transactions", 1, any(), any())
        } returns WebhookFailureResult(quarantined = true, retryCount = 1)

        val outcome = transaction().processNext()

        assertThat(outcome).isInstanceOf(WebhookDecisionOutcome.Quarantined::class.java)
        verify(exactly = 0) { inbox.markProcessed(any(), any(), any()) }
    }

    @Test
    fun `한 제출 키에 두 전송이 붙은 충돌은 상한을 기다리지 않고 즉시 격리한다`() {
        // 이중 제출 신호다 — 재시도가 결과를 바꾸지 못하므로 처리 완료로 소거하면 신호가 사라진다(03 전이 표).
        every { inbox.findNextPendingForUpdate(any()) } returns inboxItem()
        every { transferDecision.decide(any(), any()) } returns
            DfnsTransferDecisionOutcome.Conflicting(mockk(relaxed = true), "ext-1")
        // 격리 사유는 인박스에 남는 값이라 원문·전송 ID·제출 키·주소·금액을 담지 않는다.
        every {
            inbox.recordFailure(NOTIFICATION_ID, "submission key linked to another transfer", 1, any(), any())
        } returns WebhookFailureResult(quarantined = true, retryCount = 1)

        val outcome = transaction().processNext()

        assertThat(outcome).isInstanceOf(WebhookDecisionOutcome.Quarantined::class.java)
        verify(exactly = 0) { inbox.markProcessed(any(), any(), any()) }
    }

    @Test
    fun `대기 건이 없으면 아무것도 하지 않는다`() {
        every { inbox.findNextPendingForUpdate(any()) } returns null

        assertThat(transaction().processNext()).isEqualTo(WebhookDecisionOutcome.NoWork)
        verify(exactly = 0) { decision.decide(any(), any()) }
    }

    @Test
    fun `입금 판단이 끝나면 발행한 이벤트 수와 함께 처리 완료로 표시한다`() {
        every { inbox.findNextPendingForUpdate(any()) } returns inboxItem()
        every { decision.decide(NOTIFICATION_ID, PAYLOAD.toByteArray()) } returns
            DfnsChainDecisionOutcome.Processed(transfer(), com.whatto.bcm.domain.tx.TxStatus.FINALIZED, emptyList())
        val completed = slot<Boolean>()
        every { inbox.markProcessed(NOTIFICATION_ID, any(), capture(completed)) } returns Unit

        val outcome = transaction().processNext()

        assertThat(outcome).isEqualTo(WebhookDecisionOutcome.Processed(NOTIFICATION_ID, 0))
        // `vndr_cmpl_yn`은 03이 Fireblocks 원본 보관용으로 정의한 표식이다 — Dfns 보관 경로가 없어 남기지 않는다.
        assertThat(completed.captured).isFalse()
    }

    @Test
    fun `벤더 확인 여부와 무관하게 Fireblocks 보관 표식은 남기지 않는다`() {
        listOf(NetworkChainTransferStatus.CONFIRMED, NetworkChainTransferStatus.INCLUDED).forEach { status ->
            every { inbox.findNextPendingForUpdate(any()) } returns inboxItem()
            every { decision.decide(any(), any()) } returns
                DfnsChainDecisionOutcome.Processed(
                    transfer(status = status),
                    com.whatto.bcm.domain.tx.TxStatus.CONFIRMED,
                    emptyList(),
                )
            val completed = slot<Boolean>()
            every { inbox.markProcessed(NOTIFICATION_ID, any(), capture(completed)) } returns Unit

            transaction().processNext()

            assertThat(completed.captured).describedAs(status.name).isFalse()
        }
    }

    @Test
    fun `미귀속 입금은 우리 어휘로 경보를 올리고 처리 완료로 남긴다`() {
        every { inbox.findNextPendingForUpdate(any()) } returns inboxItem()
        every { decision.decide(any(), any()) } returns
            DfnsChainDecisionOutcome.Unattributed(
                transfer(),
                NetworkChainAttributionMiss.UNKNOWN_ADDRESS,
                NETWORK,
                "USDC",
            )

        val outcome = transaction().processNext()

        val alert = (outcome as WebhookDecisionOutcome.Unattributed).alert
        assertThat(alert.notificationId).isEqualTo(NOTIFICATION_ID)
        assertThat(alert.network).isEqualTo(NETWORK)
        assertThat(alert.symbol).isEqualTo("USDC")
        // 파생 거래 ID를 만들 수 없는 관찰도 있으므로 경보에는 온체인 hash를 싣는다.
        assertThat(alert.vendorTransactionId).isEqualTo(TX_HASH)
        verify { inbox.markProcessed(NOTIFICATION_ID, any(), false) }
    }

    @Test
    fun `아직 판단하지 않는 계열은 원장을 건드리지 않고 처리 완료로 남긴다`() {
        listOf(
            DfnsChainDecisionOutcome.NotChainEvent,
            DfnsChainDecisionOutcome.UnsupportedAsset(transfer()),
            DfnsChainDecisionOutcome.UnmappedAsset(transfer()),
            DfnsChainDecisionOutcome.MissingDecimals(transfer()),
            DfnsChainDecisionOutcome.MissingSender(transfer()),
        ).forEach { decided ->
            every { inbox.findNextPendingForUpdate(any()) } returns inboxItem()
            every { decision.decide(any(), any()) } returns decided

            assertThat(transaction().processNext())
                .describedAs(decided.toString())
                .isEqualTo(WebhookDecisionOutcome.Ignored(NOTIFICATION_ID))
        }
    }

    @Test
    fun `payload 결함은 재시도로, 상한에 닿으면 격리로 남긴다`() {
        every { inbox.findNextPendingForUpdate(any()) } returns inboxItem()
        every { decision.decide(any(), any()) } throws WebhookPayloadException("Dfns 웹훅 결손: id")
        every { inbox.recordFailure(NOTIFICATION_ID, "Dfns 웹훅 결손: id", 3, any(), any()) } returns WebhookFailureResult(1, false)

        assertThat(transaction().processNext()).isEqualTo(WebhookDecisionOutcome.Retrying(NOTIFICATION_ID, 1))

        every { inbox.recordFailure(NOTIFICATION_ID, "Dfns 웹훅 결손: id", 3, any(), any()) } returns WebhookFailureResult(3, true)
        assertThat(transaction().processNext()).isEqualTo(WebhookDecisionOutcome.Quarantined(NOTIFICATION_ID, 3))
        verify(exactly = 0) { inbox.markProcessed(any(), any(), any()) }
    }

    @Test
    fun `설정 오류는 인박스를 P로 남기고 충돌·그 밖의 오류는 워커가 처리하도록 감싼다`() {
        every { inbox.findNextPendingForUpdate(any()) } returns inboxItem()

        // 운영 설정 오류는 payload poison이 아니다 — 실패 기록 없이 그대로 올려 복구 뒤 다시 처리한다.
        every { decision.decide(any(), any()) } throws FinalityPolicyConfigurationException(NETWORK)
        assertThatThrownBy { transaction().processNext() }.isInstanceOf(FinalityPolicyConfigurationException::class.java)

        every { decision.decide(any(), any()) } throws ConflictException("tx", "dup")
        assertThatThrownBy { transaction().processNext() }.isInstanceOf(WebhookDecisionConflictException::class.java)

        every { decision.decide(any(), any()) } throws IllegalStateException("boom")
        assertThatThrownBy { transaction().processNext() }.isInstanceOf(WebhookDecisionProcessingException::class.java)

        verify(exactly = 0) { inbox.recordFailure(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { inbox.markProcessed(any(), any(), any()) }
    }

    @Test
    fun `예기치 못한 실패 기록은 워커가 따로 요청한다`() {
        every { inbox.recordFailure(NOTIFICATION_ID, "decision processing failed", 3, any(), any()) } returns WebhookFailureResult(2, false)

        assertThat(transaction().recordUnexpectedFailure(NOTIFICATION_ID))
            .isEqualTo(WebhookDecisionOutcome.Retrying(NOTIFICATION_ID, 2))
    }

    private fun transaction() =
        DfnsWebhookDecisionTransaction(
            inboxRepository = inbox,
            transactionRunner =
                object : TransactionRunner {
                    override fun <T> run(block: () -> T): T = block()
                },
            decision = decision,
            transferDecision = transferDecision,
            clock = Clock.fixed(Instant.parse("2026-09-16T01:02:03Z"), ZoneOffset.UTC),
            maxAttempts = 3,
            retryBaseSeconds = 30,
        )

    private fun inboxItem() =
        WebhookInboxItem(
            notificationId = NOTIFICATION_ID,
            eventType = "wallet.blockchainevent.detected",
            vendorTransactionId = null,
            payload = PAYLOAD,
            receivedAt = "20260916010000",
            retryCount = 0,
        )

    private fun transfer(
        direction: NetworkChainDirection = NetworkChainDirection.IN,
        status: NetworkChainTransferStatus = NetworkChainTransferStatus.CONFIRMED,
    ) = NetworkChainTransfer(
        network = NETWORK,
        vendorWalletId = "wa-1f04s-lqc9q-xxxxxxxxxxxxxxxx",
        vendorWalletAddress = null,
        vendorAssetId = "EthereumSepolia:Native",
        vendorAssetKind = "NativeTransfer",
        direction = direction,
        status = status,
        amountBaseUnits = "1500000",
        fromAddress = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238",
        toAddress = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47",
        transactionHash = TX_HASH,
        blockNumber = 8_452_119,
        eventIndex = "3",
        observedAt = "1758067200",
    )

    private companion object {
        const val NOTIFICATION_ID = "whe-544ul-uqgad-jkgltj5p6fvd04cj"
        const val NETWORK = "ETHEREUM_SEPOLIA"
        const val TX_HASH = "0x2a6f0c9b6bd0a7b9f4e3e0b33d2ff4c7cf9a7a0f9b4d2a8f8c1b3e5d7a9c0b11"
        const val PAYLOAD = "{}"
    }
}
