package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.domain.sweep.SweepBatchCallItem
import com.whatto.bcm.domain.sweep.SweepBatchContractPort
import com.whatto.bcm.domain.sweep.SweepBatchReceipt
import com.whatto.bcm.domain.sweep.SweepBatchReceiptPort
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.domain.sweep.SweepLegObservation
import com.whatto.bcm.domain.sweep.SweepTarget
import com.whatto.bcm.domain.vendor.VendorBalance
import com.whatto.bcm.domain.vendor.VendorContractCall
import com.whatto.bcm.domain.vendor.VendorContractCallPort
import com.whatto.bcm.domain.vendor.VendorContractCallRequest
import com.whatto.bcm.domain.vendor.VendorDepositAddress
import com.whatto.bcm.domain.vendor.VendorNetworkRecord
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletVendorPort
import com.whatto.bcm.infra.persistence.config.SpringTransactionRunner
import com.whatto.bcm.infra.persistence.submission.SubmissionJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepAuthorizationJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepExecutionJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepTargetJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepTransactionStatusJdbcAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.HexFormat

@SpringBootTest(classes = [SweepLifecycleIntegrationTest.TestApplication::class])
@Import(
    SweepTargetJdbcAdapter::class,
    SweepAuthorizationJdbcAdapter::class,
    SweepExecutionJdbcAdapter::class,
    SweepTransactionStatusJdbcAdapter::class,
    SubmissionJdbcAdapter::class,
    SpringTransactionRunner::class,
)
class SweepLifecycleIntegrationTest : IntegrationTestSupport() {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class TestApplication

    @Autowired
    lateinit var targets: SweepTargetJdbcAdapter

    @Autowired
    lateinit var authorizations: SweepAuthorizationJdbcAdapter

    @Autowired
    lateinit var executions: SweepExecutionJdbcAdapter

    @Autowired
    lateinit var submissions: SubmissionJdbcAdapter

    @Autowired
    lateinit var transactionStatuses: SweepTransactionStatusJdbcAdapter

    @Autowired
    lateinit var transactionRunner: TransactionRunner

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var wallet: LifecycleWallet
    private lateinit var erc20: LifecycleErc20
    private lateinit var vendor: LifecycleVendor
    private lateinit var receipts: LifecycleReceipts
    private lateinit var allowanceService: SweepAllowancePreparationService
    private lateinit var executionService: SweepBatchExecutionService
    private lateinit var reconciliationService: SweepBatchReconciliationService

    @BeforeEach
    fun setUp() {
        clearTables()
        targets.insertIfAbsent(target(ACCOUNT_A))
        targets.insertIfAbsent(target(ACCOUNT_B))
        wallet = LifecycleWallet(mutableMapOf(VAULT_A to "20", VAULT_B to "30"))
        erc20 = LifecycleErc20(mutableMapOf(OWNER_A to "0", OWNER_B to "0"))
        vendor = LifecycleVendor()
        receipts = LifecycleReceipts()
        val accounts = LifecycleAccounts()
        val addresses = LifecycleAddresses()
        val mappings = LifecycleMappings
        val observedAlerts = mutableListOf<SweepExecutionAlert>()
        val alerts = SweepExecutionAlertPort { observedAlerts += it }
        val contractCalls =
            SweepContractCallSubmissionService(
                submissions,
                vendor,
                transactionRunner,
                CLOCK,
                PROPERTIES,
            )
        allowanceService =
            SweepAllowancePreparationService(
                authorizations,
                targets,
                accounts,
                addresses,
                mappings,
                erc20,
                contractCalls,
                transactionRunner,
                SequenceApprovalIds(),
                CLOCK,
                PROPERTIES,
            )
        val candidates =
            SweepCandidateSelectionService(
                targets,
                accounts,
                mappings,
                wallet,
                transactionRunner,
                transactionStatuses,
                alerts,
                PROPERTIES,
            )
        executionService =
            SweepBatchExecutionService(
                candidates,
                allowanceService,
                executions,
                accounts,
                addresses,
                mappings,
                LifecycleBatchContract,
                contractCalls,
                SweepExecutionIdGenerator { EXECUTION_ID },
                SweepExternalTransactionIdGenerator { BATCH_EXTERNAL_ID },
                alerts,
                CLOCK,
                PROPERTIES,
            )
        reconciliationService =
            SweepBatchReconciliationService(
                executions,
                vendor,
                receipts,
                erc20,
                mappings,
                accounts,
                wallet,
                targets,
                transactionStatuses,
                transactionRunner,
                alerts,
                CLOCK,
                PROPERTIES,
            )
    }

    @AfterEach
    fun tearDown() {
        clearTables()
    }

