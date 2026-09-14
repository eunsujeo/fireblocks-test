package com.whatto.bcm.domain.account

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.vendor.VendorDepositAddress
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletCreationPolicy
import java.time.LocalDateTime

sealed interface VendorCallDecision {
    data class Reuse(
        val idempotencyKey: String,
        val idempotencyKeyRegisteredAt: String,
    ) : VendorCallDecision

    data object Rotate : VendorCallDecision

    data class RetryLater(
        val retryAfterSeconds: Long,
    ) : VendorCallDecision
}

/**
 * 생성 후보 유일성·cursor 진행을 검사하고 키 세대 판단은 선택 제공자 정책에 위임한다.
 */
class WalletProvisioningPolicy(
    private val creationPolicy: WalletCreationPolicy,
) {
    fun vendorCallDecision(
        currentKey: String,
        keyRegisteredAt: String,
        lastVendorCallPreparedAt: String?,
        now: LocalDateTime,
    ): VendorCallDecision = creationPolicy.vendorCallDecision(currentKey, keyRegisteredAt, lastVendorCallPreparedAt, now)

    fun uniqueVault(
        resourceKey: String,
        candidates: Collection<VendorVault>,
    ): VendorVault? {
        if (candidates.size > 1) throw ConflictException("vendorVaultRecovery", resourceKey)
        return candidates.singleOrNull()
    }

    fun uniqueDepositAddress(
        resourceKey: String,
        candidates: Collection<VendorDepositAddress>,
    ): VendorDepositAddress? {
        if (candidates.size > 1) throw ConflictException("vendorDepositAddressRecovery", resourceKey)
        return candidates.singleOrNull()
    }

    fun requireFreshCursor(
        resource: String,
        resourceKey: String,
        seenCursors: Set<String>,
        nextCursor: String?,
    ) {
        if (nextCursor != null && nextCursor in seenCursors) throw ConflictException(resource, resourceKey)
    }
}
