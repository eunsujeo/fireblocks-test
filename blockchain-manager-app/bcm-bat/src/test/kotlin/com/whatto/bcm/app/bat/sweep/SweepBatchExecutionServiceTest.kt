package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.app.bat.sweep.fixture.SweepRuntimeFixtures
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.sweep.SweepBatchCallItem
import com.whatto.bcm.domain.sweep.SweepBatchContractPort
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItem
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.domain.sweep.SweepTarget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SweepBatchExecutionServiceTest {
    @Test
    fun `batch 출시 게이트가 닫혀 있으면 후보도 조회하지 않는다`() {
        val selector = FakeBatchCandidates(candidate(ACCOUNT_B, "12.5"))
        val service = service(selector = selector, properties = properties(batchEnabled = false))

        assertThatThrownBy { service.runOnce() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("batch submission gate")
        assertThat(selector.calls).isZero()
    }

    @Test
    fun `준비된 후보를 주소순 item으로 선기록하고 운영 계정 CONTRACT_CALL 한 건으로 제출한다`() {
        val executions = FakeBatchExecutions()
        val calls = FakeBatchContractCalls()
        val contract = FakeBatchContract()
        val service =
            service(
                selector = FakeBatchCandidates(candidate(ACCOUNT_B, "12.5"), candidate(ACCOUNT_A, "3.0")),
                executions = executions,
                contract = contract,
                calls = calls,
            )

        val result = service.runOnce()

        assertThat(result).isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, EXTERNAL_ID, VENDOR_TX_ID))
        assertThat(executions.execution?.status).isEqualTo(SweepExecutionStatus.SUBMITTED)
        assertThat(executions.items.map { it.accountId }).containsExactly(ACCOUNT_A, ACCOUNT_B)
        assertThat(executions.items.map { it.sequence }).containsExactly(1, 2)
        assertThat(executions.items.map { it.requestedAmount }).containsExactly("3", "12.5")
        assertThat(executions.execution?.requestedTotalAmount).isEqualTo("15.5")
        assertThat(executions.execution)
            .extracting("policyVersionId", "policySnapshotHash", "contractVersionId", "contractEvidenceId")
            .containsExactly("policy-ETHEREUM-USDC", "a".repeat(64), "contract-ETHEREUM", "evidence-ETHEREUM")
        assertThat(executions.execution?.requestHash)
            .isEqualTo("771d0851bbbbd7fadc6dffbcf4834595337018bf46db8d7490e7923777049a5f")
        assertThat(contract.items).containsExactly(SweepBatchCallItem(OWNER_A, "3"), SweepBatchCallItem(OWNER_B, "12.5"))
        assertThat(calls.commands.single())
            .extracting(
                "transactionType",
                "senderAccountId",
                "sourceVaultId",
                "semanticAmount",
                "sweepExecutionId",
            ).containsExactly(
                com.whatto.bcm.domain.submission.SubmissionTransactionType.SWEEP_BATCH,
                OPERATOR_ID,
                OPERATOR_VAULT,
                "15.5",
                EXECUTION_ID,
            )
    }

    @Test
    fun `sweep 실행 게이트가 중지되면 새 실행과 allowance 준비를 만들지 않는다`() {
        val executions = FakeBatchExecutions()
        val calls = FakeBatchContractCalls()
        var allowanceCalls = 0
        val service =
            service(
                selector = FakeBatchCandidates(candidate(ACCOUNT_A, "3")),
                allowance =
                    SweepAllowancePreparer {
                        allowanceCalls += 1
                        SweepAllowancePreparationResult.Ready
                    },
                executions = executions,
                calls = calls,
                gates = FakeExecutionGates(setOf(NETWORK to ExecutionGateType.SWEEP)),
            )

        assertThat(service.runOnce()).isEqualTo(SweepBatchExecutionResult.Stopped(NETWORK))
        assertThat(allowanceCalls).isZero()
        assertThat(executions.execution).isNull()
        assertThat(calls.commands).isEmpty()
    }

    @Test
    fun `sweep 실행 게이트의 최신 event가 RESUMED이면 새 batch를 허용한다`() {
        val service =
            service(
                selector = FakeBatchCandidates(candidate(ACCOUNT_A, "3")),
                gates = FakeExecutionGates(resumed = setOf(NETWORK to ExecutionGateType.SWEEP)),
            )

        assertThat(service.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, EXTERNAL_ID, VENDOR_TX_ID))
    }

    @Test
    fun `후보 선정 뒤 정책 cap이 낮아지면 새 cap 안의 후보만 실행한다`() {
        val executions = FakeBatchExecutions()
        val service =
            service(
                selector = FakeBatchCandidates(candidate(ACCOUNT_A, "5"), candidate(ACCOUNT_B, "3")),
                executions = executions,
                runtimeGuard =
                    SweepRuntimeFixtures.guard(
                        SweepRuntimeFixtures.context(
                            contractAddress = SWEEPER,
                            itemAmountCap = "4",
                            batchAmountCap = "4",
                        ),
                    ),
            )

        assertThat(service.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, EXTERNAL_ID, VENDOR_TX_ID))
        assertThat(executions.items.map { it.accountId }).containsExactly(ACCOUNT_B)
        assertThat(executions.execution?.requestedTotalAmount).isEqualTo("3")
    }

    @Test
    fun `SUBMITTING에서 중단되면 새 후보를 만들지 않고 같은 실행과 external id를 회수한다`() {
        val selector = FakeBatchCandidates(candidate(ACCOUNT_A, "3"))
        val executions = FakeBatchExecutions()
        val calls = FakeBatchContractCalls()
        val gates = FakeExecutionGates()
        calls.failure = SubmissionInProgressException(EXTERNAL_ID, 1)
        val service = service(selector = selector, executions = executions, calls = calls, gates = gates)

        assertThat(service.runOnce()).isEqualTo(SweepBatchExecutionResult.SubmissionPending(EXECUTION_ID, EXTERNAL_ID))
        assertThat(executions.execution?.status).isEqualTo(SweepExecutionStatus.SUBMITTING)

        gates.stopped = setOf(NETWORK to ExecutionGateType.SWEEP)
        calls.failure = null
        assertThat(service.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, EXTERNAL_ID, VENDOR_TX_ID))
        assertThat(selector.calls).isEqualTo(1)
        assertThat(calls.commands.map { it.externalTransactionId }).containsExactly(EXTERNAL_ID, EXTERNAL_ID)
    }

    @Test
    fun `relay 거절 뒤에도 실행과 claim을 보존해 같은 external id로 재시도한다`() {
        val executions = FakeBatchExecutions()
        val calls = FakeBatchContractCalls()
        calls.failure = RelayRejectedException("rejected")
        val service = service(selector = FakeBatchCandidates(candidate(ACCOUNT_A, "3")), executions = executions, calls = calls)

        assertThatThrownBy { service.runOnce() }.isInstanceOf(RelayRejectedException::class.java)
        assertThat(executions.execution?.status).isEqualTo(SweepExecutionStatus.SUBMITTING)
        assertThat(executions.released).isFalse()

        calls.failure = null
        assertThat(service.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, EXTERNAL_ID, VENDOR_TX_ID))
        assertThat(calls.commands.map { it.externalTransactionId }).containsExactly(EXTERNAL_ID, EXTERNAL_ID)
    }

    @Test
    fun `allowance 준비 중인 후보만 있으면 실행 원장을 만들지 않는다`() {
        val executions = FakeBatchExecutions()
        val service =
            service(
                selector = FakeBatchCandidates(candidate(ACCOUNT_A, "3")),
                allowance = SweepAllowancePreparer { SweepAllowancePreparationResult.Pending("swa-1", "vendor-approve") },
                executions = executions,
            )

        assertThat(service.runOnce()).isEqualTo(SweepBatchExecutionResult.WaitingForAllowance(1))
        assertThat(executions.execution).isNull()
    }

    private fun service(
        selector: FakeBatchCandidates = FakeBatchCandidates(),
        allowance: SweepAllowancePreparer = SweepAllowancePreparer { SweepAllowancePreparationResult.Ready },
        executions: FakeBatchExecutions = FakeBatchExecutions(),
        contract: FakeBatchContract = FakeBatchContract(),
        calls: FakeBatchContractCalls = FakeBatchContractCalls(),
        gates: FakeExecutionGates = FakeExecutionGates(),
        properties: SweepProperties = properties(),
        runtimeGuard: SweepRuntimeGuard =
            SweepRuntimeFixtures.guard(
                SweepRuntimeFixtures.context(contractAddress = SWEEPER, batchSize = properties.batchSize),
            ),
    ) = SweepBatchExecutionService(
        selector,
        allowance,
        executions,
        FakeBatchAccounts(),
        FakeBatchAddresses(),
        FakeBatchMappings(),
        contract,
        calls,
        SweepExecutionIdGenerator { EXECUTION_ID },
        SweepExternalTransactionIdGenerator { EXTERNAL_ID },
        SweepExecutionAlertPort { alerts += it },
        Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
        properties,
        gates,
        runtimeGuard,
    )

    private fun properties(batchEnabled: Boolean = true) =
        SweepProperties(
            omnibusAccountId = OMNIBUS_ID,
            operatorAccountId = OPERATOR_ID,
            thresholds = listOf(SweepAssetThreshold(NETWORK, SYMBOL, "1", "100")),
            contracts = listOf(SweepNetworkContract(NETWORK, SWEEPER)),
            security =
                SweepSecurityProperties(
                    batchSubmissionEnabled = batchEnabled,
                    tapBatchPolicyVerified = true,
                    callbackVerified = true,
                    universalGaslessVerified = true,
                    sweepContractVerified = true,
                    batchSubmissionEnabledNetworks = setOf(NETWORK),
                ),
        )

    private fun candidate(
        accountId: String,
        amount: String,
    ) = SweepCandidate(
        target = SweepTarget(accountId, NETWORK, SYMBOL, "20260812090000", null, null, 0, null),
        sourceVaultId = "vault-$accountId",
        omnibusAccountId = OMNIBUS_ID,
        omnibusVaultId = "vault-omnibus",
        vendorAssetId = "USDC_ERC20",
        amount = amount,
    )

    private companion object {
        val alerts = mutableListOf<SweepExecutionAlert>()
        const val NETWORK = "ETHEREUM"
        const val SYMBOL = "USDC"
        const val TOKEN = "0x1111111111111111111111111111111111111111"
        const val OWNER_A = "0x2222222222222222222222222222222222222222"
        const val OWNER_B = "0x3333333333333333333333333333333333333333"
        const val SWEEPER = "0x4444444444444444444444444444444444444444"
        const val ACCOUNT_A = "customer-a"
        const val ACCOUNT_B = "customer-b"
        const val OPERATOR_ID = "operator-1"
        const val OPERATOR_VAULT = "vault-operator"
        const val OMNIBUS_ID = "omnibus-1"
        const val EXECUTION_ID = "01987654-3210-7abc-8def-0123456789ab"
        const val EXTERNAL_ID = "swp-01987654"
        const val VENDOR_TX_ID = "vendor-batch-1"
    }
}

