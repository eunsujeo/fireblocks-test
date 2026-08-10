package com.whatto.bcm.app.application.account.fixture

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress

object AccountFixture {
    fun fixture(
        accountId: String = "acct_test_01",
        accountType: AccountType = AccountType.CUSTOMER,
        ref: String = "000123",
        vendorVaultId: String = "77",
        registeredAt: String = "20260805120000",
    ): Account =
        Account(
            accountId = accountId,
            accountType = accountType,
            ref = ref,
            vendorVaultId = vendorVaultId,
            registeredAt = registeredAt,
        )
}

object DepositAddressFixture {
    fun fixture(
        accountId: String = "acct_test_01",
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        address: String = "0xAb3C9",
        registeredAt: String = "20260805120000",
    ): DepositAddress =
        DepositAddress(
            accountId = accountId,
            network = network,
            symbol = symbol,
            address = address,
            registeredAt = registeredAt,
        )
}
