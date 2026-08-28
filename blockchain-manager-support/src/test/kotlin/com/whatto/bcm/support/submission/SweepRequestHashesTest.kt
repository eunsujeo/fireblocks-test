package com.whatto.bcm.support.submission

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SweepRequestHashesTest {
    @Test
    fun `계정과 source event 배열 순서가 달라도 같은 요청 hash다`() {
        val first =
            SweepRequestHashes.requestV1(
                "BASE",
                "USDC",
                listOf(
                    SweepRequestHashItem("account-b", listOf(EVENT_B, EVENT_A)),
                    SweepRequestHashItem("account-a", listOf(EVENT_C)),
                ),
            )
        val reordered =
            SweepRequestHashes.requestV1(
                "BASE",
                "USDC",
                listOf(
                    SweepRequestHashItem("account-a", listOf(EVENT_C)),
                    SweepRequestHashItem("account-b", listOf(EVENT_A, EVENT_B)),
                ),
            )

        assertThat(reordered).isEqualTo(first)
        assertThat(first.items.map { it.accountId }).containsExactly("account-a", "account-b")
        assertThat(first.items[1].sourceEventIds).containsExactly(EVENT_A, EVENT_B)
    }

    @Test
    fun `중복 계정과 중복 source event는 거절한다`() {
        assertThatThrownBy {
            SweepRequestHashes.requestV1(
                "BASE",
                "USDC",
                listOf(
                    SweepRequestHashItem("account-a", listOf(EVENT_A)),
                    SweepRequestHashItem("account-a", listOf(EVENT_B)),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("account")

        assertThatThrownBy {
            SweepRequestHashes.requestV1(
                "BASE",
                "USDC",
                listOf(
                    SweepRequestHashItem("account-a", listOf(EVENT_A)),
                    SweepRequestHashItem("account-b", listOf(EVENT_A)),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("source event")
    }

    private companion object {
        const val EVENT_A = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891"
        const val EVENT_B = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7892"
        const val EVENT_C = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7893"
    }
}
