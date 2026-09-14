package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.account.VendorCallDecision
import java.time.LocalDateTime

/** 선택 제공자의 생성 재시도 계약. 조회·저장·외부 호출 없이 키 세대와 대기 여부를 판단한다. */
fun interface WalletCreationPolicy {
    fun vendorCallDecision(
        currentKey: String,
        keyRegisteredAt: String,
        lastVendorCallPreparedAt: String?,
        now: LocalDateTime,
    ): VendorCallDecision
}
