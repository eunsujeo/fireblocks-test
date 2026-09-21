package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.sweep.SweepInvalidationService
import com.whatto.bcm.app.application.sweep.SweepOutboxEventPublisher
import com.whatto.bcm.app.bat.reconciliation.TransactionReconciliationJob
import com.whatto.bcm.app.bat.reconciliation.TransactionReconciliationProperties
import com.whatto.bcm.app.bat.stall.TransactionalStallTerminalObservationHandler
import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.domain.sweep.SweepBatchCallItem
import com.whatto.bcm.domain.sweep.SweepEventPublisher
import com.whatto.bcm.domain.sweep.SweepEventSerializer
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItem
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.domain.sweep.SweepTarget
import com.whatto.bcm.domain.tx.BoostAttemptRepository
import com.whatto.bcm.domain.tx.TxReconciliationMissingWebhookAlertPort
import com.whatto.bcm.domain.tx.TxReconciliationReport
import com.whatto.bcm.domain.tx.TxReconciliationReportPort
import com.whatto.bcm.domain.tx.TxReconciliationRepository
import com.whatto.bcm.domain.tx.TxReconciliationTrackingStoppedAlertPort
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorContractCallRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.infra.client.evm.EvmErc20Client
import com.whatto.bcm.infra.client.evm.EvmRpcNetworkProperties
import com.whatto.bcm.infra.client.evm.EvmRpcProperties
import com.whatto.bcm.infra.client.fireblocks.FireblocksClient
import com.whatto.bcm.infra.client.fireblocks.FireblocksJwtSigner
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import com.whatto.bcm.infra.client.fireblocks.FireblocksStatusTranslator
import com.whatto.bcm.infra.client.fireblocks.PooledFireblocksRestClientFactory
import com.whatto.bcm.infra.persistence.boost.BoostJdbcAdapter
import com.whatto.bcm.infra.persistence.config.SpringTransactionRunner
import com.whatto.bcm.infra.persistence.event.OutboxJdbcAdapter
import com.whatto.bcm.infra.persistence.job.JobStateJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepAuthorizationJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepExecutionJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepTargetJdbcAdapter
import com.whatto.bcm.infra.persistence.sweep.SweepTransactionStatusJdbcAdapter
import com.whatto.bcm.infra.persistence.tx.TxCrudRepository
import com.whatto.bcm.infra.persistence.tx.TxJdbcAdapter
import com.whatto.bcm.support.submission.SweepBatchHashItem
import com.whatto.bcm.support.submission.SweepBatchRequestHashes
import com.whatto.bcm.testsupport.TestSupportApplication
import com.whatto.bcm.testsupport.chain.LocalChainConfiguration
import com.whatto.bcm.testsupport.chain.LocalChainEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

@SpringBootTest(classes = [LocalFireblocksSweepReconciliationIntegrationTest.TestApplication::class])
@Import(
    TxJdbcAdapter::class,
    BoostJdbcAdapter::class,
    OutboxJdbcAdapter::class,
    JobStateJdbcAdapter::class,
    SweepAuthorizationJdbcAdapter::class,
    SweepExecutionJdbcAdapter::class,
    SweepTargetJdbcAdapter::class,
    SweepTransactionStatusJdbcAdapter::class,
    SpringTransactionRunner::class,
)
class LocalFireblocksSweepReconciliationIntegrationTest : IntegrationTestSupport() {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJdbcRepositories(basePackageClasses = [TxCrudRepository::class])
    class TestApplication

    @Autowired
    lateinit var authorizations: SweepAuthorizationJdbcAdapter

    @Autowired
    lateinit var executions: SweepExecutionJdbcAdapter

    @Autowired
    lateinit var targets: SweepTargetJdbcAdapter

    @Autowired
    lateinit var transactionStatuses: SweepTransactionStatusJdbcAdapter

    @Autowired
    lateinit var transactionRunner: TransactionRunner

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactions: TxRecordRepository

