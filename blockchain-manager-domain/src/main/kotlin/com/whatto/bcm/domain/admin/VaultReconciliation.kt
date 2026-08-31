package com.whatto.bcm.domain.admin

import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.vendor.VendorVault

enum class VaultReconciliationStatus {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
}

enum class VaultReconciliationItemStatus {
    MANAGED,
    UNMANAGED,
    MISSING_IN_FIREBLOCKS,
}

data class VaultReconciliation(
    val runId: String,
    val query: String?,
    val status: VaultReconciliationStatus,
    val vendorPageCount: Int,
    val vendorVaultCount: Long,
    val resultCount: Long,
    val failureCode: String?,
    val requestedAt: String,
    val startedAt: String?,
    val finishedAt: String?,
    val vendorCursor: String? = null,
    val vendorPaginationDone: Boolean = false,
) {
    fun failureStatus(): VaultReconciliationStatus =
        if (vendorPageCount > 0) VaultReconciliationStatus.PARTIAL else VaultReconciliationStatus.FAILED
}

data class VaultReconciliationItem(
    val sequence: Long,
    val reconciliationStatus: VaultReconciliationItemStatus,
    val accountId: String?,
    val accountType: AccountType?,
    val ref: String?,
    val vendorVaultId: String,
    val vendorVaultName: String?,
    val walletCount: Int?,
    val registeredAt: String?,
)

data class VaultReconciliationPage(
    val run: VaultReconciliation,
    val items: List<VaultReconciliationItem>,
    val nextSequence: Long?,
)

class VaultReconciliationFailureException(
    val failureCode: String,
) : RuntimeException("vault reconciliation failed: code=$failureCode")

interface VaultReconciliationRepository {
    fun create(run: VaultReconciliation): VaultReconciliation

    fun claim(
        runId: String,
        claimId: String,
        claimedAt: String,
        expiresAt: String,
    ): Boolean

    fun renewClaim(
        runId: String,
        claimId: String,
        renewedAt: String,
        expiresAt: String,
    ): Boolean

    /** ACCEPTED 실행의 계정 snapshot을 고정하고 RUNNING으로 전이한다. 재호출은 snapshot을 변경하지 않는다. */
    fun startWithAccountSnapshot(
        runId: String,
        claimId: String,
        startedAt: String,
    ): Boolean

    fun hasDuplicateVendorVaultMapping(runId: String): Boolean

    /** 현재 cursor가 [cursor]일 때만 page와 다음 cursor를 한 트랜잭션으로 기록한다. */
    fun recordVendorPage(
        runId: String,
        claimId: String,
        cursor: String?,
        nextCursor: String?,
        vaults: List<VendorVault>,
        recordedAt: String,
    ): Boolean

    fun complete(
        runId: String,
        claimId: String,
        finishedAt: String,
    ): Boolean

    fun fail(
        runId: String,
        claimId: String,
        failureCode: String,
        finishedAt: String,
    ): Boolean

    fun failAccepted(
        runId: String,
        failureCode: String,
        finishedAt: String,
    )

    fun find(runId: String): VaultReconciliation?

    fun findPage(
        runId: String,
        afterSequence: Long?,
        limit: Int,
    ): VaultReconciliationPage?

    fun findResumable(): List<VaultReconciliation> = emptyList()
}
