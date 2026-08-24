package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.app.bat.sweep.fixture.SweepRuntimeFixtures
import com.whatto.bcm.app.bat.sweep.fixture.SweepSelectionFixtures.account
import com.whatto.bcm.app.bat.sweep.fixture.SweepSelectionFixtures.balance
import com.whatto.bcm.app.bat.sweep.fixture.SweepSelectionFixtures.mapping
import com.whatto.bcm.app.bat.sweep.fixture.SweepSelectionFixtures.target
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import com.whatto.bcm.domain.sweep.SweepTarget
import com.whatto.bcm.domain.sweep.SweepTargetKey
import com.whatto.bcm.domain.sweep.SweepTargetRepository
import com.whatto.bcm.domain.sweep.SweepTransactionStatusRepository
import com.whatto.bcm.domain.vendor.VendorBalance
import com.whatto.bcm.domain.vendor.VendorDepositAddress
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletVendorPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SweepCandidateSelectionServiceTest {
    @Test
    fun `최소 금액 이상을 잔액 내림차순으로 M개 고르고 미달 대상은 삭제한다`() {
        val targets =
            FakeSweepTargets(
                target("customer-1", registeredAt = "20260811090100"),
                target("customer-2", registeredAt = "20260811090200"),
                target("customer-3", registeredAt = "20260811090300"),
                target("customer-4", registeredAt = "20260811090400"),
                target("customer-active", activeSweepExecutionId = "swx-active", activeItemSequence = 1),
            )
        val accounts =
            FakeAccounts(
                account("omnibus", AccountType.SYSTEM, "vault-omnibus"),
                account("customer-1"),
                account("customer-2"),
                account("customer-3"),
                account("customer-4"),
                account("customer-active"),
            )
        val mappings = FakeMappings(mapping())
        val wallet =
            FakeWallet(
                mapOf(
                    ("vault-customer-1" to "USDC_ERC20") to balance("10.000"),
                    ("vault-customer-2" to "USDC_ERC20") to balance("25"),
                    ("vault-customer-3" to "USDC_ERC20") to balance("9.999"),
                    ("vault-customer-4" to "USDC_ERC20") to balance("30"),
                ),
            )
        val service = service(targets, accounts, mappings, wallet, properties(batchSize = 2))

        val selected = service.selectCandidates()

        assertThat(selected.map { it.target.accountId }).containsExactly("customer-4", "customer-2")
        assertThat(selected.map { it.amount }).containsExactly("30", "25")
        assertThat(selected).allSatisfy { assertThat(it.omnibusVaultId).isEqualTo("vault-omnibus") }
        assertThat(targets.findByKey(target("customer-3").key)).isNull()
        assertThat(targets.findByKey(target("customer-1").key)).isNotNull()
        assertThat(wallet.calls).doesNotContain("vault-customer-active" to "USDC_ERC20")
    }

    @Test
    fun `최소 금액 설정이 없는 자산은 벤더 호출과 삭제 없이 보류한다`() {
        val unsupported = target(network = "BASE", symbol = "USDC")
        val targets = FakeSweepTargets(unsupported)
        val accounts = FakeAccounts(account("omnibus", AccountType.SYSTEM), account("customer-1"))
        val wallet = FakeWallet(emptyMap())
        val service = service(targets, accounts, FakeMappings(mapping()), wallet, properties())

        val selected = service.selectCandidates()

        assertThat(selected).isEmpty()
        assertThat(wallet.calls).isEmpty()
        assertThat(targets.findByKey(unsupported.key)).isEqualTo(unsupported)
    }

    @Test
    fun `옴니버스 계정이 SYSTEM이 아니면 잔액을 조회하지 않고 실패한다`() {
        val targets = FakeSweepTargets(target())
        val accounts = FakeAccounts(account("omnibus", AccountType.CUSTOMER), account("customer-1"))
        val wallet = FakeWallet(emptyMap())
        val service = service(targets, accounts, FakeMappings(mapping()), wallet, properties())

        assertThatThrownBy { service.selectCandidates() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("omnibus")
        assertThat(wallet.calls).isEmpty()
    }

    @Test
    fun `한 대상의 데이터 오류는 경보하고 다음 대상을 계속 선정한다`() {
        val broken = target("missing-account", registeredAt = "20260811090100")
        val healthy = target("customer-1", registeredAt = "20260811090200")
        val targets = FakeSweepTargets(broken, healthy)
        val accounts = FakeAccounts(account("omnibus", AccountType.SYSTEM), account("customer-1"))
        val wallet = FakeWallet(mapOf(("vault-customer-1" to "USDC_ERC20") to balance("12")))
        val alerts = mutableListOf<SweepExecutionAlert>()
        val service =
            service(
                targets,
                accounts,
                FakeMappings(mapping()),
                wallet,
                properties(),
                SweepExecutionAlertPort(alerts::add),
            )

        val selected = service.selectCandidates()

        assertThat(selected.map { it.target.accountId }).containsExactly("customer-1")
        assertThat(alerts.map { it.target }).containsExactly(broken.key)
    }

    @Test
    fun `제한 출시 대상이 아닌 네트워크는 벤더 잔액 조회 전에 제외한다`() {
        val ethereum = target("customer-eth", network = "ETHEREUM")
        val base = target("customer-base", network = "BASE")
        val targets = FakeSweepTargets(ethereum, base)
        val accounts =
            FakeAccounts(
                account("omnibus", AccountType.SYSTEM, "vault-omnibus"),
                account("customer-eth"),
                account("customer-base"),
            )
        val wallet =
            FakeWallet(
                mapOf(
                    ("vault-customer-eth" to "USDC_ERC20") to balance("12"),
                    ("vault-customer-base" to "USDC_BASE") to balance("30"),
                ),
            )
        val service =
            service(
                targets,
                accounts,
                FakeMappings(mapping(), mapping(network = "BASE", vendorAssetId = "USDC_BASE")),
                wallet,
                properties(enabledNetworks = setOf("ETHEREUM")),
            )

        assertThat(service.selectCandidates().map { it.target.network }).containsExactly("ETHEREUM")
        assertThat(wallet.calls).containsExactly("vault-customer-eth" to "USDC_ERC20")
    }

    @Test
    fun `활성 정책의 건별 총액 상한 안에서만 후보를 선정한다`() {
        val targets =
            FakeSweepTargets(
                target("customer-over", registeredAt = "20260811090100"),
                target("customer-70", registeredAt = "20260811090200"),
                target("customer-40", registeredAt = "20260811090300"),
                target("customer-30", registeredAt = "20260811090400"),
            )
        val accounts =
            FakeAccounts(
                account("omnibus", AccountType.SYSTEM, "vault-omnibus"),
                account("customer-over"),
                account("customer-70"),
                account("customer-40"),
                account("customer-30"),
            )
        val wallet =
            FakeWallet(
                mapOf(
                    ("vault-customer-over" to "USDC_ERC20") to balance("120"),
                    ("vault-customer-70" to "USDC_ERC20") to balance("70"),
                    ("vault-customer-40" to "USDC_ERC20") to balance("40"),
                    ("vault-customer-30" to "USDC_ERC20") to balance("30"),
                ),
            )
        val alerts = mutableListOf<SweepExecutionAlert>()
        val service =
            service(
                targets,
                accounts,
                FakeMappings(mapping()),
                wallet,
                properties(),
                executionAlerts = SweepExecutionAlertPort(alerts::add),
                runtimeGuard =
                    SweepRuntimeFixtures.guard(
                        SweepRuntimeFixtures.context(
                            minimumAmount = "10",
                            batchSize = 10,
                            itemAmountCap = "100",
                            batchAmountCap = "100",
                        ),
                    ),
            )

        val selected = service.selectCandidates()

        assertThat(selected.map { it.target.accountId }).containsExactly("customer-70", "customer-30")
        assertThat(alerts.map { it.target.accountId }).containsExactly("customer-over")
    }

    @Test
    fun `조회 상한은 전송 상한보다 작을 수 없다`() {
        assertThatThrownBy { properties(batchSize = 3, scanLimit = 2) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("scanLimit")
    }

    private fun properties(
        batchSize: Int = 10,
        scanLimit: Int = 100,
        enabledNetworks: Set<String> = setOf("ETHEREUM", "BASE"),
    ) = SweepProperties(
        omnibusAccountId = "omnibus",
        batchSize = batchSize,
        scanLimit = scanLimit,
        thresholds = listOf(SweepAssetThreshold("ETHEREUM", "USDC", "10")),
        security = SweepSecurityProperties(batchSubmissionEnabledNetworks = enabledNetworks),
    )

    private fun service(
        targets: FakeSweepTargets,
        accounts: FakeAccounts,
        mappings: FakeMappings,
        wallet: FakeWallet,
        properties: SweepProperties,
        executionAlerts: SweepExecutionAlertPort = SweepExecutionAlertPort { },
        statuses: SweepTransactionStatusRepository = SelectionSweepTransactionStatuses(),
        runtimeGuard: SweepRuntimeGuard =
            SweepRuntimeFixtures.guard(
                SweepRuntimeFixtures.context(minimumAmount = "10", batchSize = properties.batchSize),
            ),
    ) = SweepCandidateSelectionService(
        targets,
        accounts,
        mappings,
        wallet,
        ImmediateTransactionRunner,
        statuses,
        executionAlerts,
        properties,
        runtimeGuard,
    )
}

private class SelectionSweepTransactionStatuses(
    private val finalizedIds: Set<String> = emptySet(),
) : SweepTransactionStatusRepository {
    override fun finalizedDepositIds(key: SweepTargetKey): Set<String> = finalizedIds
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> run(block: () -> T): T = block()
}

private class FakeSweepTargets(
    vararg initial: SweepTarget,
) : SweepTargetRepository {
    private val rows = initial.associateByTo(linkedMapOf()) { it.key }

    override fun insertIfAbsent(target: SweepTarget): Boolean = rows.putIfAbsent(target.key, target) == null

    override fun findByKey(key: SweepTargetKey): SweepTarget? = rows[key]

    override fun findByKeyForUpdate(key: SweepTargetKey): SweepTarget? = rows[key]

    override fun findPending(
        networks: Set<String>,
        limit: Int,
    ): List<SweepTarget> =
        rows.values
            .filter { it.activeSweepExecutionId == null && it.network in networks }
            .sortedBy { it.registeredAt }
            .take(limit)

    override fun findPendingForUpdate(key: SweepTargetKey): SweepTarget? = rows[key]?.takeIf { it.activeSweepExecutionId == null }

    override fun releaseClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean = error("not used")

    override fun deleteClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean = error("not used")

    override fun deletePending(key: SweepTargetKey): Boolean {
        val target = rows[key] ?: return false
        return target.activeSweepExecutionId == null && rows.remove(key) != null
    }
}

private class FakeAccounts(
    vararg accounts: Account,
) : AccountRepository {
    private val rows = accounts.associateBy { it.accountId }

    override fun insert(account: Account): Account = error("not used")

    override fun findByTypeAndRef(
        accountType: AccountType,
        ref: String,
    ): Account? = rows.values.firstOrNull { it.accountType == accountType && it.ref == ref }

    override fun findByAccountId(accountId: String): Account? = rows[accountId]
}

private class FakeMappings(
    vararg mappings: VendorAssetMapping,
) : VendorAssetMappingRepository {
    private val rows = mappings.associateBy { it.network to it.symbol }

    override fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping? = rows[network to symbol]

    override fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping? =
        rows.values.firstOrNull { it.vendorAssetId == vendorAssetId }

    override fun findAll(
        network: String?,
        symbol: String?,
    ): List<VendorAssetMapping> = rows.values.toList()

    override fun existsByNetwork(network: String): Boolean = rows.keys.any { it.first == network }

    override fun insert(mapping: VendorAssetMapping): VendorAssetMapping = error("not used")

    override fun delete(
        network: String,
        symbol: String,
    ) = error("not used")
}

private class FakeWallet(
    private val balances: Map<Pair<String, String>, VendorBalance>,
) : WalletVendorPort {
    val calls = mutableListOf<Pair<String, String>>()

    override fun createVault(
        name: String,
        idempotencyKey: String,
    ): VendorVault = error("not used")

    override fun createDepositAddress(
        vaultId: String,
        assetSymbol: String,
        idempotencyKey: String,
    ): VendorDepositAddress = error("not used")

    override fun balanceOf(
        vaultId: String,
        assetSymbol: String,
    ): VendorBalance {
        calls += vaultId to assetSymbol
        return checkNotNull(balances[vaultId to assetSymbol])
    }
}
