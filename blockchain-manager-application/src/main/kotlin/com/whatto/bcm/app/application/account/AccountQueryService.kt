package com.whatto.bcm.app.application.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.exception.AccountNotFoundException
import org.springframework.stereotype.Service

/** 다른 피처가 계정 저장소를 직접 참조하지 않도록 필수 계정 조회를 캡슐화한다. */
@Service
class AccountQueryService(
    private val repository: AccountRepository,
) {
    fun findByAccountId(accountId: String): Account? = repository.findByAccountId(accountId)

    fun requiredAccount(accountId: String): Account = repository.findByAccountId(accountId) ?: throw AccountNotFoundException(accountId)
}