private class FakeBatchCandidates(
    private vararg val values: SweepCandidate,
) : SweepCandidateSelector {
    var calls = 0
        private set

    override fun selectCandidates(): List<SweepCandidate> {
        calls += 1
        return values.toList()
    }
}

private class FakeBatchExecutions : SweepExecutionRepository {
    var execution: SweepExecution? = null
    var items = emptyList<SweepItem>()
    var released = false

    override fun createAndClaim(
        execution: SweepExecution,
        items: List<SweepItem>,
    ) {
        check(this.execution == null)
        this.execution = execution
        this.items = items
    }

    override fun findById(executionId: String): SweepExecution? = execution?.takeIf { it.executionId == executionId }

    override fun findByIdForUpdate(executionId: String): SweepExecution? = findById(executionId)

    override fun findItems(executionId: String): List<SweepItem> = items.takeIf { execution?.executionId == executionId }.orEmpty()

    override fun findPendingSubmission(operatorAccountId: String): SweepExecution? =
        execution?.takeIf {
            it.operatorAccountId == operatorAccountId &&
                (it.status == SweepExecutionStatus.READY || it.status == SweepExecutionStatus.SUBMITTING)
        }

    override fun findReconciling(limit: Int): List<SweepExecution> = emptyList()

    override fun markSubmitting(executionId: String): SweepExecution =
        requireExecution(executionId).copy(status = SweepExecutionStatus.SUBMITTING).also { execution = it }

