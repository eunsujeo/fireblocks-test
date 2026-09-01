package com.whatto.bcm.domain.account

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.vendor.VendorDepositAddress
import com.whatto.bcm.domain.vendor.VendorVault
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

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
 * vault·wallet 생성의 순수 정책. 조회·저장·외부 호출은 하지 않고 후보 유일성, cursor 진행, 키 세대만 판단한다.
 */
class WalletProvisioningPolicy(
    private val vendorCallUpperBound: Duration,
) {
    init {
        require(!vendorCallUpperBound.isZero && !vendorCallUpperBound.isNegative) {
            "vendorCallUpperBound must be positive"
        }
        require(vendorCallUpperBound <= MAX_VENDOR_CALL_UPPER_BOUND) {
            "vendorCallUpperBound must not exceed $MAX_VENDOR_CALL_UPPER_BOUND"
        }
    }

    fun vendorCallDecision(
        currentKey: String,
        keyRegisteredAt: String,
        lastVendorCallPreparedAt: String?,
        now: LocalDateTime,
    ): VendorCallDecision {
        val keyExpiresAt = parse(keyRegisteredAt).plus(IDEMPOTENCY_WINDOW)
        if (now.plus(vendorCallUpperBound).isBefore(keyExpiresAt)) {
            return VendorCallDecision.Reuse(currentKey, keyRegisteredAt)
        }
        val safeRotationAt =
            lastVendorCallPreparedAt?.let {
                parse(it).plus(vendorCallUpperBound).plus(IDEMPOTENCY_WINDOW).plus(STORED_TIME_PRECISION)
            }
        if (safeRotationAt != null && safeRotationAt.isAfter(now)) {
            return VendorCallDecision.RetryLater(ceilSeconds(Duration.between(now, safeRotationAt)))
        }
        return VendorCallDecision.Rotate
    }

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

    private fun parse(value: String): LocalDateTime = LocalDateTime.parse(value, DATE_TIME_FORMATTER)

    private fun ceilSeconds(duration: Duration): Long = duration.seconds + if (duration.nano == 0) 0 else 1

    companion object {
        val IDEMPOTENCY_WINDOW: Duration = Duration.ofHours(24)
        val MAX_VENDOR_CALL_UPPER_BOUND: Duration = Duration.ofMinutes(5)
        private val STORED_TIME_PRECISION: Duration = Duration.ofSeconds(1)
        private val DATE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    }
}
