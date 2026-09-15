package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec

/**
 * 생성 의도에 고정할 제출 snapshot(요청 hash·계약 버전·벤더 network)을 벤더 구현이 만든다 — 유스케이스는 벤더 본문 형식을 알지 않는다.
 * 같은 요청에는 항상 같은 snapshot을 돌려줘야 재요청이 기존 의도에 합류한다. null은 그 BCM 네트워크에 벤더 network 매핑이 없다는 뜻이다.
 */
fun interface NetworkWalletSubmissionPort {
    fun submission(request: NetworkWalletCreationRequest): NetworkWalletSubmissionSpec?
}
