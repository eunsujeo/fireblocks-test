package com.whatto.bcm.support.submission

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SweepBatchRequestHashesTest {
    @Test
    fun `batch-v1은 주소순으로 정렬하고 금액을 정규화해 고정 hash를 만든다`() {
        val fingerprint =
            fingerprint(
                listOf(
                    SweepBatchHashItem(OWNER_B, "12.500"),
                    SweepBatchHashItem(OWNER_A, "3.0"),
                ),
            )

        assertThat(fingerprint.items)
            .containsExactly(
                SweepBatchHashItem(OWNER_A, "3"),
                SweepBatchHashItem(OWNER_B, "12.5"),
            )
        assertThat(fingerprint.totalAmount).isEqualTo("15.5")
        assertThat(fingerprint.requestHash)
            .isEqualTo("771d0851bbbbd7fadc6dffbcf4834595337018bf46db8d7490e7923777049a5f")
    }

    @Test
    fun `입력 순서와 숫자 표기만 다르면 같은 batch 의도다`() {
        val first = fingerprint(listOf(SweepBatchHashItem(OWNER_B, "12.50"), SweepBatchHashItem(OWNER_A, "3")))
        val second = fingerprint(listOf(SweepBatchHashItem(OWNER_A, "3.00"), SweepBatchHashItem(OWNER_B, "12.5")))

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `같은 원천 주소를 두 항목에 넣거나 0 이하 금액을 넣지 못한다`() {
        assertThatThrownBy {
            fingerprint(listOf(SweepBatchHashItem(OWNER_A, "1"), SweepBatchHashItem(OWNER_A, "2")))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unique")
        assertThatThrownBy { fingerprint(listOf(SweepBatchHashItem(OWNER_A, "0"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("positive")
    }

    @Test
    fun `execution id는 canonical UUID v7만 허용한다`() {
        assertThatThrownBy {
            SweepBatchRequestHashes.batchV1(
                "ETHEREUM",
                "USDC",
                TOKEN,
                SWEEPER,
                "01987654-3210-6abc-8def-0123456789ab",
                listOf(SweepBatchHashItem(OWNER_A, "1")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UUID v7")
    }

    private fun fingerprint(items: List<SweepBatchHashItem>) =
        SweepBatchRequestHashes.batchV1(
            network = "ETHEREUM",
            symbol = "USDC",
            tokenContractAddress = TOKEN,
            sweepContractAddress = SWEEPER,
            executionId = EXECUTION_ID,
            items = items,
        )

    private companion object {
        const val TOKEN = "0x1111111111111111111111111111111111111111"
        const val OWNER_A = "0x2222222222222222222222222222222222222222"
        const val OWNER_B = "0x3333333333333333333333333333333333333333"
        const val SWEEPER = "0x4444444444444444444444444444444444444444"
        const val EXECUTION_ID = "01987654-3210-7abc-8def-0123456789ab"
    }
}
