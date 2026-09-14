package com.whatto.bcm.domain.account

import com.whatto.bcm.domain.exception.ConflictException

/**
 * 계정 매핑 — (유형, ref)당 VAULT 또는 LOGICAL 계정 하나 (03-bcm-db bcm_acnt_m).
 * ref 는 DAW-CORE 계정 ID 그대로이고 접두사가 없다 — 불투명 유일 문자열로만 다루고 파싱해 분기하지 않는다.
 * 유형이 없으면 고객·시스템 ref 가 겹칠 수 있어 유일성이 성립하지 않는다 ([AccountType]).
 *
 * 일시는 코어 규약의 UTC yyyyMMddHHmmss 문자열이다.
 */
data class Account(
    val accountId: String,
    /** 계정 유형 — ref 와 짝을 이뤄 멱등의 기준이 된다 */
    val accountType: AccountType,
    /** 백엔드 참조 키 — 유형과 함께 계정 생성 멱등의 기준 (DB 복합 UNIQUE 가 최종 방어) */
    val ref: String,
    /** 벤더 vault id — 백엔드에 노출하지 않는다 */
    val vendorVaultId: String?,
    val registeredAt: String,
    val model: AccountModel = AccountModel.VAULT,
) {
    init {
        require(
            when (model) {
                AccountModel.VAULT -> !vendorVaultId.isNullOrBlank() && vendorVaultId == vendorVaultId.trim()
                AccountModel.LOGICAL -> vendorVaultId == null
            },
        ) { "Invalid account model and vault combination" }
    }

    fun requireVendorVaultId(): String = vendorVaultId ?: throw ConflictException("vaultAccountRequired", accountId)

    fun requireLogical() {
        if (model != AccountModel.LOGICAL) throw ConflictException("logicalAccountRequired", accountId)
    }
}

enum class AccountModel {
    VAULT,
    LOGICAL,
}
