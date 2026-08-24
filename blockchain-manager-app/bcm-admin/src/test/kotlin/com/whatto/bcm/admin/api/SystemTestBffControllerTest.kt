package com.whatto.bcm.admin.api

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.admin.application.SystemTestProgress
import com.whatto.bcm.admin.application.SystemTestRelatedIds
import com.whatto.bcm.admin.application.SystemTestRunCollection
import com.whatto.bcm.admin.application.SystemTestRunDetail
import com.whatto.bcm.admin.application.SystemTestRunNotFound
import com.whatto.bcm.admin.application.SystemTestRunReader
import com.whatto.bcm.admin.application.SystemTestRunSummary
import com.whatto.bcm.admin.application.ViewResult
import com.whatto.bcm.admin.application.ViewState
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@WebMvcTest(SystemTestBffController::class, SystemTestBffExceptionHandler::class)
@TestPropertySource(properties = ["bcm.admin.system-test.enabled=true"])
class SystemTestBffControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var reader: SystemTestRunReader

    @MockkBean
    private lateinit var clock: Clock

    @Test
    fun `실행 목록과 상세는 로컬 진단 DTO만 응답한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-20T09:00:00Z")
        every { clock.zone } returns ZoneOffset.UTC
        val summary = summary()
        every { reader.runs() } returns ViewResult(SystemTestRunCollection(listOf(summary), 0), ViewState.FRESH, emptyList())
        every { reader.run("run-1") } returns ViewResult(detail(summary), ViewState.FRESH, emptyList())

        mockMvc
            .perform(get("/bff/admin/test-runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.runs[0].runId").value("run-1"))
            .andExpect(jsonPath("$.data.runs[0].progress.percent").value(50))

        mockMvc
            .perform(get("/bff/admin/test-runs/run-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.relatedIds.externalTxId").value("ext-1"))
            .andExpect(jsonPath("$.data.relatedIds.transactionHref").value("/admin/transactions/ext-1"))
            .andExpect(jsonPath("$.data.runnerPid").doesNotExist())
            .andExpect(jsonPath("$.data.rawPayload").doesNotExist())
    }

    @Test
    fun `없는 실행은 내부 경로 없이 404를 응답한다`() {
        every { clock.instant() } returns Instant.parse("2026-08-20T09:00:00Z")
        every { reader.run("missing") } throws SystemTestRunNotFound("missing")

        mockMvc
            .perform(get("/bff/admin/test-runs/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("TEST_RUN_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("테스트 실행을 찾을 수 없습니다."))
    }

    private fun summary() =
        SystemTestRunSummary(
            runId = "run-1",
            suite = "SMOKE",
            state = "FAILED",
            startedAt = "2026-08-20T08:00:00Z",
            updatedAt = "2026-08-20T08:01:00Z",
            completedAt = "2026-08-20T08:01:00Z",
            currentStep = null,
            lastSuccessfulStep = "ledger-initialized",
            progress = SystemTestProgress(1, 2, 50),
            failureCode = "INTERNAL_TEST_FAILURE",
            failedStep = "internal-scenario",
            retryable = true,
        )

    private fun detail(summary: SystemTestRunSummary) =
        SystemTestRunDetail(
            summary = summary,
            steps = emptyList(),
            components = emptyList(),
            classification = listOf("REAL_LOCAL", "SIMULATED_VENDOR"),
            relatedIds = SystemTestRelatedIds(externalTxId = "ext-1", transactionHref = "/admin/transactions/ext-1"),
            failure = null,
            artifactPath = "/safe/build/system-test/run-1",
        )
}

@WebMvcTest(SystemTestBffController::class, SystemTestBffExceptionHandler::class)
class SystemTestBffDisabledTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var clock: Clock

    @Test
    fun `진단이 비활성이면 BFF route가 존재하지 않는다`() {
        mockMvc.perform(get("/bff/admin/test-runs")).andExpect(status().isNotFound)
    }
}
