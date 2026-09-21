package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 발신 사건의 블록 좌표를 어느 거래에 적용할지(계약13 "발신 확정의 블록 좌표").
 *
 * 이 판정은 **이동이 어느 제출의 것인지 묻지 않는다** — 증명할 수 없는 물음이고, 확정에 필요한 것은
 * "이 hash가 어느 블록인가"뿐이다. 귀속은 벤더가 전송 요청에 결속해 준 `txHash`가 이미 해결했다.
 */
class NetworkChainOutgoingCoordinateTest {
    @Test
    fun `이 hash를 가진 우리 발신 거래에 좌표를 적용한다`() {
        val record = outgoing("xfr-1")

        val result = NetworkChainOutgoingCoordinate.apply(listOf(record))

        assertThat(result).isEqualTo(NetworkChainCoordinateResult.Advance(listOf(record)))
    }

    @Test
    fun `같은 hash에 우리 발신이 여럿이면 모두 같은 블록이므로 모두 적용한다`() {
        // 하나를 고르는 문제가 아니다 — 벤더가 여러 전송을 한 트랜잭션으로 냈어도 블록 좌표는 하나다.
        val first = outgoing("xfr-1")
        val second = outgoing("xfr-2")

        val result = NetworkChainOutgoingCoordinate.apply(listOf(first, second))

        assertThat(result).isEqualTo(NetworkChainCoordinateResult.Advance(listOf(first, second)))
    }

    @Test
    fun `같은 hash의 입금 행은 건드리지 않는다`() {
        // 입금은 제 사건에서 좌표를 받는다. 발신 사건이 남의 행 상태를 함께 옮기면 원인과 결과가 어긋난다.
        val deposit = outgoing("dep-1").copy(externalTxId = null)
        val ours = outgoing("xfr-1")

        val result = NetworkChainOutgoingCoordinate.apply(listOf(deposit, ours))

        assertThat(result).isEqualTo(NetworkChainCoordinateResult.Advance(listOf(ours)))
    }

    @Test
    fun `이 hash로 기록된 우리 발신 거래가 없으면 만들지 않고 남긴다`() {
        // 전송 알림이 아직 안 왔을 수 있다 — 여기서 만들면 알림이 나중에 와서 같은 자금의 거래가 둘이 된다.
        assertThat(NetworkChainOutgoingCoordinate.apply(emptyList())).isEqualTo(NetworkChainCoordinateResult.NoOutgoingRecord)
        assertThat(NetworkChainOutgoingCoordinate.apply(listOf(outgoing("dep-1").copy(externalTxId = null))))
            .isEqualTo(NetworkChainCoordinateResult.NoOutgoingRecord)
    }

    private fun outgoing(vendorTxId: String) =
        TxRecord(
            vendorTxId = vendorTxId,
            externalTxId = "ext-$vendorTxId",
            accountId = "acct-1",
            network = "ETHEREUM_SEPOLIA",
            symbol = "USDC",
            transactionHash = HASH,
            lastPublishedStatus = TxStatus.CONFIRMED,
            confirmationCount = 0,
            // 관찰을 이미 통과한 행이다 — 첫 관찰 전 행만 vendorCreatedAt 을 비운다(03 V32).
            vendorCreatedAt = "20260917090000",
            firstDetectedAt = "20260917090000",
            lastChangedAt = "20260917090000",
        )

    private companion object {
        val HASH = "0x" + "a".repeat(64)
    }
}
