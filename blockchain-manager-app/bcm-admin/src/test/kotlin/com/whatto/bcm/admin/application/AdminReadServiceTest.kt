package com.whatto.bcm.admin.application

import com.whatto.bcm.admin.client.AdminAssetMapping
import com.whatto.bcm.admin.client.AdminContract
import com.whatto.bcm.admin.client.AdminExecutionGate
import com.whatto.bcm.admin.client.AdminExecutionGateOverview
import com.whatto.bcm.admin.client.AdminExecutionGateResume
import com.whatto.bcm.admin.client.AdminExternalControlEvidence
import com.whatto.bcm.admin.client.AdminNetwork
import com.whatto.bcm.admin.client.AdminRuntimeReadiness
import com.whatto.bcm.admin.client.AdminSweepRuntime
import com.whatto.bcm.admin.client.AdminTransactionInvestigation
import com.whatto.bcm.admin.client.AdminTransactionInvestigationSummary
import com.whatto.bcm.admin.client.AdminWebhookRuntime
import com.whatto.bcm.admin.client.BcmAdminReadGateway
import com.whatto.bcm.admin.client.BcmWebhookHealthGateway
import com.whatto.bcm.admin.client.SourceFailure
import com.whatto.bcm.admin.config.AdminProperties
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdminReadServiceTest {
    private val gateway = mockk<BcmAdminReadGateway>()
    private val webhookHealthGateway = mockk<BcmWebhookHealthGateway>()
    private val clock = Clock.fixed(Instant.parse("2026-08-17T09:00:00Z"), ZoneOffset.UTC)
    private val service = AdminReadService(gateway, webhookHealthGateway, clock, staleAfterSeconds = 90_000)

    @Test
    fun `대시보드는 한 소스 실패를 부분 상태로 표시하고 성공한 값을 유지한다`() {
        every { gateway.networks(null, null, null, null) } returns
            listOf(network(code = "BASE", testnet = false), network(code = "SEPOLIA", testnet = true))
        every { gateway.assetMappings(null, null) } throws SourceFailure("assets", 502, "upstream unavailable")
        every { gateway.runtimeReadiness() } returns runtimeReadiness("HEALTHY")
        every { webhookHealthGateway.isReady() } returns true

        val result = service.overview()

        assertThat(result.state).isEqualTo(ViewState.PARTIAL)
        assertThat(result.catalogNetworkCount).isEqualTo(2)
        assertThat(result.adoptedNetworkCount).isEqualTo(2)
        assertThat(result.availableNetworkCount).isZero()
        assertThat(result.catalogSyncedAt).isEqualTo("20260817080000")
        assertThat(result.networkCount).isEqualTo(2)
        assertThat(result.testnetCount).isEqualTo(1)
        assertThat(result.assetMappingCount).isNull()
        assertThat(result.webhook?.state).isEqualTo("HEALTHY")
        assertThat(result.issues).extracting("source").containsExactly("assets")
        assertThat(result.preparationChecks.single { it.key == "BCM_WEBHOOK" }.status)
            .isEqualTo(PreparationStatus.ACTION_REQUIRED)
    }

    @Test
    fun `대시보드는 Fireblocks 카탈로그 후보와 채택 네트워크를 구분한다`() {
        every { gateway.networks(null, null, null, null) } returns
            listOf(
                network(code = "BASE"),
                network(code = null, candidateId = "polygon-amoy", syncedAt = "20260817083000"),
                network(code = null, candidateId = "deprecated", deprecated = true),
            )
        every { gateway.assetMappings(null, null) } returns emptyList()
        every { gateway.runtimeReadiness() } returns runtimeReadiness("NEVER_RECEIVED")
        every { webhookHealthGateway.isReady() } returns true

        val result = service.overview()

        assertThat(result.catalogNetworkCount).isEqualTo(3)
        assertThat(result.adoptedNetworkCount).isEqualTo(1)
        assertThat(result.availableNetworkCount).isEqualTo(1)
        assertThat(result.catalogSyncedAt).isEqualTo("20260817083000")
    }

    @Test
    fun `첫 수신 전에는 Webhook 장애로 단정하지 않고 점검 행동을 안내한다`() {
        every { gateway.networks(null, null, null, null) } returns emptyList()
        every { gateway.assetMappings(null, null) } returns emptyList()
        every { gateway.runtimeReadiness() } returns runtimeReadiness("NEVER_RECEIVED")
        every { webhookHealthGateway.isReady() } returns true

        val result = service.overview()
        val webhook = result.preparationChecks.single { it.key == "BCM_WEBHOOK" }
        val account = result.preparationChecks.single { it.key == "ACCOUNT_ADDRESS" }

        assertThat(webhook.status).isEqualTo(PreparationStatus.NOT_OBSERVED)
        assertThat(webhook.detail).contains("첫 입금 점검")
        assertThat(account.owner).isEqualTo(PreparationOwner.DIRECT)
        assertThat(account.detail).doesNotContain("DAW-CORE가")
    }

    @Test
    fun `로컬 Stub 준비 상태는 두 네트워크와 네 자산 매핑이 모두 있어야 완료다`() {
        every { gateway.networks(null, null, null, null) } returns
            listOf(network(code = "ETHEREUM"), network(code = "BASE"))
        every { gateway.assetMappings(null, null) } returns
            listOf(
                AdminAssetMapping("ETHEREUM", "USDC", "USDC_ETH_LOCAL", "0x01", "20260821000000"),
                AdminAssetMapping("ETHEREUM", "KRWK", "KRWK_ETH_LOCAL", "0x02", "20260821000000"),
                AdminAssetMapping("BASE", "USDC", "USDC_BASE_LOCAL", "0x03", "20260821000000"),
            )
        every { gateway.runtimeReadiness() } returns runtimeReadiness("NEVER_RECEIVED")
        every { webhookHealthGateway.isReady() } returns true
        val localService =
            AdminReadService(
                gateway,
                webhookHealthGateway,
                clock,
                staleAfterSeconds = 90_000,
                properties = AdminProperties(vendorMode = "STUB", chainMode = "LOCAL", dataSet = "stub"),
            )

        val result = localService.overview()

        assertThat(result.preparationChecks.single { it.key == "NETWORK_ADOPTION" }.status)
            .isEqualTo(PreparationStatus.READY)
        assertThat(result.preparationChecks.single { it.key == "ASSET_MAPPING" }.status)
            .isEqualTo(PreparationStatus.ACTION_REQUIRED)
        assertThat(result.preparationChecks.single { it.key == "ASSET_MAPPING" }.detail).contains("3/4")
    }

    @Test
    fun `과거 원장이 정상이어도 Webhook 프로세스 health 실패는 준비 완료로 표시하지 않는다`() {
        every { gateway.networks(null, null, null, null) } returns emptyList()
        every { gateway.assetMappings(null, null) } returns emptyList()
        every { gateway.runtimeReadiness() } returns runtimeReadiness("HEALTHY")
        every { webhookHealthGateway.isReady() } throws SourceFailure("webhookHealth", 502, "unavailable")

        val result = service.overview()
        val webhook = result.preparationChecks.single { it.key == "BCM_WEBHOOK" }

        assertThat(result.state).isEqualTo(ViewState.PARTIAL)
        assertThat(result.issues).extracting("source").contains("webhookHealth")
        assertThat(webhook.status).isEqualTo(PreparationStatus.ACTION_REQUIRED)
        assertThat(webhook.detail).contains("프로세스 health")
    }

    @Test
    fun `오래된 네트워크 동기화 시각은 stale로 판정한다`() {
        every { gateway.networks(null, null, null, null) } returns
            listOf(network(code = "BASE", syncedAt = "20260815000000"))

        val result = service.networks(NetworkFilters())

        assertThat(result.state).isEqualTo(ViewState.STALE)
        assertThat(result.data).hasSize(1)
    }

    @Test
    fun `자산 매핑 검색은 네트워크 필터 안에서 심볼과 컨트랙트 주소를 부분 검색한다`() {
        every { gateway.assetMappings("BASE", null) } returns
            listOf(
                AdminAssetMapping("BASE", "USDC", "USDC_BASE", "0x8335aBcD", "20260817080000"),
                AdminAssetMapping("BASE", "KRWK", "KRWK_BASE", "0x1234", "20260817080100"),
            )

        val byAddress = service.assets(AssetFilters(network = "BASE", q = "8335ab"))
        val bySymbol = service.assets(AssetFilters(network = "BASE", q = "usdc"))

        assertThat(byAddress.data).extracting("symbol").containsExactly("USDC")
        assertThat(bySymbol.data).extracting("contractAddress").containsExactly("0x8335aBcD")
    }

    @Test
    fun `통합 검색은 네트워크와 자산을 서버에서 합쳐 action과 금지 사유를 돌려준다`() {
        every { gateway.transactionInvestigation("base") } throws SourceFailure("transaction", 404, "not found")
        every { gateway.networks("base", null, null, null) } returns listOf(network(code = "BASE"))
        every { gateway.assetMappings(null, null) } returns
            listOf(AdminAssetMapping("BASE", "USDC", "USDC_BASE", "0x8335", "20260817080000"))

        val result = service.search("base")

        assertThat(result.data).extracting("kind").containsExactly(SearchKind.NETWORK, SearchKind.ASSET)
        assertThat(result.data).allSatisfy {
            assertThat(it.action.href).startsWith("/")
            assertThat(it.action.disabledReason).isNull()
        }
    }

    @Test
    fun `통합 검색은 컨트랙트 주소로 자산 매핑을 찾고 전체 주소를 근거로 보여준다`() {
        every { gateway.transactionInvestigation("0x8335") } throws SourceFailure("transaction", 404, "not found")
        every { gateway.networks("0x8335", null, null, null) } returns emptyList()
        every { gateway.assetMappings(null, null) } returns
            listOf(AdminAssetMapping("BASE", "USDC", "USDC_BASE", "0x8335aBcD", "20260817080000"))

        val result = service.search("0x8335")
        val asset = result.data.single()

        assertThat(result.data).hasSize(1)
        assertThat(asset.kind).isEqualTo(SearchKind.ASSET)
        assertThat(asset.secondary).contains("0x8335aBcD")
        assertThat(asset.action.href).contains("q=0x8335")
    }

    @Test
    fun `거래 식별자 검색은 root 거래 상세 action을 가장 먼저 돌려준다`() {
        every { gateway.transactionInvestigation("tx-new") } returns investigation(truncatedSources = listOf("WEBHOOK"))
        every { gateway.networks("tx-new", null, null, null) } returns emptyList()
        every { gateway.assetMappings(null, null) } returns emptyList()

        val result = service.search("tx-new")
        val transactionResult = result.data.single()

        assertThat(result.state).isEqualTo(ViewState.PARTIAL)
        assertThat(transactionResult.kind).isEqualTo(SearchKind.TRANSACTION)
        assertThat(transactionResult.action.href).isEqualTo("/admin/transactions/tx-root")
    }

    @Test
    fun `오래 갱신되지 않은 미종결 거래 상세은 stale로 판정한다`() {
        every { gateway.transactionInvestigation("tx-root") } returns
            investigation(lastChangedAt = "2026-08-15T00:00:00Z")

        val result = service.transaction("tx-root")

        assertThat(result.state).isEqualTo(ViewState.STALE)
        assertThat(result.data.summary.rootTransactionId).isEqualTo("tx-root")
    }

    @Test
    fun `컨트랙트 ERROR evidence는 성공한 목록과 함께 partial 근거를 표시한다`() {
        every { gateway.contracts() } returns
            listOf(
                AdminContract(
                    versionId = "contract-v1",
                    scopeId = "BASE:SWEEP",
                    network = "BASE",
                    use = "SWEEP",
                    version = "1.0.0",
                    address = "0xcontract",
                    state = "CANDIDATE",
                    runtimeCodeHash = "a".repeat(64),
                    evidenceStatus = "ERROR",
                    evidenceValidUntil = "2026-08-17T10:00:00Z",
                    active = false,
                ),
            )

        val result = service.contracts()

        assertThat(result.state).isEqualTo(ViewState.PARTIAL)
        assertThat(result.issues).extracting("code").containsExactly("CONTRACT_DRIFT")
    }

    @Test
    fun `실행 게이트 조회가 잘리면 성공한 원장과 partial 근거를 함께 표시한다`() {
        every { gateway.executionGates() } returns executionGates(truncated = true)

        val result = service.emergency()

        assertThat(result.state).isEqualTo(ViewState.PARTIAL)
        assertThat(result.data.gates).hasSize(1)
        assertThat(result.issues).extracting("code").containsExactly("EXECUTION_GATE_RESULT_TRUNCATED")
    }

    @Test
    fun `중지 상태는 조회 실패가 아니므로 fresh 원장으로 표시한다`() {
        every { gateway.executionGates() } returns executionGates(truncated = false)

        val result = service.emergency()
        val gate = result.data.gates.single()

        assertThat(result.state).isEqualTo(ViewState.FRESH)
        assertThat(gate.state).isEqualTo("STOPPED")
        assertThat(result.issues).isEmpty()
    }

    @Test
    fun `외부 통제 drift는 비상 운영을 partial 상태로 표시한다`() {
        every { gateway.executionGates() } returns
            executionGates(truncated = false).copy(
                externalControls = listOf(externalControl(status = "DRIFT", completionReady = false)),
            )

        val result = service.emergency()

        assertThat(result.state).isEqualTo(ViewState.PARTIAL)
        assertThat(result.issues).extracting("code").containsExactly("EXTERNAL_CONTROL_DRIFT")
    }

    @Test
    fun `재개 요청은 서버 계산 상태와 금지 사유를 보존해 partial로 알린다`() {
        every { gateway.executionGates() } returns
            executionGates(truncated = false).copy(
                resumes = listOf(resume(state = "BLOCKED", resumeReady = false)),
            )

        val result = service.emergency()

        assertThat(result.state).isEqualTo(ViewState.PARTIAL)
        assertThat(
            result.data.resumes
                .single()
                .disabledReasons,
        ).containsExactly("RESUME_CHECK_DRIFT")
        assertThat(result.issues).extracting("code").containsExactly("EXECUTION_GATE_RESUME_BLOCKED")
    }

    @Test
    fun `BCM 응답의 확인 증적이 만료됐으면 BFF도 stale로 내려 완료 처리하지 않는다`() {
        every { gateway.executionGates() } returns
            executionGates(truncated = false).copy(
                externalControls =
                    listOf(
                        externalControl(status = "CONFIRMED", completionReady = true).copy(
                            validUntil = "2026-08-17T09:00:00Z",
                        ),
                    ),
            )

        val result = service.emergency()
        val evidence = result.data.externalControls.single()

        assertThat(result.state).isEqualTo(ViewState.STALE)
        assertThat(evidence.status).isEqualTo("STALE")
        assertThat(evidence.completionReady).isFalse()
        assertThat(evidence.issues).contains("EVIDENCE_EXPIRED")
    }

    private fun network(
        code: String?,
        testnet: Boolean = false,
        syncedAt: String = "20260817080000",
        candidateId: String = "candidate-$code",
        deprecated: Boolean = false,
    ) = AdminNetwork(
        candidateId = candidateId,
        code = code,
        displayName = (code ?: candidateId).lowercase().replaceFirstChar(Char::uppercase),
        chainId = 8453,
        testnet = testnet,
        deprecated = deprecated,
        syncedAt = syncedAt,
    )

    private fun investigation(
        lastChangedAt: String = "2026-08-17T08:00:00Z",
        truncatedSources: List<String> = emptyList(),
    ) = AdminTransactionInvestigation(
        summary =
            AdminTransactionInvestigationSummary(
                rootTransactionId = "tx-root",
                activeTransactionId = "tx-new",
                externalTransactionId = "wd-1",
                transactionHash = "0xactive",
                accountId = "acct-1",
                network = "BASE",
                symbol = "USDC",
                transactionType = "WITHDRAWAL",
                status = "CONFIRMED",
                confirmationCount = 0,
                vendorSubStatus = "PENDING_BLOCKCHAIN_CONFIRMATIONS",
                vendorNetworkStatus = "CONFIRMING",
                submissionStatus = "SUBMITTED",
                amount = "30",
                senderAccountId = "acct-1",
                receiverType = "ADDRESS",
                receiverValue = "0xdestination",
                vendorCreatedAt = "2026-08-17T07:59:00Z",
                firstDetectedAt = "2026-08-17T08:00:00Z",
                lastChangedAt = lastChangedAt,
                reconciliationCheckCount = 2,
            ),
        timeline = emptyList(),
        boosts = emptyList(),
        sweepExecution = null,
        allowances = emptyList(),
        feeQuotes = emptyList(),
        truncatedSources = truncatedSources,
    )

    private fun executionGates(truncated: Boolean) =
        AdminExecutionGateOverview(
            observedAt = "2026-08-17T09:00:00Z",
            truncated = truncated,
            gates =
                listOf(
                    AdminExecutionGate(
                        network = "BASE",
                        type = "WITHDRAWAL",
                        state = "STOPPED",
                        stoppedAt = "2026-08-17T08:50:00Z",
                        reason = "이상 징후",
                        workTicket = "SEC-1061",
                        actorEmployeeNo = "810001",
                        sequence = 1,
                        newExecutionAllowed = false,
                        existingExecutionRecoveryAllowed = true,
                        emergencyRevocationAllowed = false,
                        disabledReasons = listOf("EXECUTION_GATE_STOPPED"),
                    ),
                ),
            externalControls = emptyList(),
            allowanceRevocations = emptyList(),
            webhookRecoveries = emptyList(),
            resumes = emptyList(),
        )

    private fun externalControl(
        status: String,
        completionReady: Boolean,
    ) = AdminExternalControlEvidence(
        evidenceId = "external-evidence-1",
        network = "BASE",
        contractVersionId = "contract-v1",
        status = status,
        completionReady = completionReady,
        snapshotHash = "a".repeat(64),
        tapSourceId = "tap-policy-api",
        tapBlocked = false,
        pinnedBlockNumber = "1234",
        expectedOperatorSetHash = "b".repeat(64),
        firstEndpointId = "rpc-a",
        firstPaused = true,
        firstOperatorSetHash = "b".repeat(64),
        secondEndpointId = "rpc-b",
        secondPaused = true,
        secondOperatorSetHash = "b".repeat(64),
        observedAt = "2026-08-17T08:55:00Z",
        validUntil = "2026-08-17T09:05:00Z",
        reason = "비상 외부 통제 확인",
        workTicket = "SEC-1062",
        actorEmployeeNo = "810001",
        issues = listOf("TAP_BATCH_NOT_BLOCKED"),
    )

    private fun resume(
        state: String,
        resumeReady: Boolean,
    ) = AdminExecutionGateResume(
        resumeId = "resume-1",
        requestId = "request-1",
        network = "BASE",
        type = "SWEEP",
        state = state,
        stoppedEventId = "stop-1",
        contractVersionId = "contract-v1",
        contractEvidenceId = "evidence-v1",
        revocationExecutionId = "revocation-1",
        causeEvidenceUri = "evidence://incident/root-cause",
        causeEvidenceHash = "c".repeat(64),
        requestedAt = "2026-08-17T08:50:00Z",
        expiresAt = "2026-08-17T10:00:00Z",
        requestedByEmployeeNo = "810001",
        approvalCount = 2,
        requiredApprovals = 2,
        securityApprovalCount = 1,
        latestCheckStatus = "DRIFT",
        latestCheckObservedAt = "2026-08-17T08:59:00Z",
        latestCheckValidUntil = "2026-08-17T09:04:00Z",
        issues = listOf("RPC_2_PAUSED"),
        resumeReady = resumeReady,
        disabledReasons = listOf("RESUME_CHECK_DRIFT"),
        retryable = true,
        retryCondition = "RESUME_CHECK_DRIFT",
        statusPath = "/admin/execution-gates",
    )

    private fun runtimeReadiness(state: String) =
        AdminRuntimeReadiness(
            observedAt = "2026-08-17T09:00:00Z",
            webhook =
                AdminWebhookRuntime(
                    state = state,
                    lastReceivedAt = null,
                    pendingInboxCount = 0,
                    poisonedInboxCount = 0,
                    pendingOutboxCount = 0,
                    poisonedOutboxCount = 0,
                    statusPath = "/admin/emergency",
                ),
            sweep =
                AdminSweepRuntime(
                    enabled = false,
                    state = "DISABLED",
                    activeContractCount = 0,
                    activePolicyCount = 0,
                    executorLastRunAt = null,
                    executorLastSucceededAt = null,
                    disabledReasons = listOf("SWEEP_EXECUTOR_NOT_OBSERVED"),
                ),
        )
}
