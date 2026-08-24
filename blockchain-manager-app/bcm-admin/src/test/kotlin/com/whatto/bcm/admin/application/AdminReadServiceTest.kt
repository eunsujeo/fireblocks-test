package com.whatto.bcm.admin.application

import com.whatto.bcm.admin.client.AdminAssetMapping
import com.whatto.bcm.admin.client.AdminContract
import com.whatto.bcm.admin.client.AdminExecutionGate
import com.whatto.bcm.admin.client.AdminExecutionGateOverview
import com.whatto.bcm.admin.client.AdminExecutionGateResume
import com.whatto.bcm.admin.client.AdminExternalControlEvidence
import com.whatto.bcm.admin.client.AdminNetwork
import com.whatto.bcm.admin.client.AdminTransactionInvestigation
import com.whatto.bcm.admin.client.AdminTransactionInvestigationSummary
import com.whatto.bcm.admin.client.BcmAdminReadGateway
import com.whatto.bcm.admin.client.SourceFailure
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdminReadServiceTest {
    private val gateway = mockk<BcmAdminReadGateway>()
    private val clock = Clock.fixed(Instant.parse("2026-08-17T09:00:00Z"), ZoneOffset.UTC)
    private val service = AdminReadService(gateway, clock, staleAfterSeconds = 90_000)

    @Test
    fun `대시보드는 한 소스 실패를 부분 상태로 표시하고 성공한 값을 유지한다`() {
        every { gateway.networks(null, null, true, null) } returns
            listOf(network(code = "BASE", testnet = false), network(code = "SEPOLIA", testnet = true))
        every { gateway.assetMappings(null, null) } throws SourceFailure("assets", 502, "upstream unavailable")

        val result = service.overview()

        assertThat(result.state).isEqualTo(ViewState.PARTIAL)
        assertThat(result.networkCount).isEqualTo(2)
        assertThat(result.testnetCount).isEqualTo(1)
        assertThat(result.assetMappingCount).isNull()
        assertThat(result.issues).extracting("source").containsExactly("assets")
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
    fun `통합 검색은 네트워크와 자산을 서버에서 합쳐 action과 금지 사유를 돌려준다`() {
        every { gateway.transactionInvestigation("base") } throws SourceFailure("transaction", 404, "not found")
        every { gateway.networks("base", null, null, null) } returns listOf(network(code = "BASE"))
        every { gateway.assetMappings(null, null) } returns
            listOf(AdminAssetMapping("BASE", "USDC", "0x8335", "20260817080000"))

        val result = service.search("base")

        assertThat(result.data).extracting("kind").containsExactly(SearchKind.NETWORK, SearchKind.ASSET)
        assertThat(result.data).allSatisfy {
            assertThat(it.action.href).startsWith("/")
            assertThat(it.action.disabledReason).isNull()
        }
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
        code: String,
        testnet: Boolean = false,
        syncedAt: String = "20260817080000",
    ) = AdminNetwork(
        candidateId = "candidate-$code",
        code = code,
        displayName = code.lowercase().replaceFirstChar(Char::uppercase),
        chainId = 8453,
        testnet = testnet,
        deprecated = false,
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
}