    override fun markSubmitted(
        executionId: String,
        vendorTransactionId: String,
    ): SweepExecution =
        requireExecution(executionId)
            .copy(status = SweepExecutionStatus.SUBMITTED, vendorTransactionId = vendorTransactionId)
            .also { execution = it }

    override fun markReconciling(
        executionId: String,
        vendorTransactionId: String,
        transactionHash: String?,
    ): SweepExecution = error("not used")

    override fun completeReconciliation(
        executionId: String,
        items: List<com.whatto.bcm.domain.sweep.SweepItemReconciliation>,
        status: SweepExecutionStatus,
        actualTotalAmount: String,
        finishedAt: String,
    ): SweepExecution = error("not used")

    override fun markFailedAndRelease(
        executionId: String,
        finishedAt: String,
    ): SweepExecution =
        requireExecution(executionId)
            .copy(status = SweepExecutionStatus.FAILED, finishedAt = finishedAt)
            .also {
                execution = it
                items = items.map { item -> item.copy(status = SweepItemStatus.RETRY) }
                released = true
            }

    private fun requireExecution(executionId: String): SweepExecution = checkNotNull(execution?.takeIf { it.executionId == executionId })
}

private class FakeBatchAccounts : AccountRepository {
    private val rows =
        listOf(
            Account("operator-1", AccountType.SYSTEM, "operator-ref", "vault-operator", "20260812090000"),
            Account("omnibus-1", AccountType.SYSTEM, "omnibus-ref", "vault-omnibus", "20260812090000"),
        ).associateBy { it.accountId }

