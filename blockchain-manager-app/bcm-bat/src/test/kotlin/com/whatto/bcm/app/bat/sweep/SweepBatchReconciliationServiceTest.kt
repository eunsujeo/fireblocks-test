package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepBatchReceipt
import com.whatto.bcm.domain.sweep.SweepBatchReceiptPort
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItem
import com.whatto.bcm.domain.sweep.SweepItemReconciliation
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.domain.sweep.SweepLegObservation
import com.whatto.bcm.domain.sweep.SweepTarget
import com.whatto.bcm.domain.sweep.SweepTargetKey
import com.whatto.bcm.domain.sweep.SweepTargetRepository
import com.whatto.bcm.domain.sweep.SweepTransactionStatusRepository
import com.whatto.bcm.domain.vendor.VendorBalance
import com.whatto.bcm.domain.vendor.VendorNetworkRecord
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.domain.vendor.WalletVendorPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SweepBatchReconciliationServiceTest {
    @Test
    fun `부분 성공 receipt와 network records를 대조해 성공 target은 정리하고 실패 target은 재선정한다`() {
        val fixture = fixture()

        val result = fixture.service.runOnce()

        assertThat(result).isEqualTo(SweepReconciliationCycleResult(1, 0, 0))
        assertThat(fixture.executions.execution.status).isEqualTo(SweepExecutionStatus.PARTIAL)
        assertThat(fixture.executions.execution.actualTotalAmount).isEqualTo("3")
        assertThat(fixture.executions.items.map { it.status }).containsExactly(SweepItemStatus.SUCCEEDED, SweepItemStatus.FAILED)
        assertThat(fixture.executions.items.map { it.actualAmount }).containsExactly("3", "0")
        assertThat(fixture.executions.items[1].failureCode).isEqualTo(FAILURE_CODE)
        assertThat(fixture.targets.row(ACCOUNT_A)).isNull()
        assertThat(fixture.targets.row(ACCOUNT_B)?.activeSweepExecutionId).isNull()
    }

    @Test
    fun `SweepLeg가 빠지면 성공을 추측하지 않고 실행과 target을 RECONCILING에 보존해 경보한다`() {
        val fixture = fixture(legs = listOf(successLeg()))

        val result = fixture.service.runOnce()

        assertThat(result).isEqualTo(SweepReconciliationCycleResult(0, 0, 1))
        assertThat(fixture.executions.execution.status).isEqualTo(SweepExecutionStatus.RECONCILING)
        assertThat(fixture.executions.items).allMatch { it.status == SweepItemStatus.READY }
        assertThat(fixture.targets.row(ACCOUNT_A)?.activeSweepExecutionId).isEqualTo(EXECUTION_ID)
        assertThat(fixture.alerts.single().cause).hasMessageContaining("SweepLeg count")
    }

    @Test
    fun `잔액 관찰 중 새 확정 입금이 생기면 성공 target도 삭제하지 않고 다음 회차로 되돌린다`() {
        val statuses = ChangingFinalizedStatuses()
        val fixture = fixture(statuses = statuses)

        fixture.service.runOnce()

        assertThat(fixture.targets.row(ACCOUNT_A)).isNotNull()
        assertThat(fixture.targets.row(ACCOUNT_A)?.activeSweepExecutionId).isNull()
    }

    @Test
    fun `최상위 벤더 거래가 실패하면 receipt를 기다리지 않고 항목을 RETRY로 돌린다`() {
        val fixture = fixture(vendorStatus = "FAILED")

        val result = fixture.service.runOnce()

        assertThat(result).isEqualTo(SweepReconciliationCycleResult(1, 0, 0))
        assertThat(fixture.executions.execution.status).isEqualTo(SweepExecutionStatus.FAILED)
        assertThat(fixture.executions.items).allMatch { it.status == SweepItemStatus.RETRY }
        assertThat(fixture.targets.rows()).allMatch { it.activeSweepExecutionId == null }
    }

    @Test
    fun `벤더 최상위 해시가 없어도 저장된 실행 해시와 network record가 일치하면 대사를 완료한다`() {
        val fixture = fixture(vendorTransactionHash = null)

        val result = fixture.service.runOnce()

        assertThat(result).isEqualTo(SweepReconciliationCycleResult(1, 0, 0))
        assertThat(fixture.executions.execution.status).isEqualTo(SweepExecutionStatus.PARTIAL)
        assertThat(fixture.alerts).isEmpty()
    }

    private fun fixture(
        legs: List<SweepLegObservation> = listOf(successLeg(), failedLeg()),
        statuses: SweepTransactionStatusRepository = StableFinalizedStatuses,
        vendorStatus: String = "COMPLETED",
        vendorTransactionHash: String? = TX_HASH,
    ): ReconciliationFixture {
        val targets = FakeReconciliationTargets()
        val executions = FakeReconciliationExecutions(targets)
        val vendor = FakeReconciliationVendor(vendorTransaction(vendorStatus, vendorTransactionHash))
        val receiptPort = FixedBatchReceiptPort(SweepBatchReceipt(true, legs))
        val erc20 = ReconciliationErc20
        val mappings = ReconciliationMappings(mapping())
        val accounts = ReconciliationAccounts(account(ACCOUNT_A, "vault-a"), account(ACCOUNT_B, "vault-b"))
        val wallet = ReconciliationWallet
        val alerts = mutableListOf<SweepExecutionAlert>()
        val service =
            SweepBatchReconciliationService(
                executions,
                vendor,
                receiptPort,
                erc20,
                mappings,
                accounts,
                wallet,
                targets,
                statuses,
                ImmediateReconciliationTransactionRunner,
                alerts::add,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                SweepProperties(
                    omnibusAccountId = OMNIBUS_ID,
                    thresholds = listOf(SweepAssetThreshold(NETWORK, SYMBOL, "1", "100")),
                ),
            )
        return ReconciliationFixture(service, executions, targets, alerts)
    }

    private fun vendorTransaction(
        status: String,
        transactionHash: String?,
    ) = VendorTransaction(
        transactionId = VENDOR_TX_ID,
        externalTransactionId = EXTERNAL_ID,
        vendorAssetId = "ETH",
        rawStatus = status,
        subStatus = null,
        transactionHash = transactionHash,
        source = VendorTransactionPeer("VAULT_ACCOUNT", "operator-vault"),
        destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
        sourceAddress = null,
        destinationAddress = SWEEPER,
        amount = "0",
        confirmationCount = 1,
        createdAtEpochMillis = 1,
        lastUpdatedEpochMillis = 2,
        networkRecords =
            listOf(
                VendorNetworkRecord(
                    type = "CONTRACT_CALL",
                    source = VendorTransactionPeer("VAULT_ACCOUNT", "vault-a"),
                    destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
                    destinationAddress = OMNIBUS_ADDRESS,
                    transactionHash = TX_HASH,
                    vendorAssetId = VENDOR_ASSET,
                    netAmount = "3",
                    dropped = false,
                ),
            ),
    )

    private fun successLeg() = SweepLegObservation(EXECUTION_ID, 1, OWNER_A, "3", "3", true, ZERO_CODE, 10)

    private fun failedLeg() = SweepLegObservation(EXECUTION_ID, 2, OWNER_B, "12.5", "0", false, FAILURE_CODE, 11)

    private fun account(
        id: String,
        vaultId: String,
    ) = Account(id, AccountType.CUSTOMER, "ref-$id", vaultId, "20260812090000")

    private fun mapping() = VendorAssetMapping(NETWORK, SYMBOL, VENDOR_ASSET, TOKEN, "20260812090000", "SYSTEM", "9999")

    private data class ReconciliationFixture(
        val service: SweepBatchReconciliationService,
        val executions: FakeReconciliationExecutions,
        val targets: FakeReconciliationTargets,
        val alerts: List<SweepExecutionAlert>,
    )

    private companion object {
        const val NETWORK = "ETHEREUM"
        const val SYMBOL = "USDC"
        const val ACCOUNT_A = "customer-a"
        const val ACCOUNT_B = "customer-b"
        const val OWNER_A = "0x2222222222222222222222222222222222222222"
        const val OWNER_B = "0x3333333333333333333333333333333333333333"
        const val SWEEPER = "0x4444444444444444444444444444444444444444"
        const val OMNIBUS_ADDRESS = "0x5555555555555555555555555555555555555555"
        const val TOKEN = "0x1111111111111111111111111111111111111111"
        const val OMNIBUS_ID = "omnibus-1"
        const val VENDOR_ASSET = "USDC_ERC20"
        const val EXECUTION_ID = "01987654-3210-7abc-8def-0123456789ab"
        const val EXTERNAL_ID = "swp-01987654"
        const val VENDOR_TX_ID = "vendor-batch-1"
        const val TX_HASH = "0xabc"
        val ZERO_CODE = "0".repeat(64)
        val FAILURE_CODE = "1".padStart(64, '0')
    }
}

