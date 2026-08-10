package com.whatto.bcm.app.application.account

import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.exception.BcmException

/**
 * 네트워크 하나의 발급 결과 — 성공이면 [depositAddress], 실패면 [failure] 가 채워진다 (둘 중 하나만).
 * 에러 코드로의 변환은 api 계층이 한다 — application 은 에러 표현을 모른다 (architecture.md).
 */
data class AddressOutcome(
    val network: String,
    val symbol: String,
    val depositAddress: DepositAddress?,
    val failure: BcmException?,
)
