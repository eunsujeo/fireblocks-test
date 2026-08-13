package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import com.whatto.bcm.domain.sweep.SweepExecutionStage
import com.whatto.bcm.domain.sweep.SweepTarget
import com.whatto.bcm.domain.sweep.SweepTargetRepository
import com.whatto.bcm.domain.sweep.SweepTransactionStatusRepository
import com.whatto.bcm.domain.vendor.WalletVendorPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

data class SweepCandidate(
    val target: SweepTarget,
    val sourceVaultId: String,
    val omnibusAccountId: String,
    val omnibusVaultId: String,
    val vendorAssetId: String,
    val amount: String,
)

interface SweepCandidateSelector {
    fun selectCandidates(): List<SweepCandidate>
}

@Service
class SweepCandidateSelectionService(
    private val sweepTargets: SweepTargetRepository,
    private val accounts: AccountRepository,
    private val assetMappings: VendorAssetMappingRepository,
    private val wallet: WalletVendorPort,
    private val transactionRunner: TransactionRunner,
    private val sweepTransactionStatuses: SweepTransactionStatusRepository,
    private val executionAlerts: SweepExecutionAlertPort,
    private val properties: SweepProperties,
) : SweepCandidateSelector {
    override fun selectCandidates(): List<SweepCandidate> {
        val omnibus = requiredOmnibusAccount()
        val candidates =
            sweepTargets
                .findPending(properties.scanLimit)
                .mapNotNull { target ->
                    try {
                        selectIfEligible(target, omnibus)
                    } catch (exception: RuntimeException) {
                        executionAlerts.alert(
                            SweepExecutionAlert(SweepExecutionStage.SELECTION, target.key, exception),
                        )
                        null
                    }
                }
        return candidates
            .sortedWith(
                compareByDescending<SweepCandidate> { BigDecimal(it.amount) }
                    .thenBy { it.target.registeredAt }
                    .thenBy { it.target.accountId }
                    .thenBy { it.target.network }
                    .thenBy { it.target.symbol },
            ).take(properties.batchSize)
    }

    private fun selectIfEligible(
        target: SweepTarget,
        omnibus: Account,
    ): SweepCandidate? {
        val minimumAmount = properties.minimumAmount(target.network, target.symbol)
        if (minimumAmount == null) {
            logger.warn(
                "sweep target skipped because minimum amount is not configured: accountId={} network={} symbol={}",
                target.accountId,
                target.network,
                target.symbol,
            )
            return null
        }
        val source =
            checkNotNull(accounts.findByAccountId(target.accountId)) {
                "sweep source account not found: accountId=${target.accountId}"
            }
        check(source.accountType == AccountType.CUSTOMER) {
            "sweep source account must be CUSTOMER: accountId=${target.accountId}"
        }
        check(source.vendorVaultId != omnibus.vendorVaultId) {
            "sweep source and omnibus vault must differ: accountId=${target.accountId}"
        }
        val mapping =
            checkNotNull(assetMappings.find(target.network, target.symbol)) {
                "sweep asset mapping not found: network=${target.network} symbol=${target.symbol}"
            }
        val finalizedBeforeBalance = sweepTransactionStatuses.finalizedDepositIds(target.key)
        val available = BigDecimal(wallet.balanceOf(source.vendorVaultId, mapping.vendorAssetId).available)
        check(available.signum() >= 0) {
            "vendor available balance must not be negative: accountId=${target.accountId}"
        }
        if (available < minimumAmount) {
            deleteIfStillBelow(target, finalizedBeforeBalance)
            return null
        }
        return candidate(target, source, omnibus, mapping.vendorAssetId, available.stripTrailingZeros().toPlainString())
    }

    private fun candidate(
        target: SweepTarget,
        source: Account,
        omnibus: Account,
        vendorAssetId: String,
        amount: String,
    ): SweepCandidate =
        SweepCandidate(
            target = target,
            sourceVaultId = source.vendorVaultId,
            omnibusAccountId = omnibus.accountId,
            omnibusVaultId = omnibus.vendorVaultId,
            vendorAssetId = vendorAssetId,
            amount = amount,
        )

    private fun deleteIfStillBelow(
        target: SweepTarget,
        finalizedBeforeBalance: Set<String>,
    ) {
        transactionRunner.run {
            sweepTargets.findPendingForUpdate(target.key) ?: return@run
            val finalizedAfterBalance = sweepTransactionStatuses.finalizedDepositIds(target.key)
            if (finalizedAfterBalance.any { it !in finalizedBeforeBalance }) return@run
            sweepTargets.deletePending(target.key)
        }
    }

    private fun requiredOmnibusAccount(): Account {
        check(properties.omnibusAccountId.isNotBlank()) { "sweep omnibusAccountId must be configured" }
        val account =
            checkNotNull(accounts.findByAccountId(properties.omnibusAccountId)) {
                "sweep omnibus account not found: accountId=${properties.omnibusAccountId}"
            }
        check(account.accountType == AccountType.SYSTEM) {
            "sweep omnibus account must be SYSTEM: accountId=${properties.omnibusAccountId}"
        }
        return account
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SweepCandidateSelectionService::class.java)
    }
}