private class FakeReconciliationExecutions(
    private val targets: FakeReconciliationTargets,
) : SweepExecutionRepository {
    var execution = reconciliationExecution()
    var items = reconciliationItems()

    override fun createAndClaim(
        execution: SweepExecution,
        items: List<SweepItem>,
    ) = error("not used")

    override fun findById(executionId: String): SweepExecution? = execution.takeIf { it.executionId == executionId }

    override fun findByIdForUpdate(executionId: String): SweepExecution? = findById(executionId)

    override fun findItems(executionId: String): List<SweepItem> = items.takeIf { executionId == execution.executionId }.orEmpty()

    override fun findPendingSubmission(operatorAccountId: String): SweepExecution? = null

    override fun findReconciling(limit: Int): List<SweepExecution> =
        listOf(execution).filter { it.status == SweepExecutionStatus.RECONCILING }.take(limit)

    override fun markSubmitting(executionId: String): SweepExecution = error("not used")

    override fun markSubmitted(
        executionId: String,
        vendorTransactionId: String,
    ): SweepExecution = error("not used")

    override fun markReconciling(
        executionId: String,
        vendorTransactionId: String,
        transactionHash: String?,
    ): SweepExecution = error("not used")

    override fun completeReconciliation(
        executionId: String,
        items: List<SweepItemReconciliation>,
        status: SweepExecutionStatus,
        actualTotalAmount: String,
        finishedAt: String,
    ): SweepExecution {
        val bySequence = items.associateBy { it.sequence }
        this.items =
            this.items.map { item ->
                val result = requireNotNull(bySequence[item.sequence])
                item.copy(
                    actualAmount = result.actualAmount,
                    status = result.status,
                    failureCode = result.failureCode,
                    logIndex = result.logIndex,
                )
            }
        execution = execution.copy(status = status, actualTotalAmount = actualTotalAmount, finishedAt = finishedAt)
        return execution
    }

    override fun markFailedAndRelease(
        executionId: String,
        finishedAt: String,
    ): SweepExecution {
        execution = execution.copy(status = SweepExecutionStatus.FAILED, finishedAt = finishedAt)
        items = items.map { it.copy(status = SweepItemStatus.RETRY) }
        targets.releaseAll(executionId)
        return execution
    }
}