    @Autowired
    lateinit var reconciliation: TxReconciliationRepository

    @Autowired
    lateinit var jobs: JobStateRepository

    @Autowired
    lateinit var outbox: OutboxEventRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var boosts: BoostAttemptRepository

    @BeforeEach
    fun setUp() {
        clearTables()
        resetStubAndChain()
        insertAdminSnapshot()
    }

    @AfterEach
    fun tearDown() {
        clearTables()
    }

    @Test
    fun `실제 부분 성공 batch sweep은 BCM 원장 대사 후 고객 이벤트 없이 수렴한다`() {
        val vendor = fireblocksClient()
        val firstVault = vendor.createVault("CUSTOMER:BAT-001", "createVault:bat-001")
        val secondVault = vendor.createVault("CUSTOMER:BAT-002", "createVault:bat-002")
        val operatorVault = vendor.createVault("SYSTEM:BAT-OPERATOR", "createVault:bat-operator")
        val firstAddress = vendor.createDepositAddress(firstVault.vaultId, TOKEN_ASSET_ID, "createWallet:bat-001").address
        val secondAddress = vendor.createDepositAddress(secondVault.vaultId, TOKEN_ASSET_ID, "createWallet:bat-002").address
        vendor.createDepositAddress(firstVault.vaultId, NATIVE_ASSET_ID, "createWallet:bat-001-native")
        vendor.createDepositAddress(secondVault.vaultId, NATIVE_ASSET_ID, "createWallet:bat-002-native")
        val operatorAddress =
            vendor.createDepositAddress(operatorVault.vaultId, NATIVE_ASSET_ID, "createWallet:bat-operator").address
        val accounts =
            LocalAccounts(
                listOf(
                    account(ACCOUNT_A, firstVault.vaultId),
                    account(ACCOUNT_B, secondVault.vaultId),
                ),
            )
        val accountByAddress = mapOf(firstAddress to ACCOUNT_A, secondAddress to ACCOUNT_B)
        val vaultByAddress = mapOf(firstAddress to firstVault.vaultId, secondAddress to secondVault.vaultId)
        val owners = accountByAddress.keys.sorted()
        val evm = evmClient()
        (owners zip listOf(AMOUNT, INSUFFICIENT_ALLOWANCE)).forEachIndexed { index, (owner, allowance) ->
            chain.setNativeBalance(owner, BigInteger.ZERO)
            val approval =
                vendor.submitContractCall(
                    VendorContractCallRequest(
                        externalTransactionId = "local-bcm-bat-gasless-approve-${index + 1}",
                        network = NETWORK,
                        sourceVaultId = checkNotNull(vaultByAddress[owner]),
                        contractAddress = chain.manifest.tokenContractAddress,
                        callData =
                            evm.approvalCallData(
                                chain.manifest.sweepContractAddress,
                                allowance,
                                chain.manifest.tokenDecimals,
                            ),
                        useGasless = true,
                    ),
                ) as VendorTransactionSubmission.Accepted
            completeTransaction(approval.transactionId)
        }
        chain.setNativeBalance(operatorAddress, BigInteger.ZERO)
        val callData =
            evm.batchSweepCallData(
                NETWORK,
                EXECUTION_ID,
                chain.manifest.tokenContractAddress,
                owners.map { SweepBatchCallItem(it, AMOUNT) },
            )
        val submission =
            vendor.submitContractCall(
                VendorContractCallRequest(
                    externalTransactionId = EXTERNAL_ID,
                    network = NETWORK,
                    sourceVaultId = operatorVault.vaultId,
                    contractAddress = chain.manifest.sweepContractAddress,
                    callData = callData,
                    useGasless = true,
                ),
            ) as VendorTransactionSubmission.Accepted
        completeTransaction(submission.transactionId)
        val transaction = checkNotNull(vendor.transaction(submission.transactionId))

        val fingerprint =
            SweepBatchRequestHashes.batchV1(
                NETWORK,
                SYMBOL,
                chain.manifest.tokenContractAddress,
                chain.manifest.sweepContractAddress,
                EXECUTION_ID,
                owners.map { SweepBatchHashItem(it, AMOUNT) },
            )
        val items =
            fingerprint.items.mapIndexed { index, item ->
                SweepItem(
                    EXECUTION_ID,
                    index + 1,
                    "sweep-request-${index + 1}",
                    "sweep-request-item-${index + 1}",
                    checkNotNull(accountByAddress[item.sourceAddress]),
                    item.sourceAddress,
                    item.amount,
                    null,
                    SweepItemStatus.READY,
                    null,
                    null,
                )
            }
        insertSweepRequests(items, vaultByAddress)
        items.forEach { item ->
            targets.insertIfAbsent(SweepTarget(item.accountId, NETWORK, SYMBOL, NOW, null, null, 0, null))
            authorizations.insert(
                SweepAuthorization(
                    SweepAuthorizationKey(item.accountId, NETWORK, SYMBOL, chain.manifest.sweepContractAddress),
                    AMOUNT,
                    AMOUNT,
                    SweepAuthorizationStatus.ACTIVE,
                    null,
                    null,
                    NOW,
                ),
            )
        }
        executions.createAndClaim(
            SweepExecution(
                EXECUTION_ID,
                EXTERNAL_ID,
                fingerprint.requestHash,
                NETWORK,
                SYMBOL,
                OPERATOR_ACCOUNT_ID,
                chain.manifest.sweepContractAddress,
                SweepExecutionStatus.READY,
                items.size,
                fingerprint.totalAmount,
                null,
                true,
                null,
                null,
                NOW,
                null,
                POLICY_VERSION_ID,
                POLICY_SNAPSHOT_HASH,
                CONTRACT_VERSION_ID,
                CONTRACT_EVIDENCE_ID,
            ),
            items,
        )
        executions.markSubmitting(EXECUTION_ID)
        executions.markSubmitted(EXECUTION_ID, submission.transactionId)
        executions.markReconciling(EXECUTION_ID, submission.transactionId, transaction.transactionHash)
        val alerts = mutableListOf<SweepExecutionAlert>()
        val service =
            SweepBatchReconciliationService(
                executions,
                vendor,
                evm,
                evm,
                LocalAssetMappings(chain.manifest.tokenContractAddress),
                accounts,
                vendor,
                targets,
                transactionStatuses,
                transactionRunner,
                SweepOutboxEventPublisher(
                    OutboxEventService(outbox),
                    SweepEventSerializer(objectMapper::writeValueAsString),
                    CLOCK,
                    5,
                ),
                { alert -> alerts += alert },
                CLOCK,
                SweepProperties(thresholds = listOf(SweepAssetThreshold(NETWORK, SYMBOL, "10", "20"))),
            )

        assertThat(service.runOnce()).isEqualTo(SweepReconciliationCycleResult(1, 0, 0))
        assertThat(executions.findById(EXECUTION_ID))
            .extracting("status", "actualTotalAmount")
            .containsExactly(SweepExecutionStatus.PARTIAL, AMOUNT)
        assertThat(executions.findItems(EXECUTION_ID).map { it.status })
            .containsExactlyInAnyOrder(SweepItemStatus.SUCCEEDED, SweepItemStatus.FAILED)
        assertThat(targets.findByKey(items[0].let { it.toKey() })?.activeSweepExecutionId).isNull()
        assertThat(targets.findByKey(items[1].let { it.toKey() })?.activeSweepExecutionId).isNull()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_outbox_l", Long::class.java)).isEqualTo(2)
        assertThat(
            jdbc.queryForList(
                "SELECT payload ->> 'chainStatus' AS chain_status, payload ->> 'itemOutcome' AS item_outcome FROM bcm_outbox_l ORDER BY evnt_id",
            ),
        ).extracting("chain_status", "item_outcome")
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple
                    .tuple("FINALIZED", "SUCCEEDED"),
                org.assertj.core.groups.Tuple
                    .tuple("FINALIZED", "FAILED"),
            )
        assertThat(alerts).isEmpty()
    }

    @Test
    fun `실제 완료 입금의 유실 Webhook은 tx 대사 단건 조회로 FINALIZED와 outbox를 회수한다`() {
        val vendor = fireblocksClient()
        val destination = vendor.createVault("CUSTOMER:BAT-RECONCILIATION", "createVault:bat-reconciliation")
        vendor.createDepositAddress(destination.vaultId, TOKEN_ASSET_ID, "createWallet:bat-reconciliation")
        val transactionId = createExternalDeposit(destination.vaultId)
        completeTransaction(transactionId)
        val completed = checkNotNull(vendor.transaction(transactionId))
        transactions.insert(
            TxRecord(
                vendorTxId = transactionId,
                externalTxId = null,
                accountId = ACCOUNT_A,
                network = NETWORK,
                symbol = SYMBOL,
                transactionHash = completed.transactionHash,
                lastPublishedStatus = TxStatus.CONFIRMED,
                confirmationCount = 1,
                // 관찰을 이미 통과한 행이다 — 첫 관찰 전 행만 vendorCreatedAt 을 비운다(03 V32).
                vendorCreatedAt = RECONCILIATION_DETECTED_AT,
                firstDetectedAt = RECONCILIATION_DETECTED_AT,
                lastChangedAt = RECONCILIATION_DETECTED_AT,
            ),
        )
        val reports = mutableListOf<TxReconciliationReport>()
        val handler =
            TransactionalStallTerminalObservationHandler(
                transactions = transactions,
                statusTranslator = FireblocksStatusTranslator { 1 },
                transactionRunner = transactionRunner,
                outbox = outbox,
                sweepExecutions = executions,
                sweepInvalidation = SweepInvalidationService(executions, SweepEventPublisher { error("not used") }),
                boosts = boosts,
                vendor = vendor,
                eventSerializer = ChainEventSerializer { "{\"txId\":\"${it.txId}\"}" },
                clock = CLOCK,
                outboxMaxAttempts = 5,
            )
        val job =
            TransactionReconciliationJob(
                vendor = vendor,
                reconciliation = reconciliation,
                statusTranslator = FireblocksStatusTranslator { 1 },
                terminalObservations = handler,
                reports = TxReconciliationReportPort(reports::add),
                missingWebhookAlerts = TxReconciliationMissingWebhookAlertPort { },
                trackingStoppedAlerts = TxReconciliationTrackingStoppedAlertPort { },
                metrics = NoOpOperationalMetricsPort,
                jobs = jobs,
                clock = CLOCK,
                properties = TransactionReconciliationProperties(enabled = true),
            )

        job.run()

        assertThat(transactions.findByVendorTxId(transactionId)?.lastPublishedStatus).isEqualTo(TxStatus.FINALIZED)
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_outbox_l"))
            .containsEntry("vndr_tx_id", transactionId)
            .containsEntry("evt_typ_dvcd", "TXCF")
            .containsEntry("topic", "deposit-events")
        assertThat(reports.single().recoveredCount).isEqualTo(1)
        assertThat(jobs.find("tx-reconciliation")?.lastSucceededAt).isEqualTo("20260819235500")
    }

    private fun SweepItem.toKey() =
        com.whatto.bcm.domain.sweep
            .SweepTargetKey(accountId, NETWORK, SYMBOL)

    private fun insertAdminSnapshot() {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr, release_cmit,
               artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash, deploy_blck_no,
               immut_payload, immut_hash, ceiling_payload, ceiling_hash, release_uri, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, 'SWEEP', 'local-v1', ?, 'local-commit', ?, ?, ?, '0xlocaldeploy', 1,
                    '{}'::jsonb, ?, '{}'::jsonb, ?, 'local://release', ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ctrt_vrsn_id) DO NOTHING
            """.trimIndent(),
            CONTRACT_VERSION_ID,
            CONTRACT_SCOPE_ID,
            NETWORK,
            chain.manifest.sweepContractAddress,
            "1".repeat(64),
            "2".repeat(64),
            chain.manifest.sweepCodeHash.removePrefix("0x"),
            "4".repeat(64),
            "5".repeat(64),
            NOW,
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
            VALUES (?, ?, ?, 31337, ?, ?, 1, 'RPC_A', 31337, ?, ?, ?, 'RPC_B', 31337, ?, ?, ?,
                    'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'VALID', ?, '20991231235959', '{}'::jsonb, ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (evdc_id) DO NOTHING
            """.trimIndent(),
            CONTRACT_EVIDENCE_ID,
            CONTRACT_VERSION_ID,
            "6".repeat(64),
            chain.manifest.sweepCodeHash.removePrefix("0x"),
            "4".repeat(64),
            chain.manifest.sweepCodeHash.removePrefix("0x"),
            "4".repeat(64),
            NOW,
            chain.manifest.sweepCodeHash.removePrefix("0x"),
            "4".repeat(64),
            NOW,
            NOW,
            "7".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_vrsn_l
              (plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn, ctrt_vrsn_id,
               plcy_payload, plcy_hash, ceiling_snps, ceiling_hash, ceiling_pass_yn, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 1, 'local-v1', ?,
                    '{"enabled":true,"minimumAmount":10,"batchSize":2,"allowanceCap":20,"itemAmountCap":50,"batchAmountCap":100,"boostAttempts":1}'::jsonb,
                    ?, '{}'::jsonb, ?, 'Y', ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (plcy_vrsn_id) DO NOTHING
            """.trimIndent(),
            POLICY_VERSION_ID,
            POLICY_SCOPE_ID,
            CONTRACT_VERSION_ID,
            "8".repeat(64),
            "9".repeat(64),
            NOW,
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_bind_m
              (ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id, bind_rvsn, last_evdc_id,
               bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 'SWEEP', ?, 1, ?, ?, ?, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ctrt_scope_id) DO NOTHING
            """.trimIndent(),
            CONTRACT_SCOPE_ID,
            NETWORK,
            CONTRACT_VERSION_ID,
            CONTRACT_EVIDENCE_ID,
            "b".repeat(64),
            NOW,
            NOW,
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_bind_m
              (plcy_scope_id, actv_plcy_vrsn_id, bind_rvsn, bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 1, ?, ?, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (plcy_scope_id) DO NOTHING
            """.trimIndent(),
            POLICY_SCOPE_ID,
            POLICY_VERSION_ID,
            POLICY_SNAPSHOT_HASH,
            NOW,
            NOW,
        )
    }

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_boost_l")
        jdbc.update("DELETE FROM bcm_tx_l")
        jdbc.update("DELETE FROM bcm_job_m")
        jdbc.update("DELETE FROM bcm_swp_trgt WHERE ntwk_cd = ?", NETWORK)
        jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", EXECUTION_ID)
        jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", EXECUTION_ID)
        jdbc.update("DELETE FROM bcm_swp_req_item_l WHERE swp_req_item_id LIKE 'sweep-request-item-%'")
        jdbc.update("DELETE FROM bcm_swp_req_l WHERE ext_swp_req_id LIKE 'local-bat-request-%'")
        jdbc.update("DELETE FROM bcm_swp_auth_m WHERE ntwk_cd = ?", NETWORK)
        jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id IN (?, ?)", ACCOUNT_A, ACCOUNT_B)
    }

    private fun insertSweepRequests(
        items: List<SweepItem>,
        vaultByAddress: Map<String, String>,
    ) {
        items.forEach { item ->
            jdbc.update(
                """
                INSERT INTO bcm_acnt_m
                  (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (?, 'CU', ?, ?, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
                """.trimIndent(),
                item.accountId,
                "ref-${item.accountId}",
                checkNotNull(vaultByAddress[item.sourceAddress]),
                NOW,
            )
            val requestId = "sweep-request-${item.sequence}"
            jdbc.update(
                """
                INSERT INTO bcm_swp_req_l
                  (swp_req_id, ext_swp_req_id, req_hash, ntwk_cd, tkn_smbl, swp_req_stcd,
                   item_cnt, req_dttm, fnsh_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (?, ?, ?, ?, ?, 'ACCEPTED', 1, ?, NULL,
                        'SYSTEM', '9999', 'SYSTEM', '9999')
                """.trimIndent(),
                requestId,
                "local-bat-request-${item.sequence}",
                "a".repeat(64),
                NETWORK,
                SYMBOL,
                NOW,
            )
            jdbc.update(
                """
                INSERT INTO bcm_swp_req_item_l
                  (swp_req_item_id, swp_req_id, item_seq, acnt_id, swp_req_item_stcd, last_fail_cd,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (?, ?, 1, ?, 'PENDING', NULL, 'SYSTEM', '9999', 'SYSTEM', '9999')
                """.trimIndent(),
                item.sweepRequestItemId,
                requestId,
                item.accountId,
            )
        }
    }

    private fun completeTransaction(transactionId: String) {
        repeat(2) {
            val response =
                HTTP.send(
                    HttpRequest
                        .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/transactions/$transactionId/advance"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            assertThat(response.statusCode()).isEqualTo(200)
        }
    }

    private fun createExternalDeposit(destinationVaultId: String): String {
        val response =
            HTTP.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/deposits"))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"externalTxId":"$RECONCILIATION_EXTERNAL_ID","assetId":"$TOKEN_ASSET_ID","destinationVaultId":"$destinationVaultId","amount":"1"}""",
                        ),
                    ).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
        return tools.jackson.databind
            .ObjectMapper()
            .readTree(response.body())
            .path("id")
            .asString()
    }

    private fun resetStubAndChain() {
        val response =
            HTTP.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$stubPort/__stub/reset"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
    }

    private fun fireblocksClient(): FireblocksClient {
        val key = privateKeyPem()
        val properties =
            FireblocksProperties(
                baseUrl = "http://127.0.0.1:$stubPort",
                apiKey = "bcm-local-stub",
                privateKeyPem = key,
                contractCallGasAssetIds = mapOf(NETWORK to NATIVE_ASSET_ID),
            )
        return FireblocksClient(
            RestClient.builder(),
            properties,
            FireblocksJwtSigner(properties.apiKey, key, Clock.systemUTC()),
            NoOpOperationalMetricsPort,
            PooledFireblocksRestClientFactory(),
        )
    }

    private fun evmClient() =
        EvmErc20Client(RestClient.builder(), EvmRpcProperties(mapOf(NETWORK to EvmRpcNetworkProperties(chain.rpcUrl))))

    private fun privateKeyPem(): String {
        val encoded =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .private.encoded
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }

    private fun account(
        id: String,
        vaultId: String,
    ) = Account(id, AccountType.CUSTOMER, id, vaultId, NOW)

    companion object {
        private const val NETWORK = "LOCAL"
        private const val SYMBOL = "TUSD"
        private const val TOKEN_ASSET_ID = "TUSD_LOCAL"
        private const val NATIVE_ASSET_ID = "ETH_LOCAL"
        private const val ACCOUNT_A = "local-sweep-a"
        private const val ACCOUNT_B = "local-sweep-b"
        private const val OPERATOR_ACCOUNT_ID = "local-sweep-operator"
        private const val EXECUTION_ID = "0198c7d5-7a30-7000-8000-000000000021"
        private const val EXTERNAL_ID = "local-bcm-bat-sweep-1"
        private const val RECONCILIATION_EXTERNAL_ID = "local-bcm-bat-reconciliation-deposit-1"
        private const val RECONCILIATION_DETECTED_AT = "20260819000000"
        private const val AMOUNT = "20"
        private const val INSUFFICIENT_ALLOWANCE = "10"
        private const val NOW = "20260820000000"
        private const val CONTRACT_SCOPE_ID = "LOCAL:SWEEP"
        private const val CONTRACT_VERSION_ID = "contract-LOCAL"
        private const val CONTRACT_EVIDENCE_ID = "evidence-LOCAL"
        private const val POLICY_SCOPE_ID = "POLICY:LOCAL:TUSD"
        private const val POLICY_VERSION_ID = "policy-LOCAL-TUSD"
        private val POLICY_SNAPSHOT_HASH = "a".repeat(64)
        private val RAW_AMOUNT = java.math.BigInteger("20000000")
        private val CLOCK = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC)
        private val HTTP = HttpClient.newHttpClient()
        private val runtimeDirectory: Path = Files.createTempDirectory("bcm-bat-local-fireblocks-")
        private val chain =
            LocalChainEnvironment.start(
                LocalChainConfiguration(
                    "bcm-bat-local-fireblocks-sweep-seed",
                    runtimeDirectory,
                    Path.of(requireNotNull(System.getProperty("bcm.contract-artifacts"))),
                ),
            )
        private val stub =
            SpringApplicationBuilder(TestSupportApplication::class.java).run(
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--management.server.port=0",
                "--spring.autoconfigure.exclude=" +
                    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "--bcm.test-support.vendor-mode=STUB",
                "--bcm.test-support.chain-mode=LOCAL",
                "--bcm.test-support.fireblocks-base-url=http://127.0.0.1:18080",
                "--bcm.test-support.fireblocks-api-key=bcm-local-stub",
                "--bcm.test-support.webhook-jwks-url=http://127.0.0.1:18080/.well-known/jwks.json",
                "--bcm.test-support.evm-rpc-url=${chain.rpcUrl}",
                "--bcm.test-support.evm-chain-id=31337",
                "--bcm.test-support.reset-enabled=true",
                "--bcm.test-support.local-chain-manifest-file=${runtimeDirectory.resolve("manifest.json")}",
                "--bcm.test-support.local-chain-key-file=${runtimeDirectory.resolve("evm-keys.json")}",
            )
        private val stubPort = requireNotNull(stub.environment.getProperty("local.server.port")).toInt()

        @JvmStatic
        @AfterAll
        fun closeEnvironment() {
            stub.close()
            chain.close()
            runtimeDirectory.toFile().deleteRecursively()
        }
    }
}

private class LocalAccounts(
    accounts: List<Account>,
) : AccountRepository {
    private val values = accounts.associateBy(Account::accountId)

    override fun insert(account: Account): Account = error("not used")

    override fun findByTypeAndRef(
        accountType: AccountType,
        ref: String,
    ): Account? = values.values.find { it.accountType == accountType && it.ref == ref }

    override fun findByAccountId(accountId: String): Account? = values[accountId]
}

private class LocalAssetMappings(
    tokenContract: String,
) : VendorAssetMappingRepository {
    private val mapping = VendorAssetMapping("LOCAL", "TUSD", "TUSD_LOCAL", tokenContract, "20260820000000", "SYSTEM", "9999")

    override fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping? = mapping.takeIf { it.network == network && it.symbol == symbol }

    override fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping? = mapping.takeIf { it.vendorAssetId == vendorAssetId }

    override fun findAll(
        network: String?,
        symbol: String?,
    ): List<VendorAssetMapping> =
        listOf(mapping).filter { (network == null || it.network == network) && (symbol == null || it.symbol == symbol) }

    override fun existsByNetwork(network: String): Boolean = mapping.network == network

    override fun insert(mapping: VendorAssetMapping): VendorAssetMapping = error("not used")

    override fun delete(
        network: String,
        symbol: String,
    ) = error("not used")
}
