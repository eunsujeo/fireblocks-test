package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.tx.TxRecord

/**
 * 발신(`direction: Out`) 온체인 이동 사건이 알려준 **블록 좌표를 어느 거래에 적용할지**의 판정(계약13 "발신 확정의 블록 좌표").
 *
 * 이 사건에서 읽는 것은 **`txHash`가 어느 블록에 있는가** 하나다. 그건 블록의 사실이라 어느 이동이 실어 왔든 답이 같다.
 * **이 이동이 어느 제출의 것인지는 묻지 않는다** — 벤더는 이동과 제출을 잇는 키를 주지 않으므로 증명할 수 없고,
 * 증명할 수 없는 판정을 확정의 관문으로 세우면 원장 밖 이동이 우리 제출의 확정을 만들어 낸다(2026-09-17 사용자 확정).
 *
 * 귀속의 근거는 따로 있다 — **벤더가 우리 전송 요청에 결속해 알려준 `txHash`**다(전송 알림이 `bcm_tx_l`에 기록한다).
 * 그래서 여기서는 그 hash를 가진 **우리 발신 거래 전부**에 같은 좌표를 적용한다.
 *
 * - 발신 거래는 제출 키(`ext_tx_id`)가 있는 행이다. 입금 행은 제 사건에서 좌표를 받으므로 건드리지 않는다.
 * - 같은 hash에 우리 발신이 여럿이면(벤더가 여러 전송을 한 트랜잭션으로 냈다) **모두 같은 블록**이므로 모두 적용한다 —
 *   하나를 고르는 문제가 아니라서 모호함이 없다.
 *
 * 벤더 호출·원장 쓰기는 하지 않는 순수 판단이다.
 */
object NetworkChainOutgoingCoordinate {
    fun apply(candidates: List<TxRecord>): NetworkChainCoordinateResult {
        val outgoing = candidates.filter { it.externalTxId != null }
        if (outgoing.isEmpty()) return NetworkChainCoordinateResult.NoOutgoingRecord
        return NetworkChainCoordinateResult.Advance(outgoing)
    }
}

sealed interface NetworkChainCoordinateResult {
    /** 이 hash를 가진 우리 발신 거래들 — 사건의 블록 좌표를 전부에 적용한다. */
    data class Advance(
        val records: List<TxRecord>,
    ) : NetworkChainCoordinateResult

    /**
     * 이 hash로 기록된 우리 발신 거래가 없다. **아직 없는 것이지 잘못된 것이 아니다** —
     * 전송 알림이 늦게 올 수 있다(도착 순서는 수용 항목). 거래를 만들지 않고 **재처리 가능한 상태로 남긴다**.
     *
     * 우리가 내지 않은 발신이라면 상한까지 해소되지 않아 격리된다. 그 전에 **전송 알림 판단이 원장 밖 전송을 이미 격리**하므로
     * 이상 신호 자체는 여기서 처음 드러나는 게 아니다.
     */
    data object NoOutgoingRecord : NetworkChainCoordinateResult
}