    override fun insert(account: Account): Account = error("not used")

    override fun findByTypeAndRef(
        accountType: AccountType,
        ref: String,
    ): Account? = error("not used")

    override fun findByAccountId(accountId: String): Account? = rows[accountId]
}

private class FakeBatchAddresses : DepositAddressRepository {
    private val rows =
        listOf(
            DepositAddress("customer-a", "ETHEREUM", "USDC", "0x2222222222222222222222222222222222222222", "20260812090000"),
            DepositAddress("customer-b", "ETHEREUM", "USDC", "0x3333333333333333333333333333333333333333", "20260812090000"),
        ).associateBy { Triple(it.accountId, it.network, it.symbol) }

    override fun insert(depositAddress: DepositAddress): DepositAddress = error("not used")

    override fun find(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddress? = rows[Triple(accountId, network, symbol)]

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

private class FakeBatchMappings : VendorAssetMappingRepository {
    private val mapping =
        VendorAssetMapping(
            "ETHEREUM",
            "USDC",
            "USDC_ERC20",
            "0x1111111111111111111111111111111111111111",
            "20260812090000",
            "SYSTEM",
            "9999",
        )

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

private class FakeBatchContract : SweepBatchContractPort {
    var items = emptyList<SweepBatchCallItem>()

    override fun batchSweepCallData(
        network: String,
        executionId: String,
        tokenContractAddress: String,
        items: List<SweepBatchCallItem>,
    ): String {
        this.items = items
        return "0x4209ef32data"
    }
}

private class FakeBatchContractCalls : SweepContractCallSubmitter {
    val commands = mutableListOf<SweepContractCallCommand>()
    var failure: RuntimeException? = null

    override fun submit(command: SweepContractCallCommand): SweepContractCallResult {
        commands += command
        failure?.let { throw it }
        return SweepContractCallResult("vendor-batch-1")
    }
}
