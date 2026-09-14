package com.whatto.bcm.domain.account

import com.whatto.bcm.domain.provider.ProviderOrigin

interface LogicalAccountRepository {
    /** 계정은 독립 트랜잭션으로 커밋하며 동일 유형/ref의 최초 accountId로 합류한다. */
    fun reserve(
        account: Account,
        origin: ProviderOrigin,
    ): Account
}
