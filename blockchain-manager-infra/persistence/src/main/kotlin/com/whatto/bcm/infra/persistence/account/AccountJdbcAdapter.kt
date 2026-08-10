package com.whatto.bcm.infra.persistence.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.exception.ConflictException
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

/** bcm_acnt_m 파생 쿼리 — 어댑터 내부 전용 */
interface AccountCrudRepository : CrudRepository<AccountEntity, String> {
    fun findByAcntTypDvcdAndRef(
        acntTypDvcd: String,
        ref: String,
    ): AccountEntity?
}

@Repository
class AccountJdbcAdapter(
    private val crud: AccountCrudRepository,
    private val template: JdbcAggregateTemplate,
) : AccountRepository {
    // 문자열 PK 는 신규 판별이 안 돼 save() 가 UPDATE 를 시도한다 — 명시적 insert
    override fun insert(account: Account): Account =
        try {
            template.insert(AccountEntity.from(account)).toDomain()
        } catch (exception: DuplicateKeyException) {
            // (유형, ref) 복합 UNIQUE 경합 — 기술 예외를 도메인 예외로 변환, 호출부가 이긴 값을 재조회한다 (03)
            throw ConflictException(
                resource = "account",
                key = "${account.accountType}:${account.ref}",
                cause = exception,
            )
        }

    override fun findByTypeAndRef(
        accountType: AccountType,
        ref: String,
    ): Account? = crud.findByAcntTypDvcdAndRef(AccountTypeCodes.toCode(accountType), ref)?.toDomain()

    override fun findByAccountId(accountId: String): Account? = crud.findByIdOrNull(accountId)?.toDomain()
}