private class FakeReconciliationTargets : SweepTargetRepository {
    private val rows =
        reconciliationItems()
            .associate { item ->
                item.accountId to
                    SweepTarget(
                        item.accountId,
                        "ETHEREUM",
                        "USDC",
                        "20260812090000",
                        item.executionId,
                        item.sequence,
                        1,
                        "20260812150000",
                    )
            }.toMutableMap()

    fun row(accountId: String): SweepTarget? = rows[accountId]

    fun rows(): List<SweepTarget> = rows.values.toList()

    fun releaseAll(executionId: String) {
        rows.replaceAll { _, target ->
            if (target.activeSweepExecutionId == executionId) {
                target.copy(activeSweepExecutionId = null, activeItemSequence = null)
            } else {
                target
            }
        }
    }

    override fun insertIfAbsent(target: SweepTarget): Boolean = error("not used")

    override fun findByKey(key: SweepTargetKey): SweepTarget? = rows[key.accountId]

    override fun findByKeyForUpdate(key: SweepTargetKey): SweepTarget? = findByKey(key)

    override fun findPending(limit: Int): List<SweepTarget> = error("not used")

    override fun findPendingForUpdate(key: SweepTargetKey): SweepTarget? = error("not used")

    override fun releaseClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean {
        val current = rows[key.accountId] ?: return false
        if (current.activeSweepExecutionId != executionId || current.activeItemSequence != itemSequence) return false
        rows[key.accountId] = current.copy(activeSweepExecutionId = null, activeItemSequence = null)
        return true
    }

    override fun deleteClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean {
        val current = rows[key.accountId] ?: return false
        if (current.activeSweepExecutionId != executionId || current.activeItemSequence != itemSequence) return false
        rows.remove(key.accountId)
        return true
    }

    override fun deletePending(key: SweepTargetKey): Boolean = error("not used")
}

