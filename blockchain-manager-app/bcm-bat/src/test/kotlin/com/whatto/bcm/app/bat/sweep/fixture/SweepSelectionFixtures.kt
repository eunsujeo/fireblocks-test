package com.whatto.bcm.app.bat.sweep.fixture

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.sweep.SweepTarget
import com.whatto.bcm.domain.vendor.VendorBalance

object SweepSelectionFixtures {
    fun target(
        accountId: String = "customer-1",
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        registeredAt: String = "20260811090000",
        activeSweepExecutionId: String? = null,
        activeItemSequence: Int? = null,
    ) = SweepTarget(accountId, network, symbol, registeredAt, activeSweepExecutionId, activeItemSequence, 0, null)

    fun account(
        accountId: String,
        accountType: AccountType = AccountType.CUSTOMER,
        vendorVaultId: String = "vault-$accountId",
    ) = Account(accountId, accountType, "ref-$accountId", vendorVaultId, "20260811090000")

    fun mapping(
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        vendorAssetId: String = "USDC_ERC20",
    ) = VendorAssetMapping(network, symbol, vendorAssetId, "0xToken", "20260811090000", "SYSTEM", "9999")

    fun balance(available: String) = VendorBalance(available, available, "0", "0", "0")
}
