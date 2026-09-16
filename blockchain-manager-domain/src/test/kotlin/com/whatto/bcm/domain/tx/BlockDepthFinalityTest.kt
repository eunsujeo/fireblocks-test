package com.whatto.bcm.domain.tx

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 블록 깊이로 확정을 판정하는 순수 규칙(계약13 "확정 판정") — 벤더 상태 원어를 보지 않는다. */
class BlockDepthFinalityTest {
    @Test
    fun `블록 자체가 1컨펌이고 head가 못 미치면 0이다`() {
        assertThat(BlockDepthFinality.confirmations(headBlockNumber = 100, blockNumber = 100)).isEqualTo(1)
        assertThat(BlockDepthFinality.confirmations(headBlockNumber = 101, blockNumber = 100)).isEqualTo(2)
        assertThat(BlockDepthFinality.confirmations(headBlockNumber = 112, blockNumber = 100)).isEqualTo(13)
        // 관측 지연·재구성으로 head가 사건 블록보다 낮게 보일 수 있다 — 음수 컨펌을 만들지 않는다.
        assertThat(BlockDepthFinality.confirmations(headBlockNumber = 99, blockNumber = 100)).isEqualTo(0)
        assertThat(BlockDepthFinality.confirmations(headBlockNumber = 0, blockNumber = 0)).isEqualTo(1)
    }

    @Test
    fun `임계 이상일 때만 확정이고 벤더가 확정이라 해도 깊이가 모자라면 확정이 아니다`() {
        assertThat(BlockDepthFinality.finalized(headBlockNumber = 100, blockNumber = 100, requiredConfirmations = 1)).isTrue()
        assertThat(BlockDepthFinality.finalized(headBlockNumber = 100, blockNumber = 100, requiredConfirmations = 2)).isFalse()
        assertThat(BlockDepthFinality.finalized(headBlockNumber = 111, blockNumber = 100, requiredConfirmations = 12)).isTrue()
        assertThat(BlockDepthFinality.finalized(headBlockNumber = 110, blockNumber = 100, requiredConfirmations = 12)).isFalse()
        assertThat(BlockDepthFinality.finalized(headBlockNumber = 99, blockNumber = 100, requiredConfirmations = 1)).isFalse()
    }

    @Test
    fun `음수 블록과 0 이하 임계는 판정 입력으로 받지 않는다`() {
        listOf<Pair<String, () -> Any>>(
            "headBlockNumber" to { BlockDepthFinality.confirmations(headBlockNumber = -1, blockNumber = 0) },
            "blockNumber" to { BlockDepthFinality.confirmations(headBlockNumber = 0, blockNumber = -1) },
            "requiredConfirmations" to { BlockDepthFinality.finalized(100, 100, requiredConfirmations = 0) },
            "requiredConfirmations" to { BlockDepthFinality.finalized(100, 100, requiredConfirmations = -3) },
        ).forEach { (field, call) ->
            assertThatThrownBy { call() }
                .describedAs(field)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(field)
        }
    }
}