    @Test
    fun `approve 재관측부터 부분 성공 대사와 전체 allowance 회수까지 PostgreSQL 원장으로 이어진다`() {
        assertThat(executionService.runOnce()).isEqualTo(SweepBatchExecutionResult.WaitingForAllowance(2))
        assertThat(authorizations.findByKey(authorizationKey(ACCOUNT_A))?.status)
            .isEqualTo(SweepAuthorizationStatus.APPROVING)
        assertThat(authorizations.findByKey(authorizationKey(ACCOUNT_B))?.status)
            .isEqualTo(SweepAuthorizationStatus.APPROVING)
        assertThat(vendor.requests.filter { it.contractAddress == TOKEN_CONTRACT }).hasSize(2)
        assertThat(executions.findById(EXECUTION_ID)).isNull()

        erc20.allowances[OWNER_A] = "100"
        erc20.allowances[OWNER_B] = "100"

        assertThat(executionService.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, BATCH_EXTERNAL_ID, BATCH_VENDOR_TX_ID))
        assertThat(executions.findById(EXECUTION_ID)?.status).isEqualTo(SweepExecutionStatus.SUBMITTED)
        assertThat(executions.findItems(EXECUTION_ID).map { it.accountId }).containsExactly(ACCOUNT_A, ACCOUNT_B)
        assertThat(executions.findItems(EXECUTION_ID).map { it.sequence }).containsExactly(1, 2)
        assertThat(vendor.requests.filter { it.contractAddress == SWEEP_CONTRACT }).hasSize(1)
        assertThat(vendor.requests.single { it.contractAddress == SWEEP_CONTRACT }.callData)
            .isEqualTo(fakeCallData("batch:$EXECUTION_ID:$OWNER_A:20, $OWNER_B:30"))
        assertThat(vendor.requests).allMatch(VendorContractCallRequest::useGasless)

        assertThat(executionService.runOnce()).isEqualTo(SweepBatchExecutionResult.NoCandidates)
        assertThat(vendor.requests.filter { it.contractAddress == SWEEP_CONTRACT }).hasSize(1)

        executions.markReconciling(EXECUTION_ID, BATCH_VENDOR_TX_ID, TRANSACTION_HASH)
        vendor.transaction = completedBatchTransaction()
        receipts.value = partialReceipt()
        wallet.availableByVault[VAULT_A] = "0"
        wallet.availableByVault[VAULT_B] = "30"

        assertThat(reconciliationService.runOnce()).isEqualTo(SweepReconciliationCycleResult(1, 0, 0))
        assertThat(executions.findById(EXECUTION_ID))
            .extracting("status", "actualTotalAmount")
            .containsExactly(SweepExecutionStatus.PARTIAL, "20")
        assertThat(executions.findItems(EXECUTION_ID).map { it.status })
            .containsExactly(SweepItemStatus.SUCCEEDED, SweepItemStatus.FAILED)
        assertThat(targets.findByKey(target(ACCOUNT_A).key)).isNull()
        assertThat(targets.findByKey(target(ACCOUNT_B).key)?.activeSweepExecutionId).isNull()

