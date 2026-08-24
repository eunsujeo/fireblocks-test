package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletVendorPort
import org.springframework.stereotype.Service

@Service
class AdminVaultQueryService(
    private val accounts: AccountRepository,
    private val walletVendor: WalletVendorPort,
) {
    fun vaults(query: String?): List<AdminVaultSummary> {
        val vendorVaults = allVendorVaults()
        val vendorById = vendorVaults.associateBy(VendorVault::vaultId)
        val accountsByVendorId = accounts.findAll().associateBy { it.vendorVaultId }
        val managed =
            accountsByVendorId.values.map { account ->
                val vendor = vendorById[account.vendorVaultId]
                AdminVaultSummary(
                    reconciliationStatus =
                        if (vendor == null) {
                            AdminVaultReconciliationStatus.MISSING_IN_FIREBLOCKS
                        } else {
                            AdminVaultReconciliationStatus.MANAGED
                        },
                    accountId = account.accountId,
                    accountType = account.accountType.name,
                    ref = account.ref,
                    vendorVaultId = account.vendorVaultId,
                    vendorVaultName = vendor?.name,
                    walletCount = vendor?.walletCount,
                    registeredAt = account.registeredAt,
                )
            }
        val unmanaged =
            vendorVaults
                .filterNot { it.vaultId in accountsByVendorId }
                .map { vendor ->
                    AdminVaultSummary(
                        reconciliationStatus = AdminVaultReconciliationStatus.UNMANAGED,
                        accountId = null,
                        accountType = null,
                        ref = null,
                        vendorVaultId = vendor.vaultId,
                        vendorVaultName = vendor.name,
                        walletCount = vendor.walletCount,
                        registeredAt = null,
                    )
                }
        val normalized = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return (managed + unmanaged)
            .filter { normalized == null || it.matches(normalized) }
            .sortedWith(
                compareBy<AdminVaultSummary> { it.reconciliationStatus.sortOrder }
                    .thenBy { it.vendorVaultName }
                    .thenBy { it.vendorVaultId },
            )
    }

    private fun allVendorVaults(): List<VendorVault> {
        val results = mutableListOf<VendorVault>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = walletVendor.vaults(cursor)
            results += page.data
            cursor = page.next
            if (cursor != null && !seen.add(cursor)) throw VendorApiException("listVaultsPagination", null)
        } while (cursor != null)
        return results
    }

    private fun AdminVaultSummary.matches(query: String): Boolean =
        listOfNotNull(accountId, ref, vendorVaultId, vendorVaultName).any { query in it.lowercase() }
}

enum class AdminVaultReconciliationStatus(
    val sortOrder: Int,
) {
    MANAGED(0),
    UNMANAGED(1),
    MISSING_IN_FIREBLOCKS(2),
}

data class AdminVaultSummary(
    val reconciliationStatus: AdminVaultReconciliationStatus,
    val accountId: String?,
    val accountType: String?,
    val ref: String?,
    val vendorVaultId: String,
    val vendorVaultName: String?,
    val walletCount: Int?,
    val registeredAt: String?,
)
