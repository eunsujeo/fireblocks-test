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

/** Dfns 인박스 판단 — 결과를 인박스 상태로 옮기는 경계(계약13). 입금만 판단하고 나머지는 처리 완료로 남긴다. */
class DfnsWebhookDecisionTransactionTest {
    private val inbox = mockk<WebhookInboxRepository>(relaxed = true)
    private val decision = mockk<DfnsChainEventDecision>()

    @Test
    fun `대기 건이 없으면 아무것도 하지 않는다`() {
        every { inbox.findNextPendingForUpdate() } returns null

        assertThat(transaction().processNext()).isEqualTo(WebhookDecisionOutcome.NoWork)
        verify(exactly = 0) { decision.decide(any(), any()) }
    }

    @Test
    fun `입금 판단이 끝나면 발행한 이벤트 수와 함께 처리 완료로 표시한다`() {
        every { inbox.findNextPendingForUpdate() } returns inboxItem()
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
            every { inbox.findNextPendingForUpdate() } returns inboxItem()
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
        every { inbox.findNextPendingForUpdate() } returns inboxItem()
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
            DfnsChainDecisionOutcome.Outgoing(transfer(direction = NetworkChainDirection.OUT)),
            DfnsChainDecisionOutcome.UnsupportedAsset(transfer()),
            DfnsChainDecisionOutcome.UnmappedAsset(transfer()),
            DfnsChainDecisionOutcome.MissingDecimals(transfer()),
            DfnsChainDecisionOutcome.MissingSender(transfer()),
        ).forEach { decided ->
            every { inbox.findNextPendingForUpdate() } returns inboxItem()
            every { decision.decide(any(), any()) } returns decided

            assertThat(transaction().processNext())
                .describedAs(decided.toString())
                .isEqualTo(WebhookDecisionOutcome.Ignored(NOTIFICATION_ID))
        }
    }

    @Test
    fun `payload 결함은 재시도로, 상한에 닿으면 격리로 남긴다`() {
        every { inbox.findNextPendingForUpdate() } returns inboxItem()
        every { decision.decide(any(), any()) } throws WebhookPayloadException("Dfns 웹훅 결손: id")
        every { inbox.recordFailure(NOTIFICATION_ID, "Dfns 웹훅 결손: id", 3) } returns WebhookFailureResult(1, false)

        assertThat(transaction().processNext()).isEqualTo(WebhookDecisionOutcome.Retrying(NOTIFICATION_ID, 1))

        every { inbox.recordFailure(NOTIFICATION_ID, "Dfns 웹훅 결손: id", 3) } returns WebhookFailureResult(3, true)
        assertThat(transaction().processNext()).isEqualTo(WebhookDecisionOutcome.Quarantined(NOTIFICATION_ID, 3))
        verify(exactly = 0) { inbox.markProcessed(any(), any(), any()) }
    }

    @Test
    fun `설정 오류는 인박스를 P로 남기고 충돌·그 밖의 오류는 워커가 처리하도록 감싼다`() {
        every { inbox.findNextPendingForUpdate() } returns inboxItem()

        // 운영 설정 오류는 payload poison이 아니다 — 실패 기록 없이 그대로 올려 복구 뒤 다시 처리한다.
        every { decision.decide(any(), any()) } throws FinalityPolicyConfigurationException(NETWORK)
        assertThatThrownBy { transaction().processNext() }.isInstanceOf(FinalityPolicyConfigurationException::class.java)

        every { decision.decide(any(), any()) } throws ConflictException("tx", "dup")
        assertThatThrownBy { transaction().processNext() }.isInstanceOf(WebhookDecisionConflictException::class.java)

        every { decision.decide(any(), any()) } throws IllegalStateException("boom")
        assertThatThrownBy { transaction().processNext() }.isInstanceOf(WebhookDecisionProcessingException::class.java)

        verify(exactly = 0) { inbox.recordFailure(any(), any(), any()) }
        verify(exactly = 0) { inbox.markProcessed(any(), any(), any()) }
    }

    @Test
    fun `예기치 못한 실패 기록은 워커가 따로 요청한다`() {
        every { inbox.recordFailure(NOTIFICATION_ID, "decision processing failed", 3) } returns WebhookFailureResult(2, false)

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
            clock = Clock.fixed(Instant.parse("2026-09-16T01:02:03Z"), ZoneOffset.UTC),
            maxAttempts = 3,
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