private class FakeReconciliationVendor(
    private val transaction: VendorTransaction,
) : VendorTransactionPort {
    override fun submitTransaction(request: VendorTransactionRequest): VendorTransactionSubmission = error("not used")

    override fun transaction(transactionId: String): VendorTransaction? = transaction.takeIf { it.transactionId == transactionId }

    override fun transactionByExternalTransactionId(externalTransactionId: String): VendorTransaction? = error("not used")

    override fun transactions(request: VendorTransactionPageRequest) = error("not used")
}

private object StableFinalizedStatuses : SweepTransactionStatusRepository {
    override fun finalizedDepositIds(key: SweepTargetKey): Set<String> = emptySet()
}

private class ChangingFinalizedStatuses : SweepTransactionStatusRepository {
    private var calls = 0

    override fun finalizedDepositIds(key: SweepTargetKey): Set<String> {
        calls += 1
        return if (calls == 1) emptySet() else setOf("new-deposit")
    }
}

private object ImmediateReconciliationTransactionRunner : TransactionRunner {
    override fun <T> run(block: () -> T): T = block()
}

private class FixedBatchReceiptPort(
    private val value: SweepBatchReceipt?,
) : SweepBatchReceiptPort {
    override fun receipt(
        network: String,
        transactionHash: String,
        sweepContractAddress: String,
        tokenDecimals: Int,
    ): SweepBatchReceipt? = value
}

private object ReconciliationErc20 : Erc20ContractPort {
    override fun decimals(
        network: String,
        tokenContractAddress: String,
    ): Int = 6

    override fun allowance(
        network: String,
        tokenContractAddress: String,
        ownerAddress: String,
        spenderAddress: String,
    ) = error("not used")

    override fun approvalCallData(
        spenderAddress: String,
        amount: String,
        decimals: Int,
    ) = error("not used")
}

private class ReconciliationMappings(
    private val mapping: VendorAssetMapping,
) : VendorAssetMappingRepository {
    override fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping? = mapping.takeIf { it.network == network && it.symbol == symbol }

    override fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping? = error("not used")

    override fun findAll(
        network: String?,
        symbol: String?,
    ): List<VendorAssetMapping> = error("not used")

    override fun existsByNetwork(network: String): Boolean = error("not used")

    override fun insert(mapping: VendorAssetMapping): VendorAssetMapping = error("not used")

    override fun delete(
        network: String,
        symbol: String,
    ) = error("not used")
}

private class ReconciliationAccounts(
    vararg accounts: Account,
) : AccountRepository {
    private val rows = accounts.associateBy { it.accountId }

    override fun insert(account: Account): Account = error("not used")

    override fun findByTypeAndRef(
        accountType: AccountType,
        ref: String,
    ): Account? = error("not used")

    override fun findByAccountId(accountId: String): Account? = rows[accountId]
}

private object ReconciliationWallet : WalletVendorPort {
    override fun createVault(
        name: String,
        idempotencyKey: String,
    ) = error("not used")

    override fun createDepositAddress(
        vaultId: String,
        assetSymbol: String,
        idempotencyKey: String,
    ) = error("not used")

    override fun balanceOf(
        vaultId: String,
        assetSymbol: String,
    ): VendorBalance = VendorBalance("0.5", "0.5", "0", "0", "0")
}

private fun reconciliationExecution() =
    SweepExecution(
        executionId = "01987654-3210-7abc-8def-0123456789ab",
        externalTransactionId = "swp-01987654",
        requestHash = "a".repeat(64),
        network = "ETHEREUM",
        symbol = "USDC",
        operatorAccountId = "operator-1",
        sweepContractAddress = "0x4444444444444444444444444444444444444444",
        status = SweepExecutionStatus.RECONCILING,
        itemCount = 2,
        requestedTotalAmount = "15.5",
        actualTotalAmount = null,
        gasless = true,
        vendorTransactionId = "vendor-batch-1",
        transactionHash = "0xabc",
        requestedAt = "20260812150000",
        finishedAt = null,
    )

private fun reconciliationItems() =
    listOf(
        SweepItem(
            "01987654-3210-7abc-8def-0123456789ab",
            1,
            "customer-a",
            "0x2222222222222222222222222222222222222222",
            "3",
            null,
            SweepItemStatus.READY,
            null,
            null,
        ),
        SweepItem(
            "01987654-3210-7abc-8def-0123456789ab",
            2,
            "customer-b",
            "0x3333333333333333333333333333333333333333",
            "12.5",
            null,
            SweepItemStatus.READY,
            null,
            null,
        ),
    )
