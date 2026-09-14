package com.whatto.bcm.app.application.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.LogicalAccountRepository
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.support.time.CoreDateTimes
import java.time.Clock
import java.util.UUID

/** 내부 조립용 유스케이스. Dfns 수용 전 공개 API/기본 실행 빈에는 연결하지 않는다. */
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