        listOf(ACCOUNT_A, ACCOUNT_B).forEach { accountId ->
            assertThat(allowanceService.revoke(authorizationKey(accountId)))
                .isInstanceOf(SweepAllowancePreparationResult.Pending::class.java)
        }
        assertThat(vendor.requests.filter { it.callData == fakeCallData("approve:$SWEEP_CONTRACT:0:6") }).hasSize(2)
        erc20.allowances.replaceAll { _, _ -> "0" }
        listOf(ACCOUNT_A, ACCOUNT_B).forEach { accountId ->
            assertThat(allowanceService.revoke(authorizationKey(accountId)))
                .isEqualTo(SweepAllowancePreparationResult.Revoked)
            assertThat(authorizations.findByKey(authorizationKey(accountId))?.status)
                .isEqualTo(SweepAuthorizationStatus.REVOKED)
        }
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM bcm_sbmt_l WHERE tx_dvcd = ?",
                Long::class.java,
                SubmissionTransactionType.SWEEP_APPROVE.name,
            ),
        ).isEqualTo(4)
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM bcm_sbmt_l WHERE tx_dvcd = ?",
                Long::class.java,
                SubmissionTransactionType.SWEEP_BATCH.name,
            ),
        ).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bcm_outbox_l", Long::class.java)).isZero()
    }

    private fun completedBatchTransaction() =
        VendorTransaction(
            transactionId = BATCH_VENDOR_TX_ID,
            externalTransactionId = BATCH_EXTERNAL_ID,
            vendorAssetId = VENDOR_ASSET_ID,
            rawStatus = "COMPLETED",
            subStatus = null,
            transactionHash = TRANSACTION_HASH,
            source = VendorTransactionPeer("VAULT_ACCOUNT", OPERATOR_VAULT),
            destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
            sourceAddress = null,
            destinationAddress = SWEEP_CONTRACT,
            amount = "50",
            confirmationCount = 1,
            createdAtEpochMillis = 1,
            lastUpdatedEpochMillis = 2,
            networkRecords =
                listOf(
                    VendorNetworkRecord(
                        type = "TOKEN_TRANSFER",
                        source = VendorTransactionPeer("VAULT_ACCOUNT", VAULT_A),
                        destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
                        destinationAddress = OMNIBUS_ADDRESS,
                        transactionHash = TRANSACTION_HASH,
                        vendorAssetId = VENDOR_ASSET_ID,
                        netAmount = "20",
                        dropped = false,
                    ),
                ),
        )

    private fun partialReceipt() =
        SweepBatchReceipt(
            successful = true,
            legs =
                listOf(
                    SweepLegObservation(EXECUTION_ID, 1, OWNER_A, "20", "20", true, ZERO_FAILURE_CODE, 10),
                    SweepLegObservation(EXECUTION_ID, 2, OWNER_B, "30", "0", false, FAILURE_CODE, 11),
                ),
        )

    private fun target(accountId: String) = SweepTarget(accountId, NETWORK, SYMBOL, "20260813090000", null, null, 0, null)

    private fun authorizationKey(accountId: String) = SweepAuthorizationKey(accountId, NETWORK, SYMBOL, SWEEP_CONTRACT)

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_swp_trgt")
        jdbc.update("DELETE FROM bcm_swp_item_l")
        jdbc.update("DELETE FROM bcm_swp_exec_l")
        jdbc.update("DELETE FROM bcm_sbmt_l")
        jdbc.update("DELETE FROM bcm_swp_auth_m")
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneId.of("Asia/Seoul"))
        val PROPERTIES =
            SweepProperties(
                omnibusAccountId = OMNIBUS_ID,
                operatorAccountId = OPERATOR_ID,
                batchSize = 2,
                scanLimit = 2,
                thresholds = listOf(SweepAssetThreshold(NETWORK, SYMBOL, "10", "100")),
                contracts = listOf(SweepNetworkContract(NETWORK, SWEEP_CONTRACT)),
                security =
                    SweepSecurityProperties(
                        normalApprovalEnabled = true,
                        emergencyRevocationEnabled = true,
                        batchSubmissionEnabled = true,
                        tapApprovalPolicyVerified = true,
                        tapRevocationPolicyVerified = true,
                        tapBatchPolicyVerified = true,
                        callbackVerified = true,
                        universalGaslessVerified = true,
                        sweepContractVerified = true,
                    ),
            )
        const val NETWORK = "ETHEREUM"
        const val SYMBOL = "USDC"
        const val VENDOR_ASSET_ID = "USDC_ERC20"
        const val TOKEN_CONTRACT = "0x1111111111111111111111111111111111111111"
        const val OWNER_A = "0x2222222222222222222222222222222222222222"
        const val OWNER_B = "0x3333333333333333333333333333333333333333"
        const val SWEEP_CONTRACT = "0x4444444444444444444444444444444444444444"
        const val OMNIBUS_ADDRESS = "0x5555555555555555555555555555555555555555"
        const val ACCOUNT_A = "customer-a"
        const val ACCOUNT_B = "customer-b"
        const val VAULT_A = "vault-customer-a"
        const val VAULT_B = "vault-customer-b"
        const val OPERATOR_ID = "operator"
        const val OPERATOR_VAULT = "vault-operator"
        const val OMNIBUS_ID = "omnibus"
        const val OMNIBUS_VAULT = "vault-omnibus"
        const val EXECUTION_ID = "01987654-3210-7abc-8def-0123456789ab"
        const val BATCH_EXTERNAL_ID = "swp-lifecycle"
        const val BATCH_VENDOR_TX_ID = "vendor-batch"
        const val TRANSACTION_HASH = "0xbatch"
        const val ZERO_FAILURE_CODE = "0000000000000000000000000000000000000000000000000000000000000000"
        const val FAILURE_CODE = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }
}

private class SequenceApprovalIds : SweepApprovalExternalTransactionIdGenerator {
    private var sequence = 0

    override fun nextId(): String = "swa-lifecycle-${++sequence}"
}

private class LifecycleAccounts : AccountRepository {
    private val accounts =
        listOf(
            Account("customer-a", AccountType.CUSTOMER, "ref-a", "vault-customer-a", "20260813090000"),
            Account("customer-b", AccountType.CUSTOMER, "ref-b", "vault-customer-b", "20260813090000"),
            Account("operator", AccountType.SYSTEM, "operator", "vault-operator", "20260813090000"),
            Account("omnibus", AccountType.SYSTEM, "omnibus", "vault-omnibus", "20260813090000"),
        ).associateBy(Account::accountId)

