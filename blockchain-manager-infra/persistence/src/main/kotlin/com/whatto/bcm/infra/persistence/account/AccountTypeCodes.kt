package com.whatto.bcm.infra.persistence.account

import com.whatto.bcm.domain.account.AccountType

/**
 * 계정 유형 ↔ DB 구분코드 변환 — 물리 코드값은 infra 소관 (architecture.md).
 * 코어 규약의 `_dvcd` 는 짧은 코드이므로 도메인 이름을 그대로 저장하지 않는다 (03-bcm-db).
 */
internal object AccountTypeCodes {
    private const val CUSTOMER = "CU"
    private const val SYSTEM = "SY"

    fun toCode(accountType: AccountType): String =
        when (accountType) {
            AccountType.CUSTOMER -> CUSTOMER
            AccountType.SYSTEM -> SYSTEM
        }

    fun toDomain(code: String): AccountType =
        when (code) {
            CUSTOMER -> AccountType.CUSTOMER
            SYSTEM -> AccountType.SYSTEM
            else -> throw IllegalStateException("알 수 없는 계정유형 구분코드: $code")
        }
}
