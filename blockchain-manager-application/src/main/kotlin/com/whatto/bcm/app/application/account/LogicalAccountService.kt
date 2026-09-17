package com.whatto.bcm.app.application.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.LogicalAccountRepository
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.support.time.CoreDateTimes
import java.time.Clock
import java.util.UUID

/**
 * 논리 계정 생성 유스케이스. 공개 계정 API가 `BCM_PROVIDER=dfns`에서만 이 구현을 조립한다(`DfnsAccountConfig`).
 * 조건부 조립은 Dfns 수용이 아니다 — 전체 기동 차단이 유지되므로 운영에서 실행되지 않는다.
 */
class LogicalAccountService(
    private val repository: LogicalAccountRepository,
    private val origin: ProviderOrigin,
    private val clock: Clock,
) {
    fun create(
        accountType: AccountType,
        ref: String,
    ): Account {
        require(origin.protocolProvider == "dfns") { "Logical accounts require the Dfns origin" }
        require(ref.isNotBlank() && ref == ref.trim() && ref.length <= 64) { "Invalid account reference" }
        return repository.reserve(
            Account("acct_${UUID.randomUUID()}", accountType, ref, null, CoreDateTimes.now(clock), AccountModel.LOGICAL),
            origin,
        )
    }
}