    override fun insert(account: Account): Account = error("not used")

    override fun findByTypeAndRef(
        accountType: AccountType,
        ref: String,
    ): Account? = accounts.values.find { it.accountType == accountType && it.ref == ref }

    override fun findByAccountId(accountId: String): Account? = accounts[accountId]
}

private class LifecycleAddresses : DepositAddressRepository {
    private val addresses =
        mapOf(
            "customer-a" to
                DepositAddress(
                    "customer-a",
                    "ETHEREUM",
                    "USDC",
                    "0x2222222222222222222222222222222222222222",
                    "20260813090000",
                ),
            "customer-b" to
                DepositAddress(
                    "customer-b",
                    "ETHEREUM",
                    "USDC",
                    "0x3333333333333333333333333333333333333333",
                    "20260813090000",
                ),
        )

    override fun insert(depositAddress: DepositAddress): DepositAddress = error("not used")

    override fun find(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddress? = addresses[accountId]?.takeIf { it.network == network && it.symbol == symbol }

    override fun findAll(
        accountId: String,
        symbol: String?,
        network: String?,
    ): List<DepositAddress> = error("not used")

    override fun findByAddress(
        address: String,
        network: String,
    ): DepositAddress? = error("not used")

    override fun existsByAsset(
        network: String,
        symbol: String,
    ): Boolean = error("not used")
}

private object LifecycleMappings : VendorAssetMappingRepository {
    private val mapping =
        VendorAssetMapping(
            "ETHEREUM",
            "USDC",
            "USDC_ERC20",
            "0x1111111111111111111111111111111111111111",
            "20260813090000",
            "SYSTEM",
            "9999",
        )

    override fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping? = mapping.takeIf { it.network == network && it.symbol == symbol }

    override fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping? = mapping.takeIf { it.vendorAssetId == vendorAssetId }

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

private class LifecycleWallet(
    val availableByVault: MutableMap<String, String>,
) : WalletVendorPort {
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
        val available = checkNotNull(availableByVault[vaultId])
        return VendorBalance(available, available, "0", "0", "0")
    }
}

private class LifecycleErc20(
    val allowances: MutableMap<String, String>,
) : Erc20ContractPort {
    override fun decimals(
        network: String,
        tokenContractAddress: String,
    ): Int = 6

    override fun allowance(
        network: String,
        tokenContractAddress: String,
        ownerAddress: String,
        spenderAddress: String,
    ): SweepAllowanceObservation = SweepAllowanceObservation(checkNotNull(allowances[ownerAddress]), 6)

    override fun approvalCallData(
        spenderAddress: String,
        amount: String,
        decimals: Int,
    ): String = fakeCallData("approve:$spenderAddress:$amount:$decimals")
}

private object LifecycleBatchContract : SweepBatchContractPort {
    override fun batchSweepCallData(
        network: String,
        executionId: String,
        tokenContractAddress: String,
        items: List<SweepBatchCallItem>,
    ): String = fakeCallData("batch:$executionId:${items.joinToString { "${it.ownerAddress}:${it.amount}" }}")
}

private fun fakeCallData(value: String): String = "0x${HexFormat.of().formatHex(value.encodeToByteArray())}"

private class LifecycleReceipts : SweepBatchReceiptPort {
    var value: SweepBatchReceipt? = null

    override fun receipt(
        network: String,
        transactionHash: String,
        sweepContractAddress: String,
        tokenDecimals: Int,
    ): SweepBatchReceipt? = value
}

private class LifecycleVendor :
    VendorContractCallPort,
    VendorTransactionPort {
    val requests = mutableListOf<VendorContractCallRequest>()
    var transaction: VendorTransaction? = null

    override fun submitContractCall(request: VendorContractCallRequest): VendorTransactionSubmission {
        requests += request
        val transactionId =
            if (request.externalTransactionId ==
                "swp-lifecycle"
            ) {
                "vendor-batch"
            } else {
                "vendor-${request.externalTransactionId}"
            }
        return VendorTransactionSubmission.Accepted(transactionId)
    }

    override fun contractCallByExternalTransactionId(externalTransactionId: String): VendorContractCall? = null

    override fun submitTransaction(request: VendorTransactionRequest): VendorTransactionSubmission = error("not used")

    override fun transaction(transactionId: String): VendorTransaction? = transaction?.takeIf { it.transactionId == transactionId }

    override fun transactionByExternalTransactionId(externalTransactionId: String): VendorTransaction? = error("not used")

    override fun transactions(request: VendorTransactionPageRequest): VendorPage<VendorTransaction> = error("not used")
}
