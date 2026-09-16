package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Dfns 상태 원어 → 공통 TxStatus 번역(02 상태 표·계약13 "상태 번역").
 * 확정은 벤더의 `Confirmed`가 아니라 블록 깊이로 낸다(CLAUDE.md 3절).
 */
class DfnsStatusTranslatorTest {
    private val translator = DfnsStatusTranslator(FinalityPolicy { 12 })

    @Test
    fun `체인에 나가기 전 전송 상태는 제출로 번역한다`() {
        listOf("Pending", "Executing", "Broadcasted").forEach { raw ->
            assertThat(translator.translate(observation(raw), NETWORK)).describedAs(raw).isEqualTo(TxStatus.SUBMITTED)
        }
    }

    @Test
    fun `블록 포함은 미확정이고 종결 원어는 실패·거절로 번역한다`() {
        assertThat(translator.translate(observation("Included", confirmations = 99), NETWORK)).isEqualTo(TxStatus.CONFIRMED)
        assertThat(translator.translate(observation("Failed"), NETWORK)).isEqualTo(TxStatus.FAILED)
        assertThat(translator.translate(observation("Rejected"), NETWORK)).isEqualTo(TxStatus.REJECTED)
    }

    @Test
    fun `Confirmed는 임계 깊이에 도달했을 때만 확정이다`() {
        assertThat(translator.translate(observation("Confirmed", confirmations = 11), NETWORK)).isEqualTo(TxStatus.CONFIRMED)
        assertThat(translator.translate(observation("Confirmed", confirmations = 12), NETWORK)).isEqualTo(TxStatus.FINALIZED)
        assertThat(translator.translate(observation("Confirmed", confirmations = 13), NETWORK)).isEqualTo(TxStatus.FINALIZED)
        // 전송 응답에는 blockNumber가 없어 깊이를 모른다 — 벤더가 Confirmed라 해도 확정으로 올리지 않는다.
        assertThat(translator.translate(observation("Confirmed", confirmations = 0), NETWORK)).isEqualTo(TxStatus.CONFIRMED)
    }

    @Test
    fun `네트워크마다 임계를 따로 묻는다`() {
        val asked = mutableListOf<String>()
        val translator =
            DfnsStatusTranslator { network ->
                asked += network
                if (network == "BASE_SEPOLIA") 1 else 30
            }

        assertThat(translator.translate(observation("Confirmed", confirmations = 1), "BASE_SEPOLIA")).isEqualTo(TxStatus.FINALIZED)
        assertThat(translator.translate(observation("Confirmed", confirmations = 1), NETWORK)).isEqualTo(TxStatus.CONFIRMED)
        assertThat(asked).containsExactly("BASE_SEPOLIA", NETWORK)
        // 확정 판정이 필요 없는 상태는 임계를 묻지 않는다.
        translator.translate(observation("Included"), NETWORK)
        translator.translate(observation("Failed"), NETWORK)
        assertThat(asked).containsExactly("BASE_SEPOLIA", NETWORK)
    }

    @Test
    fun `목록 밖 원어와 음수 컨펌은 임의 상태로 바꾸지 않는다`() {
        listOf("", "confirmed", "COMPLETED", "CONFIRMING", "Finalized", " Confirmed", "Unknown").forEach { raw ->
            assertThatThrownBy { translator.translate(observation(raw), NETWORK) }
                .describedAs(raw)
                .isInstanceOf(WebhookPayloadException::class.java)
        }
        assertThatThrownBy { translator.translate(observation("Confirmed", confirmations = -1), NETWORK) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("confirmationCount")
    }

    @Test
    fun `대사 종결 판정은 만들지 않는다`() {
        // Dfns 대사 경로가 없고, Confirmed를 종결로 돌려주면 블록 깊이 확정 결정을 우회하게 된다.
        listOf("Pending", "Executing", "Broadcasted", "Confirmed", "Failed", "Rejected", "Included").forEach { raw ->
            assertThat(translator.terminalStatusForReconciliation(observation(raw, confirmations = 999), "VAULT_ACCOUNT"))
                .describedAs(raw)
                .isNull()
        }
    }

    private fun observation(
        rawStatus: String,
        confirmations: Int = 0,
    ) = VendorStatusObservation(rawStatus = rawStatus, subStatus = null, confirmationCount = confirmations)

    private companion object {
        const val NETWORK = "ETHEREUM_SEPOLIA"
    }
}
