package com.whatto.bcm.domain.account

/** 계정 매핑 저장소 포트 — 구현은 infra/persistence (DIP) */
interface AccountRepository {
    /** 신규 계정 저장 — (유형, ref) 충돌(멱등 경합)은 DB 복합 UNIQUE 가 막는다 */
    fun insert(account: Account): Account

    /** 유형과 ref 는 함께 조회한다 — ref 단독으로는 다른 유형의 계정을 집어낼 수 있다 */
    fun findByTypeAndRef(
        accountType: AccountType,
        ref: String,
    ): Account?

    fun findByAccountId(accountId: String): Account?

    fun findAll(): List<Account> = emptyList()
}
