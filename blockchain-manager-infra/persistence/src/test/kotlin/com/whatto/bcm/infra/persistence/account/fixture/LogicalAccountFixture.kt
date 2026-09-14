package com.whatto.bcm.infra.persistence.account.fixture

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.admin.VaultReconciliation
import com.whatto.bcm.domain.admin.VaultReconciliationStatus
import com.whatto.bcm.domain.provider.ProviderOrigin
import java.util.UUID

object LogicalAccountFixture {
    fun origin() = ProviderOrigin("logical-origin", "dfns", "dfns", "logical-instance", "logical-org", "TESTNET")

    fun account(): Account {
        val id = UUID.randomUUID().toString()
        return Account("acct_$id", AccountType.CUSTOMER, "logical-$id", null, "20260914000000", AccountModel.LOGICAL)
    }

    fun vaultRun() =
        VaultReconciliation(
            runId = "logical-vault-run",
            query = null,
            status = VaultReconciliationStatus.ACCEPTED,
            vendorPageCount = 0,
            vendorVaultCount = 0,
            resultCount = 0,
            failureCode = null,
            requestedAt = "20260914000000",
            startedAt = null,
            finishedAt = null,
        )
}
