package com.whatto.bcm.admin.api

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.admin.application.LocalScenarioCatalog
import com.whatto.bcm.admin.application.LocalScenarioCommandService
import com.whatto.bcm.admin.application.LocalScenarioConflict
import com.whatto.bcm.admin.application.LocalScenarioDefinition
import com.whatto.bcm.admin.application.LocalScenarioStarted
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant

@WebMvcTest(LocalScenarioBffController::class, LocalScenarioBffExceptionHandler::class)
@TestPropertySource(properties = ["bcm.admin.local-scenario.enabled=true"])
class LocalScenarioBffControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: LocalScenarioCommandService

    @MockkBean
    private lateinit var clock: Clock

    @Test
    fun `시나리오 카탈로그를 조회한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-21T03:00:00Z")
        every { service.catalog() } returns
            LocalScenarioCatalog(
                listOf(LocalScenarioDefinition("asset-catalog", "로컬 자산 준비", "설명", "약 1분", listOf("REAL_LOCAL"), emptyList())),
            )

        mockMvc
            .perform(get("/bff/admin/test-scenarios"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.scenarios[0].id").value("asset-catalog"))
    }

    @Test
    fun `고객 vault 시나리오는 실행 원장 링크를 즉시 반환한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-21T03:00:00Z")
        every { service.start("customer-vault", any()) } returns
            LocalScenarioStarted("vault-20260821T030000-a1b2c3d4", "/admin/test-runs/vault-20260821T030000-a1b2c3d4")

        mockMvc
            .perform(
                post("/bff/admin/test-scenarios/customer-vault/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Origin", "http://localhost")
                    .header("X-BCM-Local-Scenario", "execute")
                    .content("""{"ref":"DAW-10001","symbol":"USDC"}"""),
            ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.data.runId").value("vault-20260821T030000-a1b2c3d4"))
    }

    @Test
    fun `진행 중 실행이 있으면 새 실행을 거부한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-21T03:00:00Z")
        every { service.start("smoke", any()) } throws LocalScenarioConflict()

        mockMvc
            .perform(
                post("/bff/admin/test-scenarios/smoke/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Origin", "http://localhost")
                    .header("X-BCM-Local-Scenario", "execute")
                    .content("{}"),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("SCENARIO_ALREADY_RUNNING"))
    }

    @Test
    fun `외부 Origin은 로컬 시나리오를 시작하지 못한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-21T03:00:00Z")

        mockMvc
            .perform(
                post("/bff/admin/test-scenarios/smoke/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Origin", "https://evil.example")
                    .header("X-BCM-Local-Scenario", "execute")
                    .content("{}"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("SCENARIO_REQUEST_FORBIDDEN"))

        verify(exactly = 0) { service.start(any(), any()) }
    }

    @Test
    fun `전용 요청 헤더가 없으면 로컬 시나리오를 시작하지 못한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-21T03:00:00Z")

        mockMvc
            .perform(
                post("/bff/admin/test-scenarios/smoke/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Origin", "http://localhost")
                    .content("{}"),
            ).andExpect(status().isForbidden)

        verify(exactly = 0) { service.start(any(), any()) }
    }

    @Test
    fun `외부 Host와 일치하는 Origin도 로컬 시나리오를 시작하지 못한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-21T03:00:00Z")

        mockMvc
            .perform(
                post("/bff/admin/test-scenarios/smoke/runs")
                    .with { request -> request.also { it.serverName = "evil.example" } }
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Origin", "http://evil.example")
                    .header("X-BCM-Local-Scenario", "execute")
                    .content("{}"),
            ).andExpect(status().isForbidden)

        verify(exactly = 0) { service.start(any(), any()) }
    }

    @Test
    fun `단순 form POST는 로컬 시나리오를 시작하지 못한다`() {
        mockMvc
            .perform(post("/bff/admin/test-scenarios/smoke/runs").contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(status().isUnsupportedMediaType)

        verify(exactly = 0) { service.start(any(), any()) }
    }
}
