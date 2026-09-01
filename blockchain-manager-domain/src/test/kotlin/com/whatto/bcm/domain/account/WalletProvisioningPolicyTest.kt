package com.whatto.bcm.domain.account

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.vendor.VendorVault
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

class WalletProvisioningPolicyTest {
    private val policy = WalletProvisioningPolicy(Duration.ofSeconds(49))

    @Test
    fun `키와 POST 준비 상한 뒤 24시간과 호출 상한까지 지나야 새 키 세대를 허용한다`() {
        val beforeSafeRotation =
            policy.vendorCallDecision(
                currentKey = "bcm-vlt-old",
                keyRegisteredAt = "20260804120404",
                lastVendorCallPreparedAt = "20260805110405",
                now = LocalDateTime.parse("2026-08-06T11:04:54.500"),
            )
        val atSafeRotation =
            policy.vendorCallDecision(
                currentKey = "bcm-vlt-old",
                keyRegisteredAt = "20260804120404",
                lastVendorCallPreparedAt = "20260805110405",
                now = LocalDateTime.parse("2026-08-06T11:04:55"),
            )

        assertThat(beforeSafeRotation).isEqualTo(VendorCallDecision.RetryLater(1))
        assertThat(atSafeRotation).isEqualTo(VendorCallDecision.Rotate)
    }

    @Test
    fun `재시도 초는 준비 상한과 호출 상한을 포함하고 소수 초를 올림한다`() {
        val decision =
            policy.vendorCallDecision(
                currentKey = "bcm-vlt-old",
                keyRegisteredAt = "20260804120404",
                lastVendorCallPreparedAt = "20260805110405",
                now = LocalDateTime.parse("2026-08-05T12:04:05.500"),
            )

        assertThat(decision).isEqualTo(VendorCallDecision.RetryLater(82_850))
    }

    @Test
    fun `현재 키 세대가 24시간 안이면 같은 키를 유지한다`() {
        val decision =
            policy.vendorCallDecision(
                currentKey = "bcm-vlt-current",
                keyRegisteredAt = "20260805120405",
                lastVendorCallPreparedAt = null,
                now = LocalDateTime.parse("2026-08-05T12:05:05"),
            )

        assertThat(decision).isEqualTo(VendorCallDecision.Reuse("bcm-vlt-current", "20260805120405"))
    }

    @Test
    fun `현재 키가 남아도 호출 상한 안에 만료되면 같은 키를 재사용하지 않는다`() {
        val decision =
            policy.vendorCallDecision(
                currentKey = "bcm-vlt-expiring",
                keyRegisteredAt = "20260804120404",
                lastVendorCallPreparedAt = "20260804120405",
                now = LocalDateTime.parse("2026-08-05T12:03:30"),
            )

        assertThat(decision).isEqualTo(VendorCallDecision.RetryLater(85))
    }

    @Test
    fun `회수 후보가 둘이면 domain 정책이 fail closed한다`() {
        assertThatThrownBy {
            policy.uniqueVault(
                "acct-1",
                listOf(VendorVault("vault-1", "CUSTOMER:1"), VendorVault("vault-2", "CUSTOMER:1")),
            )
        }.isInstanceOf(ConflictException::class.java)
    }
}
