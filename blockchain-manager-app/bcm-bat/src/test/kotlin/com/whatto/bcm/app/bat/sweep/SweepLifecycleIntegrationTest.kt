package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.sweep.SweepOutboxEventPublisher
import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import com.whatto.bcm.app.bat.sweep.fixture.SweepRuntimeFixtures
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.domain.sweep.SweepBatchCallItem
import com.whatto.bcm.domain.sweep.SweepBatchContractPort
import com.whatto.bcm.domain.sweep.SweepBatchReceipt
import com.whatto.bcm.domain.sweep.SweepBatchReceiptPort
import com.whatto.bcm.domain.sweep.SweepEventSerializer
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import com.whatto.bcm.domain.sweep.SweepExecutionStage
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItem
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
import com.whatto.bcm.infra.persistence.event.OutboxJdbcAdapter
import com.whatto.bcm.infra.persistence.monitoring.OperationalSignalJdbcAdapter
import com.whatto.bcm.infra.persistence.submission.SubmissionJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepAuthorizationJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepExecutionJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepTargetJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepTransactionStatusJdbcAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    OutboxJdbcAdapter::class,
    OperationalSignalJdbcAdapter::class,
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
    lateinit var outbox: OutboxEventRepository

    @Autowired
    lateinit var operationalSignals: OperationalSignalJdbcAdapter

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
        insertAdminSnapshotReferences()
        insertSweepRequest()
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
        val runtimeGuard = runtimeGuard()
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
                FakeExecutionGates(),
                runtimeGuard,
            )
        val eventPublisher =
            SweepOutboxEventPublisher(OutboxEventService(outbox), SweepEventSerializer { "{}" }, CLOCK, 5)
        val candidates =
            SweepCandidateSelectionService(
                targets,
                accounts,
                mappings,
                wallet,
                transactionRunner,
                transactionStatuses,
                eventPublisher,
                alerts,
                CLOCK,
                PROPERTIES,
                runtimeGuard,
            )
        executionService =
            SweepBatchExecutionService(
                candidates,
                allowanceService,
                executions,
                AccountQueryService(accounts),
                DepositAddressQueryService(addresses),
                VendorAssetMappingQueryService(mappings),
                LifecycleBatchContract,
                contractCalls,
                SequenceBatchExecutionIds(),
                SequenceBatchExternalIds(),
                alerts,
                CLOCK,
                PROPERTIES,
                FakeExecutionGates(),
                runtimeGuard,
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
                eventPublisher,
                alerts,
                CLOCK,
                PROPERTIES,
            )
    }

    @Test
    fun `성공 후 최소금액 이상 잔액이 남으면 같은 요청 항목을 다음 실행으로 연결한다`() {
        erc20.allowances[OWNER_A] = "100"
        erc20.allowances[OWNER_B] = "100"
        assertThat(executionService.runOnce()).isInstanceOf(SweepBatchExecutionResult.Submitted::class.java)
        val originalItem = executions.findItems(EXECUTION_ID).single { it.accountId == ACCOUNT_A }
        executions.markReconciling(EXECUTION_ID, BATCH_VENDOR_TX_ID, TRANSACTION_HASH)
        vendor.transaction = completedBatchTransaction(records = listOf(VAULT_A to "20", VAULT_B to "30"))
        receipts.value = successfulReceipt(EXECUTION_ID, listOf(OWNER_A to "20", OWNER_B to "30"))
        wallet.availableByVault[VAULT_A] = "20"
        wallet.availableByVault[VAULT_B] = "0"

        assertThat(reconciliationService.runOnce()).isEqualTo(SweepReconciliationCycleResult(1, 0, 0))

        assertThat(requestItemStatuses()).containsExactly("PENDING", "COMPLETED")
        assertThat(requestStatus()).isEqualTo("PARTIAL")
        assertThat(targets.findPending(setOf("ETHEREUM"), 100).map { it.accountId }).containsExactly(ACCOUNT_A)
        assertThat(executionService.runOnce()).isInstanceOf(SweepBatchExecutionResult.Submitted::class.java)
        assertThat(executions.findItems(EXECUTION_ID_2).single().sweepRequestItemId).isEqualTo(originalItem.sweepRequestItemId)
        assertThat(executions.findItems(EXECUTION_ID).single { it.accountId == ACCOUNT_A }.status).isEqualTo(SweepItemStatus.SUCCEEDED)
    }

    @Test
    fun `FAILED 관찰 뒤 도착한 오래된 성공 대사는 요청을 완료하지 않는다`() {
        erc20.allowances[OWNER_A] = "100"
        erc20.allowances[OWNER_B] = "100"
        assertThat(executionService.runOnce()).isInstanceOf(SweepBatchExecutionResult.Submitted::class.java)
        executions.markReconciling(EXECUTION_ID, BATCH_VENDOR_TX_ID, TRANSACTION_HASH)
        vendor.transaction = completedBatchTransaction(records = listOf(VAULT_A to "20", VAULT_B to "30"))
        receipts.value = successfulReceipt(EXECUTION_ID, listOf(OWNER_A to "20", OWNER_B to "30"))
        wallet.availableByVault[VAULT_A] = "0"
        wallet.availableByVault[VAULT_B] = "0"
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, frst_dtct_dttm, last_chng_dttm, vndr_crt_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, ?, 'ETHEREUM', 'USDC', ?, 'FAILED', 1, 'DROPPED_BY_BLOCKCHAIN',
                    '20260812090000', '20260812100000', '20260812090000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            BATCH_VENDOR_TX_ID,
            BATCH_VENDOR_TX_ID,
            BATCH_EXTERNAL_ID,
            OPERATOR_ID,
            TRANSACTION_HASH,
        )

        assertThat(reconciliationService.runOnce()).isEqualTo(SweepReconciliationCycleResult(0, 0, 1))
        assertThat(executions.findById(EXECUTION_ID)?.status).isEqualTo(SweepExecutionStatus.RECONCILING)
        assertThat(requestItemStatuses()).containsExactly("PROCESSING", "PROCESSING")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Int::class.java)).isZero()
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
        assertThat(
            jdbc.queryForObject(
                "SELECT swp_req_stcd FROM bcm_swp_req_l WHERE swp_req_id = 'lifecycle-request'",
                String::class.java,
            ),
        ).isEqualTo("PARTIAL")
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
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM bcm_outbox_l WHERE topic = 'sweep-events'",
                Long::class.java,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `한 요청의 세 항목은 batch size 둘에 따라 두 실행으로 분할되고 마지막 대사 뒤 완료된다`() {
        insertThirdRequestItem()
        targets.insertIfAbsent(target(ACCOUNT_C))
        wallet.availableByVault[VAULT_C] = "40"
        erc20.allowances[OWNER_A] = "100"
        erc20.allowances[OWNER_B] = "100"
        erc20.allowances[OWNER_C] = "100"

        assertThat(executionService.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, BATCH_EXTERNAL_ID, BATCH_VENDOR_TX_ID))
        assertThat(executions.findItems(EXECUTION_ID).map(SweepItem::accountId))
            .containsExactly(ACCOUNT_A, ACCOUNT_B)
        executions.markReconciling(EXECUTION_ID, BATCH_VENDOR_TX_ID, TRANSACTION_HASH)
        vendor.transaction =
            completedBatchTransaction(
                records = listOf(VAULT_A to "20", VAULT_B to "30"),
            )
        receipts.value =
            successfulReceipt(
                EXECUTION_ID,
                listOf(OWNER_A to "20", OWNER_B to "30"),
            )
        wallet.availableByVault[VAULT_A] = "0"
        wallet.availableByVault[VAULT_B] = "0"

        assertThat(reconciliationService.runOnce()).isEqualTo(SweepReconciliationCycleResult(1, 0, 0))
        assertThat(requestStatus()).isEqualTo("PARTIAL")
        assertThat(requestItemStatuses())
            .containsExactly("COMPLETED", "COMPLETED", "PENDING")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l WHERE topic='sweep-events'", Int::class.java))
            .isEqualTo(2)

        assertThat(executionService.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID_2, BATCH_EXTERNAL_ID_2, BATCH_VENDOR_TX_ID_2))
        assertThat(executions.findItems(EXECUTION_ID_2).map(SweepItem::accountId)).containsExactly(ACCOUNT_C)
        executions.markReconciling(EXECUTION_ID_2, BATCH_VENDOR_TX_ID_2, TRANSACTION_HASH_2)
        vendor.transaction =
            completedBatchTransaction(
                vendorTransactionId = BATCH_VENDOR_TX_ID_2,
                externalTransactionId = BATCH_EXTERNAL_ID_2,
                transactionHash = TRANSACTION_HASH_2,
                records = listOf(VAULT_C to "40"),
            )
        receipts.value = successfulReceipt(EXECUTION_ID_2, listOf(OWNER_C to "40"))
        wallet.availableByVault[VAULT_C] = "0"

        assertThat(reconciliationService.runOnce()).isEqualTo(SweepReconciliationCycleResult(1, 0, 0))
        assertThat(requestStatus()).isEqualTo("COMPLETED")
        assertThat(requestItemStatuses()).containsExactly("COMPLETED", "COMPLETED", "COMPLETED")
        assertThat(executions.findById(EXECUTION_ID)?.status).isEqualTo(SweepExecutionStatus.COMPLETED)
        assertThat(executions.findById(EXECUTION_ID_2)?.status).isEqualTo(SweepExecutionStatus.COMPLETED)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l WHERE topic='sweep-events'", Int::class.java))
            .isEqualTo(3)
    }

    @Test
    fun `0잔액 종결 outbox 적재 실패는 요청 항목과 target 변경을 모두 rollback한다`() {
        wallet.availableByVault.replaceAll { _, _ -> "0" }
        val requestLedgerBefore = requestLedgerRows()
        val targetLedgerBefore = targetLedgerRows()
        val observedAlerts = mutableListOf<SweepExecutionAlert>()
        val failingEventPublisher =
            SweepOutboxEventPublisher(
                OutboxEventService(outbox),
                SweepEventSerializer { "{invalid-json" },
                CLOCK,
                5,
            )
        val candidates =
            SweepCandidateSelectionService(
                targets,
                LifecycleAccounts(),
                LifecycleMappings,
                wallet,
                transactionRunner,
                transactionStatuses,
                failingEventPublisher,
                SweepExecutionAlertPort { observedAlerts += it },
                CLOCK,
                PROPERTIES,
                runtimeGuard(),
            )

        assertThat(candidates.selectCandidates()).isEmpty()

        assertThat(requestLedgerRows()).containsExactlyElementsOf(requestLedgerBefore)
        assertThat(targetLedgerRows()).containsExactlyElementsOf(targetLedgerBefore)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bcm_outbox_l WHERE topic='sweep-events'",
                Int::class.java,
            ),
        ).isZero()
        assertThat(observedAlerts)
            .hasSize(2)
            .allMatch { it.stage == SweepExecutionStage.SELECTION }
    }

    @Test
    fun `FAILED submission을 실제 재획득한 때만 target 재시도와 반복 실패 신호를 누적한다`() {
        erc20.allowances.replaceAll { _, _ -> "100" }
        vendor.batchSubmission = {
            VendorTransactionSubmission.BadRequestNeedsLookup(VendorApiException("submitContractCall", 400))
        }

        repeat(3) {
            assertThatThrownBy { executionService.runOnce() }
                .isInstanceOf(RelayRejectedException::class.java)
        }

        assertThat(targetAttemptCounts()).containsExactly(3, 3)
        assertThat(operationalSignals.sweepOperationalSignals().repeatedFailureTargetCount).isEqualTo(2)
        assertThat(submissionStatus()).isEqualTo("FAILED")
    }

    @Test
    fun `유효한 REQUESTED claim 회수는 target 재시도로 세지 않는다`() {
        erc20.allowances.replaceAll { _, _ -> "100" }
        vendor.batchSubmission = { error("simulated crash after submission intent") }

        assertThatThrownBy { executionService.runOnce() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("simulated crash after submission intent")
        assertThat(targetAttemptCounts()).containsExactly(1, 1)
        assertThat(submissionStatus()).isEqualTo("REQUESTED")

        assertThat(executionService.runOnce())
            .isEqualTo(SweepBatchExecutionResult.SubmissionPending(EXECUTION_ID, BATCH_EXTERNAL_ID))
        assertThat(targetAttemptCounts()).containsExactly(1, 1)
        assertThat(vendor.requests.filter { it.contractAddress == SWEEP_CONTRACT }).hasSize(1)
    }

    @Test
    fun `SUBMITTING 뒤 submission intent 전 crash 회수는 target 재시도로 세지 않는다`() {
        erc20.allowances.replaceAll { _, _ -> "100" }

        assertThat(executionService.prepareOnce())
            .isEqualTo(SweepBatchExecutionResult.Prepared(EXECUTION_ID, BATCH_EXTERNAL_ID))
        executions.markSubmitting(EXECUTION_ID)
        assertThat(submissionStatusOrNull()).isNull()
        assertThat(targetAttemptCounts()).containsExactly(1, 1)

        assertThat(executionService.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, BATCH_EXTERNAL_ID, BATCH_VENDOR_TX_ID))
        assertThat(targetAttemptCounts()).containsExactly(1, 1)
    }

    @Test
    fun `이미 SUBMITTED인 submission 회수는 target 재시도로 세지 않는다`() {
        erc20.allowances.replaceAll { _, _ -> "100" }

        assertThat(executionService.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, BATCH_EXTERNAL_ID, BATCH_VENDOR_TX_ID))
        assertThat(targetAttemptCounts()).containsExactly(1, 1)
        jdbc.update("UPDATE bcm_swp_exec_l SET swp_exec_stcd='SUBMITTING' WHERE swp_exec_id=?", EXECUTION_ID)

        assertThat(executionService.runOnce())
            .isEqualTo(SweepBatchExecutionResult.Submitted(EXECUTION_ID, BATCH_EXTERNAL_ID, BATCH_VENDOR_TX_ID))
        assertThat(targetAttemptCounts()).containsExactly(1, 1)
        assertThat(vendor.requests.filter { it.contractAddress == SWEEP_CONTRACT }).hasSize(1)
    }

    private fun completedBatchTransaction(
        vendorTransactionId: String = BATCH_VENDOR_TX_ID,
        externalTransactionId: String = BATCH_EXTERNAL_ID,
        transactionHash: String = TRANSACTION_HASH,
        records: List<Pair<String, String>> = listOf(VAULT_A to "20"),
    ) = VendorTransaction(
        transactionId = vendorTransactionId,
        externalTransactionId = externalTransactionId,
        vendorAssetId = VENDOR_ASSET_ID,
        rawStatus = "COMPLETED",
        subStatus = null,
        transactionHash = transactionHash,
        source = VendorTransactionPeer("VAULT_ACCOUNT", OPERATOR_VAULT),
        destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
        sourceAddress = null,
        destinationAddress = SWEEP_CONTRACT,
        amount = "50",
        confirmationCount = 1,
        createdAtEpochMillis = 1,
        lastUpdatedEpochMillis = 2,
        networkRecords =
            records.map { (vaultId, amount) ->
                VendorNetworkRecord(
                    type = "TOKEN_TRANSFER",
                    source = VendorTransactionPeer("VAULT_ACCOUNT", vaultId),
                    destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
                    destinationAddress = OMNIBUS_ADDRESS,
                    transactionHash = transactionHash,
                    vendorAssetId = VENDOR_ASSET_ID,
                    netAmount = amount,
                    dropped = false,
                )
            },
    )

    private fun successfulReceipt(
        executionId: String,
        legs: List<Pair<String, String>>,
    ) = SweepBatchReceipt(
        successful = true,
        legs =
            legs.mapIndexed { index, (owner, amount) ->
                SweepLegObservation(
                    executionId,
                    index + 1,
                    owner,
                    amount,
                    amount,
                    true,
                    ZERO_FAILURE_CODE,
                    20 + index,
                )
            },
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
        jdbc.update("DELETE FROM bcm_evnt_cmpl_l")
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_swp_trgt")
        jdbc.update("DELETE FROM bcm_swp_item_l")
        jdbc.update("DELETE FROM bcm_swp_exec_l")
        jdbc.update("DELETE FROM bcm_swp_req_item_l WHERE swp_req_id = 'lifecycle-request'")
        jdbc.update("DELETE FROM bcm_swp_req_l WHERE swp_req_id = 'lifecycle-request'")
        jdbc.update("DELETE FROM bcm_sbmt_l")
        jdbc.update("DELETE FROM bcm_swp_auth_m")
        jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id IN (?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C)
    }

    private fun insertSweepRequest() {
        listOf(ACCOUNT_A to VAULT_A, ACCOUNT_B to VAULT_B).forEach { (accountId, vaultId) ->
            jdbc.update(
                """
                INSERT INTO bcm_acnt_m
                  (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (?, 'CU', ?, ?, '20260813090000', 'SYSTEM', '9999', 'SYSTEM', '9999')
                """.trimIndent(),
                accountId,
                "request-$accountId",
                vaultId,
            )
        }
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_l
              (swp_req_id, ext_swp_req_id, req_hash, ntwk_cd, tkn_smbl, swp_req_stcd,
               item_cnt, req_dttm, fnsh_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('lifecycle-request', 'lifecycle-request', ?, ?, ?, 'ACCEPTED',
                    2, '20260813090000', NULL, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            "c".repeat(64),
            NETWORK,
            SYMBOL,
        )
        listOf(ACCOUNT_A, ACCOUNT_B).forEachIndexed { index, accountId ->
            jdbc.update(
                """
                INSERT INTO bcm_swp_req_item_l
                  (swp_req_item_id, swp_req_id, item_seq, acnt_id, swp_req_item_stcd, last_fail_cd,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (?, 'lifecycle-request', ?, ?, 'PENDING', NULL,
                        'SYSTEM', '9999', 'SYSTEM', '9999')
                """.trimIndent(),
                "lifecycle-request-item-${index + 1}",
                index + 1,
                accountId,
            )
        }
    }

    private fun insertThirdRequestItem() {
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'CU', ?, ?, '20260813090000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            ACCOUNT_C,
            "request-$ACCOUNT_C",
            VAULT_C,
        )
        jdbc.update("UPDATE bcm_swp_req_l SET item_cnt=3 WHERE swp_req_id='lifecycle-request'")
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_item_l
              (swp_req_item_id, swp_req_id, item_seq, acnt_id, swp_req_item_stcd, last_fail_cd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('lifecycle-request-item-3', 'lifecycle-request', 3, ?, 'PENDING', NULL,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            ACCOUNT_C,
        )
    }

    private fun requestStatus(): String =
        jdbc.queryForObject(
            "SELECT swp_req_stcd FROM bcm_swp_req_l WHERE swp_req_id='lifecycle-request'",
            String::class.java,
        )!!

    private fun requestItemStatuses(): List<String> =
        jdbc
            .queryForList(
                """
                SELECT swp_req_item_stcd
                FROM bcm_swp_req_item_l
                WHERE swp_req_id='lifecycle-request'
                ORDER BY item_seq
                """.trimIndent(),
                String::class.java,
            ).map(::requireNotNull)

    private fun requestLedgerRows(): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT request.swp_req_id, request.swp_req_stcd, request.fnsh_dttm,
                   item.swp_req_item_id, item.swp_req_item_stcd, item.last_fail_cd
            FROM bcm_swp_req_l request
            JOIN bcm_swp_req_item_l item ON item.swp_req_id = request.swp_req_id
            WHERE request.swp_req_id = 'lifecycle-request'
            ORDER BY item.item_seq
            """.trimIndent(),
        )

    private fun targetLedgerRows(): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT acnt_id, ntwk_cd, tkn_smbl, reg_dttm, actv_swp_exec_id, actv_item_seq,
                   try_cnt, last_try_dttm, last_chng_empno, last_chng_brcd
            FROM bcm_swp_trgt
            WHERE acnt_id IN (?, ?)
            ORDER BY acnt_id
            """.trimIndent(),
            ACCOUNT_A,
            ACCOUNT_B,
        )

    private fun targetAttemptCounts(): List<Int> =
        jdbc
            .queryForList(
                "SELECT try_cnt FROM bcm_swp_trgt WHERE acnt_id IN (?, ?) ORDER BY acnt_id",
                Int::class.java,
                ACCOUNT_A,
                ACCOUNT_B,
            ).map(::requireNotNull)

    private fun submissionStatus(): String = requireNotNull(submissionStatusOrNull())

    private fun submissionStatusOrNull(): String? =
        jdbc
            .query(
                "SELECT sbmt_stcd FROM bcm_sbmt_l WHERE ext_tx_id=?",
                { resultSet, _ -> resultSet.getString("sbmt_stcd") },
                BATCH_EXTERNAL_ID,
            ).singleOrNull()

    private fun insertAdminSnapshotReferences() {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr, release_cmit,
               artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash, deploy_blck_no,
               immut_payload, immut_hash, ceiling_payload, ceiling_hash, release_uri, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('contract-ETHEREUM', 'ETHEREUM:SWEEP', 'ETHEREUM', 'SWEEP', '1.0.0', ?, 'commit',
                    ?, ?, ?, '0xdeploy', 1, '{}'::jsonb, ?, '{}'::jsonb, ?, 'doc://release', '20260813000000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ctrt_vrsn_id) DO NOTHING
            """.trimIndent(),
            SWEEP_CONTRACT,
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "5".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_evdc_l
              (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash, exp_immut_hash, pin_blck_no,
               rpc1_id, rpc1_chain_id, rpc1_code_hash, rpc1_immut_hash, rpc1_obs_dttm,
               rpc2_id, rpc2_chain_id, rpc2_code_hash, rpc2_immut_hash, rpc2_obs_dttm,
               tap_mtch_yn, clbk_mtch_yn, gasless_pass_yn, audit_pass_yn, revoke_drill_yn,
               launch_gate_yn, evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('evidence-ETHEREUM', 'contract-ETHEREUM', ?, 1, ?, ?, 1,
                    'RPC_A', 1, ?, ?, '20260813000000', 'RPC_B', 1, ?, ?, '20260813000000',
                    'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'VALID', '20260813000000', '20991231235959',
                    '{}'::jsonb, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (evdc_id) DO NOTHING
            """.trimIndent(),
            "6".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "7".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_vrsn_l
              (plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn, ctrt_vrsn_id,
               plcy_payload, plcy_hash, ceiling_snps, ceiling_hash, ceiling_pass_yn, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('policy-ETHEREUM-USDC', 'POLICY:ETHEREUM:USDC', 1, 'v1', 'contract-ETHEREUM',
                    '{"enabled":true,"minimumAmount":1,"batchSize":25,"allowanceCap":1000,"itemAmountCap":1000,"batchAmountCap":10000,"boostAttempts":1}'::jsonb,
                    ?, '{}'::jsonb, ?, 'Y', '20260813000000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (plcy_vrsn_id) DO NOTHING
            """.trimIndent(),
            "8".repeat(64),
            "9".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_bind_m
              (ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id, bind_rvsn, last_evdc_id,
               bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('ETHEREUM:SWEEP', 'ETHEREUM', 'SWEEP', 'contract-ETHEREUM', 1, 'evidence-ETHEREUM', ?,
                    '20260813000000', '20260813000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ctrt_scope_id) DO NOTHING
            """.trimIndent(),
            "b".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_bind_m
              (plcy_scope_id, actv_plcy_vrsn_id, bind_rvsn, bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('POLICY:ETHEREUM:USDC', 'policy-ETHEREUM-USDC', 1, ?,
                    '20260813000000', '20260813000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (plcy_scope_id) DO NOTHING
            """.trimIndent(),
            "a".repeat(64),
        )
    }

    private fun runtimeGuard(): SweepRuntimeGuard =
        SweepRuntimeFixtures.guard(
            SweepRuntimeFixtures.context(
                network = NETWORK,
                symbol = SYMBOL,
                contractAddress = SWEEP_CONTRACT,
                minimumAmount = "10",
                allowanceCap = "100",
                batchSize = PROPERTIES.batchSize,
            ),
        )

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
                        normalApprovalEnabledNetworks = setOf(NETWORK),
                        emergencyRevocationEnabledNetworks = setOf(NETWORK),
                        batchSubmissionEnabledNetworks = setOf(NETWORK),
                    ),
            )
        const val NETWORK = "ETHEREUM"
        const val SYMBOL = "USDC"
        const val VENDOR_ASSET_ID = "USDC_ERC20"
        const val TOKEN_CONTRACT = "0x1111111111111111111111111111111111111111"
        const val OWNER_A = "0x2222222222222222222222222222222222222222"
        const val OWNER_B = "0x3333333333333333333333333333333333333333"
        const val OWNER_C = "0x3333333333333333333333333333333333333334"
        const val SWEEP_CONTRACT = "0x4444444444444444444444444444444444444444"
        const val OMNIBUS_ADDRESS = "0x5555555555555555555555555555555555555555"
        const val ACCOUNT_A = "customer-a"
        const val ACCOUNT_B = "customer-b"
        const val ACCOUNT_C = "customer-c"
        const val VAULT_A = "vault-customer-a"
        const val VAULT_B = "vault-customer-b"
        const val VAULT_C = "vault-customer-c"
        const val OPERATOR_ID = "operator"
        const val OPERATOR_VAULT = "vault-operator"
        const val OMNIBUS_ID = "omnibus"
        const val OMNIBUS_VAULT = "vault-omnibus"
        const val EXECUTION_ID = "01987654-3210-7abc-8def-0123456789ab"
        const val EXECUTION_ID_2 = "01987654-3210-7abc-8def-0123456789ac"
        const val BATCH_EXTERNAL_ID = "swp-lifecycle"
        const val BATCH_EXTERNAL_ID_2 = "swp-lifecycle-2"
        const val BATCH_VENDOR_TX_ID = "vendor-batch"
        const val BATCH_VENDOR_TX_ID_2 = "vendor-swp-lifecycle-2"
        const val TRANSACTION_HASH = "0xbatch"
        const val TRANSACTION_HASH_2 = "0xbatch2"
        const val ZERO_FAILURE_CODE = "0000000000000000000000000000000000000000000000000000000000000000"
        const val FAILURE_CODE = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }
}

private class SequenceApprovalIds : SweepApprovalExternalTransactionIdGenerator {
    private var sequence = 0

    override fun nextId(): String = "swa-lifecycle-${++sequence}"
}

private class SequenceBatchExecutionIds : SweepExecutionIdGenerator {
    private val values = ArrayDeque(listOf("01987654-3210-7abc-8def-0123456789ab", "01987654-3210-7abc-8def-0123456789ac"))

    override fun nextId(): String = values.removeFirst()
}

private class SequenceBatchExternalIds : SweepExternalTransactionIdGenerator {
    private val values = ArrayDeque(listOf("swp-lifecycle", "swp-lifecycle-2"))

    override fun nextId(): String = values.removeFirst()
}

private class LifecycleAccounts : AccountRepository {
    private val accounts =
        listOf(
            Account("customer-a", AccountType.CUSTOMER, "ref-a", "vault-customer-a", "20260813090000"),
            Account("customer-b", AccountType.CUSTOMER, "ref-b", "vault-customer-b", "20260813090000"),
            Account("customer-c", AccountType.CUSTOMER, "ref-c", "vault-customer-c", "20260813090000"),
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
            "customer-c" to
                DepositAddress(
                    "customer-c",
                    "ETHEREUM",
                    "USDC",
                    "0x3333333333333333333333333333333333333334",
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
        symbol: String,
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
    var batchSubmission: (VendorContractCallRequest) -> VendorTransactionSubmission = ::acceptedSubmission

    override fun submitContractCall(request: VendorContractCallRequest): VendorTransactionSubmission {
        requests += request
        if (request.contractAddress == "0x4444444444444444444444444444444444444444") {
            return batchSubmission(request)
        }
        return acceptedSubmission(request)
    }

    private fun acceptedSubmission(request: VendorContractCallRequest): VendorTransactionSubmission {
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
