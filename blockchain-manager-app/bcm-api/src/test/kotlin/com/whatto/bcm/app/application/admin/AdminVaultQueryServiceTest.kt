package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletVendorPort
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdminVaultQueryServiceTest {
    private val accounts = mockk<AccountRepository>()
    private val wallet = mockk<WalletVendorPort>()
    private val service = AdminVaultQueryService(accounts, wallet)

    @Test
    fun `Fireblocks 전체 페이지와 BCM 계정을 대조해 세 상태를 구분한다`() {
        every { wallet.vaults(null) } returns VendorPage(listOf(VendorVault("1", "customer-a", 2)), "next")
        every { wallet.vaults("next") } returns VendorPage(listOf(VendorVault("2", "orphan", 1)), null)
        every { accounts.findAll() } returns
            listOf(
                Account("acct-1", AccountType.CUSTOMER, "daw-1", "1", "20260824010000"),
                Account("acct-3", AccountType.SYSTEM, "omnibus", "3", "20260824010000"),
            )

        val result = service.vaults(null)

        assertThat(result.map { it.reconciliationStatus }).containsExactly(
            AdminVaultReconciliationStatus.MANAGED,
            AdminVaultReconciliationStatus.UNMANAGED,
            AdminVaultReconciliationStatus.MISSING_IN_FIREBLOCKS,
        )
        assertThat(result.first()).extracting("accountId", "vendorVaultName", "walletCount").containsExactly("acct-1", "customer-a", 2)
    }

    @Test
    fun `검색은 account id ref vault id와 이름을 대소문자 없이 찾는다`() {
        every { wallet.vaults(null) } returns VendorPage(listOf(VendorVault("77", "Treasury Main", 4)), null)
        every { accounts.findAll() } returns listOf(Account("acct-t", AccountType.SYSTEM, "desk-A", "77", "20260824010000"))

        assertThat(service.vaults("treasury")).hasSize(1)
        assertThat(service.vaults("DESK-a")).hasSize(1)
        assertThat(service.vaults("missing")).isEmpty()
    }
}
