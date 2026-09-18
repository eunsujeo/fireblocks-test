package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 우리 지갑으로 들어온 이동이 외부 입금인지 우리 내부이체의 수신측인지(계약13 "수신측 중복 입금 방지").
 * 그대로 두면 한 번의 내부이체에 `INTERNAL`과 `DEPOSIT`이 둘 다 나가 없는 입금이 인정된다.
 */
class NetworkChainIncomingReceiptTest {
    @Test
    fun `같은 hash에 우리 발신 거래가 있으면 입금이 아니다`() {
        val result = NetworkChainIncomingReceipt.judge(listOf(outgoing()), senderIsOurWallet = true)

        assertThat(result).isEqualTo(NetworkChainIncomingResult.OurOutgoing)
    }

    @Test
    fun `발신 주소를 몰라도 우리 발신 거래가 있으면 입금이 아니다`() {
        // 결속이 이미 있으면 발신 주소를 다시 묻지 않는다 — 같은 hash의 우리 제출이 곧 답이다.
        val result = NetworkChainIncomingReceipt.judge(listOf(outgoing()), senderIsOurWallet = false)

        assertThat(result).isEqualTo(NetworkChainIncomingResult.OurOutgoing)
    }

    @Test
    fun `발신이 우리 지갑인데 아직 결속이 없으면 보류한다`() {
        // 전송 알림이 늦을 수 있다. 여기서 입금으로 확정하면 되돌릴 수 없다 — 이벤트는 취소가 안 된다.
        val result = NetworkChainIncomingReceipt.judge(emptyList(), senderIsOurWallet = true)

        assertThat(result).isEqualTo(NetworkChainIncomingResult.Unresolved)
    }

    @Test
    fun `발신이 우리 것이 아니면 외부 입금이다`() {
        val result = NetworkChainIncomingReceipt.judge(emptyList(), senderIsOurWallet = false)

        assertThat(result).isEqualTo(NetworkChainIncomingResult.External)
    }

    @Test
    fun `같은 hash의 입금 행만 있는 것은 우리 발신 거래가 아니다`() {
        // 입금 행은 제출 키가 없다 — 그것만 보고 "우리가 낸 전송"이라고 하면 진짜 외부 입금을 삼킨다.
        val deposit = outgoing().copy(externalTxId = null)

        assertThat(NetworkChainIncomingReceipt.judge(listOf(deposit), senderIsOurWallet = false))
            .isEqualTo(NetworkChainIncomingResult.External)
        assertThat(NetworkChainIncomingReceipt.judge(listOf(deposit), senderIsOurWallet = true))
            .isEqualTo(NetworkChainIncomingResult.Unresolved)
    }

    private fun outgoing() =
        TxRecord(
            vendorTxId = "xfr-1",
            externalTxId = "ext-1",
            accountId = "acct-1",
            network = "ETHEREUM_SEPOLIA",
            symbol = "USDC",
            transactionHash = "0x" + "a".repeat(64),
            lastPublishedStatus = TxStatus.CONFIRMED,
            confirmationCount = 0,
            firstDetectedAt = "20260918090000",
            lastChangedAt = "20260918090000",
        )
}
