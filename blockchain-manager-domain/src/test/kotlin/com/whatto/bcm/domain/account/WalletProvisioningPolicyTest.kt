package com.whatto.bcm.domain.account

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletCreationPolicy
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WalletProvisioningPolicyTest {
    private val policy = WalletProvisioningPolicy(WalletCreationPolicy { _, _, _, _ -> error("후보 검사에서 생성 판정을 호출하면 안 된다") })

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
