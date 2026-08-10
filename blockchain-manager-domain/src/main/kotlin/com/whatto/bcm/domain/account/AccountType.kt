package com.whatto.bcm.domain.account

/**
 * 계정 유형 — ref 가 어느 ID 공간의 값인지 가린다 (03-bcm-db bcm_acnt_m.acnt_typ_dvcd).
 *
 * DAW-CORE 는 고객 계정(daw_acnt_m)과 시스템 계정(daw_sys_acnt_m)을 **서로 다른 테이블**에서 발급하고
 * 접두사를 붙이지 않는다(2026-08-05 확인). 그래서 두 유형의 ref 값이 겹칠 수 있고, 유형 없이는
 * 어느 쪽 계정인지 가릴 수 없다 — 계정 매핑의 유일성은 (유형, ref) 조합으로만 성립한다.
 */
enum class AccountType {
    /** 고객 계정 — DAW-CORE daw_acnt_m.acnt_id */
    CUSTOMER,

    /** 시스템(운영) 계정 — DAW-CORE daw_sys_acnt_m.sys_acnt_id */
    SYSTEM,
}
