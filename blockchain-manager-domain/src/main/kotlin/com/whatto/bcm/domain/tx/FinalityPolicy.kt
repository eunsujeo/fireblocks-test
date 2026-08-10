package com.whatto.bcm.domain.tx

/** 네트워크별 DCCP 확인 수 정책. FINALIZED 판정은 벤더 status만 믿지 않고 이 값을 직접 비교한다. */
fun interface FinalityPolicy {
    fun requiredConfirmations(network: String): Int
}
