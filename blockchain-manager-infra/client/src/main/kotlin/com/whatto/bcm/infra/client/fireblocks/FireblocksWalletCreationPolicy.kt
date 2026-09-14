package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.account.VendorCallDecision
import com.whatto.bcm.domain.vendor.VendorExecutionLimits
import com.whatto.bcm.domain.vendor.WalletCreationPolicy
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Fireblocks 생성 API의 멱등키 창과 안전한 회전 시점을 판단한다. 로컬 Stub도 같은 계약을 따른다. */
@Component
@ConditionalOnFireblocksProtocol
class FireblocksWalletCreationPolicy(
    limits: VendorExecutionLimits,
) : WalletCreationPolicy {
    private val vendorCallUpperBound = Duration.ofMillis(limits.maximumCallMillis)

    init {
        require(!vendorCallUpperBound.isZero && !vendorCallUpperBound.isNegative) {
            "vendorCallUpperBound must be positive"
        }
        require(vendorCallUpperBound <= MAX_VENDOR_CALL_UPPER_BOUND) {
            "vendorCallUpperBound must not exceed $MAX_VENDOR_CALL_UPPER_BOUND"
        }
    }

    override fun vendorCallDecision(
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
