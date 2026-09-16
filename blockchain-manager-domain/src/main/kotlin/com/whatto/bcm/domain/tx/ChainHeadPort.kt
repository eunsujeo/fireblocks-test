package com.whatto.bcm.domain.tx

/**
 * 네트워크의 현재 체인 head 블록 번호를 읽는다.
 *
 * 벤더가 컨펌 수를 주지 않는 경로(Dfns)에서 확정 판정의 근거가 된다 — 벤더의 확정 표기는 reorg로 뒤집힐 수 있어
 * 그것만으로 [TxStatus.FINALIZED]를 내지 않는다(CLAUDE.md 3절 확정 결정). 조회에 실패하면 확정을 보류해야 하므로
 * 구현은 실패를 감추지 않고 예외로 올린다 — 모름을 "아직 미확정"으로 바꾸면 늦은 확정이 영영 오지 않을 수 있다.
 */
fun interface ChainHeadPort {
    fun headBlockNumber(network: String): Long
}

/** 관측 블록의 깊이로 확정을 판정하는 순수 규칙. 벤더 상태 원어를 보지 않는다. */
object BlockDepthFinality {
    /** 블록 자체가 1컨펌이다. head가 아직 사건 블록에 못 미치면(관측 지연·재구성) 0으로 본다 — 음수를 만들지 않는다. */
    fun confirmations(
        headBlockNumber: Long,
        blockNumber: Long,
    ): Long {
        require(headBlockNumber >= 0) { "headBlockNumber must not be negative" }
        require(blockNumber >= 0) { "blockNumber must not be negative" }
        if (headBlockNumber < blockNumber) return 0
        return headBlockNumber - blockNumber + 1
    }

    fun finalized(
        headBlockNumber: Long,
        blockNumber: Long,
        requiredConfirmations: Int,
    ): Boolean {
        require(requiredConfirmations >= 1) { "requiredConfirmations must be positive" }
        return confirmations(headBlockNumber, blockNumber) >= requiredConfirmations
    }
}
