package com.whatto.bcm.admin.api

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.admin.application.AdminAction
import com.whatto.bcm.admin.application.AdminOverview
import com.whatto.bcm.admin.application.AdminReadService
import com.whatto.bcm.admin.application.NetworkFilters
import com.whatto.bcm.admin.application.SearchKind
import com.whatto.bcm.admin.application.SearchResult
import com.whatto.bcm.admin.application.ViewResult
import com.whatto.bcm.admin.application.ViewState
import com.whatto.bcm.admin.client.AdminExecutionGate
import com.whatto.bcm.admin.client.AdminExecutionGateOverview
import com.whatto.bcm.admin.client.AdminTransactionInvestigation
import com.whatto.bcm.admin.client.AdminTransactionInvestigationSummary
import com.whatto.bcm.admin.client.SourceFailure
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant

@WebMvcTest(AdminBffController::class, AdminBffExceptionHandler::class)
class AdminBffControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: AdminReadService

    @MockkBean
    private lateinit var clock: Clock

    @Test
    fun `overview는 상태와 기준 시각을 응답한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-17T09:00:00Z")
        every { service.overview() } returns
            AdminOverview(
                generatedAt = "2026-08-17T09:00:00Z",
                state = ViewState.FRESH,
                catalogNetworkCount = 3,
                adoptedNetworkCount = 2,
                availableNetworkCount = 1,
                catalogSyncedAt = "20260817080000",
                networkCount = 2,
                testnetCount = 1,
                assetMappingCount = 3,
                webhook = null,
                preparationChecks = emptyList(),
                issues = emptyList(),
            )

        mockMvc
            .perform(get("/bff/admin/overview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.catalogNetworkCount").value(3))
            .andExpect(jsonPath("$.data.adoptedNetworkCount").value(2))
            .andExpect(jsonPath("$.data.networkCount").value(2))
            .andExpect(jsonPath("$.data.state").value("FRESH"))
            .andExpect(jsonPath("$.meta.generatedAt").value("2026-08-17T09:00:00Z"))
    }

    @Test
    fun `네트워크 필터는 URL 쿼리 그대로 서비스에 전달한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-17T09:00:00Z")
        val filters = NetworkFilters("base", 8453, true, false)
        every { service.networks(filters) } returns ViewResult(emptyList(), ViewState.FRESH, emptyList())

        mockMvc
            .perform(
                get("/bff/admin/networks")
                    .param("q", "base")
                    .param("chainId", "8453")
                    .param("adopted", "true")
                    .param("testnet", "false"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)

        verify(exactly = 1) { service.networks(filters) }
    }

    @Test
    fun `검색 결과는 서버가 결정한 이동 action을 포함한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-17T09:00:00Z")
        every { service.search("USDC") } returns
            ViewResult(
                listOf(SearchResult(SearchKind.ASSET, "BASE / USDC", "자산 매핑", AdminAction("/assets?symbol=USDC"))),
                ViewState.FRESH,
                emptyList(),
            )

        mockMvc
            .perform(get("/bff/admin/search").param("q", "USDC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].action.href").value("/assets?symbol=USDC"))
    }

    @Test
    fun `상류 403은 forbidden 상태로 보존한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-17T09:00:00Z")
        every { service.networks(any()) } throws SourceFailure("networks", 403, "forbidden")

        mockMvc
            .perform(get("/bff/admin/networks"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
    }

    @Test
    fun `거래 식별자는 상세 조회 서비스에 전달하고 root와 active를 구분해 응답한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-17T09:00:00Z")
        every { service.transaction("tx-new") } returns
            ViewResult(investigation(), ViewState.FRESH, emptyList())

        mockMvc
            .perform(get("/bff/admin/transactions/tx-new"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.summary.rootTransactionId").value("tx-root"))
            .andExpect(jsonPath("$.data.summary.activeTransactionId").value("tx-new"))

        verify(exactly = 1) { service.transaction("tx-new") }
    }

    @Test
    fun `비상 운영 조회는 서버 계산 실행 가능 상태를 그대로 응답한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-17T09:00:00Z")
        val overview =
            AdminExecutionGateOverview(
                observedAt = "2026-08-17T08:59:00Z",
                truncated = false,
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
        every { service.emergency() } returns ViewResult(overview, ViewState.FRESH, emptyList())

        mockMvc
            .perform(get("/bff/admin/emergency"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.gates[0].state").value("STOPPED"))
            .andExpect(jsonPath("$.data.gates[0].newExecutionAllowed").value(false))
            .andExpect(jsonPath("$.data.gates[0].existingExecutionRecoveryAllowed").value(true))

        verify(exactly = 1) { service.emergency() }
    }

    private fun investigation() =
        AdminTransactionInvestigation(
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
                    vendorCreatedAt = "2026-08-17T08:00:00Z",
                    firstDetectedAt = "2026-08-17T08:00:10Z",
                    lastChangedAt = "2026-08-17T08:07:00Z",
                    reconciliationCheckCount = 2,
                ),
            timeline = emptyList(),
            boosts = emptyList(),
            sweepExecution = null,
            allowances = emptyList(),
            feeQuotes = emptyList(),
            truncatedSources = emptyList(),
        )
}
