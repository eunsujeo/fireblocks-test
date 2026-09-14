package com.whatto.bcm.domain.account.fixture

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.account.AccountType

object AccountModelFixture {
    fun fixture(
        model: AccountModel = AccountModel.VAULT,
        vaultId: String? = "vault-test",
    ) = Account("acct_test", AccountType.CUSTOMER, "ref-test", vaultId, "20260914000000", model)
}
