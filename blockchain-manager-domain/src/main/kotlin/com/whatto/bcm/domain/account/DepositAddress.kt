package com.whatto.bcm.domain.account

/**
 * 입금 주소 매핑 — (계정, 네트워크, 심볼)당 주소 하나 (03-bcm-db bcm_addr_m — 복합 PK 가 발급 멱등의 물리 근거).
 * 자산은 결합 문자열이 아니라 두 값이다 — 같은 자산을 여러 네트워크로 받는 것이 기본 용도다.
 * 입금 감지의 귀속(주소 → 계정)이 역방향 조회로 이 매핑을 푼다.
 */
data class DepositAddress(
    val accountId: String,
    val network: String,
    val symbol: String,
    val address: String,
    val registeredAt: String,
)
