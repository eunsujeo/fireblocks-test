package com.whatto.bcm.domain.account

/** Fireblocks 자원 생성 의도 상태 — 외부 호출 전에 PENDING, 호출 직전 SUBMITTING, 로컬 매핑과 함께 COMPLETED. */
enum class CreationStatus {
    PENDING,
    SUBMITTING,
    COMPLETED,
}

data class AccountCreationIntent(
    val accountId: String,
    val accountType: AccountType,
    val ref: String,
    val vendorVaultName: String,
    val idempotencyKey: String,
    val idempotencyKeyRegisteredAt: String,
    /** POST 직전 선기록한 보수적 상한. 실제 벤더 호출이 발생했다는 증거는 아니다. */
    val lastVendorCallPreparedAt: String?,
    val status: CreationStatus,
    val attemptCount: Int,
    val vendorVaultId: String?,
    val registeredAt: String,
    val lastChangedAt: String,
)

data class DepositAddressCreationIntent(
    val accountId: String,
    val network: String,
    val symbol: String,
    /** 최초 접수 시점의 벤더 assetId. 미완료 재시도 중 현재 매핑이 바뀌어도 변경하지 않는다. */
    val vendorAssetId: String,
    val idempotencyKey: String,
    val idempotencyKeyRegisteredAt: String,
    /** POST 직전 선기록한 보수적 상한. 실제 벤더 호출이 발생했다는 증거는 아니다. */
    val lastVendorCallPreparedAt: String?,
    val status: CreationStatus,
    val attemptCount: Int,
    val address: String?,
    val registeredAt: String,
    val lastChangedAt: String,
)

/**
 * vault·wallet 생성 의도를 외부 호출보다 먼저 커밋하고, 벤더 결과와 공개 매핑을 원자적으로 완료하는 저장소.
 */
interface WalletProvisioningRepository {
    /** 같은 (유형, ref)가 이미 예약됐으면 기존 의도를 반환한다. */
    fun reserveAccount(intent: AccountCreationIntent): AccountCreationIntent

    fun findAccount(
        accountType: AccountType,
        ref: String,
    ): AccountCreationIntent?

    /** 호출 직전 시도 횟수를 원자 증가시킨다. 다른 요청이 먼저 완료했으면 그 완료 의도를 반환한다. */
    fun beginAccountAttempt(
        accountId: String,
        changedAt: String,
    ): AccountCreationIntent

    /** 최신 attempt만 실제 POST 직전에 키 세대와 호출 준비 상한을 CAS로 고정한다. */
    fun prepareAccountVendorCall(
        intent: AccountCreationIntent,
        idempotencyKey: String,
        idempotencyKeyRegisteredAt: String,
        calledAt: String,
    ): AccountCreationIntent?

    /** 의도 완료와 bcm_acnt_m 매핑을 한 트랜잭션으로 반영한다. */
    fun completeAccount(
        expectedGeneration: AccountCreationIntent,
        vendorVaultId: String,
        completedAt: String,
    ): Account

    /** 같은 (계정, 네트워크, 심볼)가 이미 예약됐으면 최초 assetId snapshot을 가진 기존 의도를 반환한다. */
    fun reserveAddress(intent: DepositAddressCreationIntent): DepositAddressCreationIntent

    fun findAddress(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddressCreationIntent?

    fun beginAddressAttempt(
        accountId: String,
        network: String,
        symbol: String,
        changedAt: String,
    ): DepositAddressCreationIntent

    fun prepareAddressVendorCall(
        intent: DepositAddressCreationIntent,
        idempotencyKey: String,
        idempotencyKeyRegisteredAt: String,
        calledAt: String,
    ): DepositAddressCreationIntent?

    /** 의도 완료와 bcm_addr_m 매핑을 한 트랜잭션으로 반영한다. */
    fun completeAddress(
        expectedGeneration: DepositAddressCreationIntent,
        address: String,
        completedAt: String,
    ): DepositAddress
}
