package com.whatto.bcm.app.application.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.exception.AccountNotFoundException
import org.springframework.stereotype.Service

@Service
class AccountQueryService(
    private val accounts: AccountRepository,
) {
    fun requiredAccount(accountId: String): Account = accounts.findByAccountId(accountId) ?: throw AccountNotFoundException(accountId)
}
