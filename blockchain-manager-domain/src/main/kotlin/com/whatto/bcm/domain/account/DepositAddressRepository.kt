package com.whatto.bcm.domain.account

/** 입금 주소 매핑 저장소 포트 — 구현은 infra/persistence (DIP) */
interface DepositAddressRepository {
    /** 신규 발급 저장 — (계정, 네트워크, 심볼) 충돌은 DB PK 가 막는다 */
    fun insert(depositAddress: DepositAddress): DepositAddress

    fun find(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddress?

    /** 발급된 주소 목록 — symbol·network 는 선택 필터, 둘 다 null 이면 그 계정의 전체 */
    fun findAll(
        accountId: String,
        symbol: String?,
        network: String?,
    ): List<DepositAddress>

    /** 입금 귀속 — 같은 네트워크·주소를 공유하는 토큰도 심볼별 발급 기록으로 구분한다 */
    fun findByAddress(
        address: String,
        network: String,
        symbol: String,
    ): DepositAddress?

    /** Admin 매핑 삭제 가드 — 계정과 무관하게 이 자산으로 발급된 주소가 하나라도 있는지 확인한다. */
    fun existsByAsset(
        network: String,
        symbol: String,
    ): Boolean
}
