package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.tx.TxRecord

/**
 * 우리 지갑으로 들어온 이동(`direction: In`)이 **외부 입금인지 우리 내부이체의 수신측인지**의 판정(계약13 "수신측 중복 입금 방지").
 *
 * 벤더는 관리 계정 간 이동을 **송신 지갑의 `Out`과 수신 지갑의 `In`** 양쪽으로 알린다.
 * 그런데 [NetworkChainAttribution]은 발급 주소로 들어온 모든 `In`을 입금으로 가르므로, 그대로 두면 한 번의 내부이체에
 * `INTERNAL` 이벤트와 `DEPOSIT` 이벤트가 **둘 다** 나가 DAW-CORE가 있지도 않은 입금을 인정한다.
 *
 * 판정은 두 입력만 본다 — 같은 hash의 거래 후보와, **발신 주소가 우리 지갑인지**.
 * 발신 판정을 자산 발급 기록으로 하지 않는 이유는 제출이 그 자산의 주소 발급을 요구하지 않기 때문이다(계약13).
 *
 * 벤더 호출·원장 쓰기는 하지 않는 순수 판단이다.
 */
object NetworkChainIncomingReceipt {
    fun judge(
        candidates: List<TxRecord>,
        senderIsOurWallet: Boolean,
    ): NetworkChainIncomingResult {
        // 같은 hash에 우리 발신 거래가 있으면 업무 이벤트는 제출 원장 쪽에서 이미 났다.
        if (NetworkChainOutgoingCoordinate.apply(candidates) is NetworkChainCoordinateResult.Advance) {
            return NetworkChainIncomingResult.OurOutgoing
        }
        // 아직 결속이 없다. 발신이 우리 지갑이면 전송 알림이 늦은 것일 수 있어 **입금으로 확정하지 않는다** —
        // 이벤트는 취소가 안 되므로 확정보다 보류가 맞다.
        if (senderIsOurWallet) return NetworkChainIncomingResult.Unresolved
        return NetworkChainIncomingResult.External
    }
}

sealed interface NetworkChainIncomingResult {
    /** 우리가 낸 전송의 수신측이다 — 입금을 만들지 않는다. */
    data object OurOutgoing : NetworkChainIncomingResult

    /**
     * 발신이 우리 지갑인데 그 hash의 발신 거래가 아직 없다. **보류한다**(재시도) —
     * 전송 알림이 오면 위 분기로 해소되고, 상한까지 안 오면 격리돼 운영이 본다.
     */
    data object Unresolved : NetworkChainIncomingResult

    /** 외부에서 온 입금이다 — 현행 그대로 처리한다. */
    data object External : NetworkChainIncomingResult
}
