package com.whatto.bcm.domain.vendor

/**
 * 수탁 지갑 벤더 포트 — vault 생성·입금 주소 발급·잔액 조회 (Phase 2 표면).
 * 구현은 infra/client (Fireblocks). 벤더 원어(에러·필드)는 이 포트 밖으로 새지 않는다.
 */
interface WalletVendorPort {
    /** workspace vault 목록 — Admin 대조 전용이며, cursor가 null이면 첫 페이지다. */
    fun vaults(cursor: String?): VendorPage<VendorVault> = error("vault listing is not supported")

    /** 생성 응답 유실 회수용 exact-name 후보 페이지. 구현은 prefix로 좁힌 뒤 응답 이름을 다시 대조한다. */
    fun vaultsByName(
        name: String,
        cursor: String?,
    ): VendorPage<VendorVault> = error("vault lookup by name is not supported")

    /** vault 생성 — idempotencyKey 로 벤더 측 재요청 중복을 막는다 (벤더 유효기간 24h). */
    fun createVault(
        name: String,
        idempotencyKey: String,
    ): VendorVault

    /** (vault, 자산) 지갑 생성 = 입금 주소 발급 — (accountId, asset)당 주소 하나 (03-bcm-db bcm_addr_m). */
    fun createDepositAddress(
        vaultId: String,
        assetSymbol: String,
        idempotencyKey: String,
    ): VendorDepositAddress

    /** 생성 응답 유실 회수용 vault wallet 주소 페이지. wallet이 없다는 404는 빈 페이지다. */
    fun depositAddresses(
        vaultId: String,
        assetSymbol: String,
        cursor: String?,
    ): VendorPage<VendorDepositAddress> = error("deposit address listing is not supported")

    fun balanceOf(
        vaultId: String,
        assetSymbol: String,
    ): VendorBalance
}

data class VendorVault(
    val vaultId: String,
    val name: String,
    val walletCount: Int? = null,
)

data class VendorDepositAddress(
    val address: String,
    /** Tag/Memo 계열 자산만 — 그 외 null */
    val tag: String?,
)

/**
 * 벤더 잔액 — 전 필드 문자열 (정밀도 — CLAUDE.md 3절 금액 원칙).
 * total = available + pending + lockedAmount + frozen (벤더 스펙 정의).
 */
data class VendorBalance(
    val total: String,
    val available: String,
    val pending: String,
    val frozen: String,
    val lockedAmount: String,
)
