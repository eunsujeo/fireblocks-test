package com.whatto.bcm.admin.application

import com.whatto.bcm.admin.config.AdminProperties
import com.whatto.bcm.admin.config.SystemTestProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SystemTestRunReaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `실행 상세는 단계에서 진행률을 다시 계산하고 허용 필드만 반환한다`() {
        writeRun("run-failed", state = "FAILED", startedAt = "2026-08-20T08:00:00.000Z")
        val reader = reader()

        val result = reader.run("run-failed")

        assertThat(result.state).isEqualTo(ViewState.FRESH)
        assertThat(result.data.progress.completedSteps).isEqualTo(1)
        assertThat(result.data.progress.totalSteps).isEqualTo(2)
        assertThat(result.data.progress.percent).isEqualTo(50)
        assertThat(result.data.failure?.code).isEqualTo("INTERNAL_TEST_FAILURE")
        assertThat(result.data.relatedIds.externalTxId).isEqualTo("ext-1")
        assertThat(result.data.relatedIds.transactionHref).isEqualTo("/admin/transactions/ext-1")
        assertThat(result.data.steps[1].classification).containsExactly("SIMULATED_VENDOR")
        assertThat(result.data.steps[1].observations).extracting("value").containsExactly("ext-1", "ext-2")
        val responseJson = ObjectMapper().writeValueAsString(result.data)
        assertThat(responseJson).doesNotContain("runnerPid", "rawPayload", "privateKey", "component.log")
    }

    @Test
    fun `목록은 최신 20건만 반환하고 깨진 실행을 부분 조회로 격리한다`() {
        repeat(22) { index ->
            writeRun(
                runId = "run-${index.toString().padStart(2, '0')}",
                state = "PASSED",
                startedAt = "2026-08-20T08:${index.toString().padStart(2, '0')}:00.000Z",
            )
        }
        Files.createDirectories(tempDir.resolve("broken"))
        Files.writeString(tempDir.resolve("broken/run.json"), "{not-json")

        val result = reader().runs()

        assertThat(result.state).isEqualTo(ViewState.PARTIAL)
        assertThat(result.data.runs).hasSize(20)
        assertThat(
            result.data.runs
                .first()
                .runId,
        ).isEqualTo("run-21")
        assertThat(
            result.data.runs
                .last()
                .runId,
        ).isEqualTo("run-02")
        assertThat(result.data.unreadableRunCount).isEqualTo(1)
        assertThat(result.issues).extracting("code").containsExactly("CORRUPT_RUN_ARTIFACT")
    }

    @Test
    fun `경로 탈출 runId와 symlink 실행은 읽지 않는다`() {
        assertThatThrownBy { reader().run("../escape") }
            .isInstanceOf(SystemTestRunNotFound::class.java)

        val outside = Files.createTempDirectory("bcm-system-test-outside")
        try {
            writeRunAt(outside, "escaped", "PASSED", "2026-08-20T08:00:00.000Z")
            Files.createSymbolicLink(tempDir.resolve("escaped"), outside.resolve("escaped"))

            assertThatThrownBy { reader().run("escaped") }
                .isInstanceOf(SystemTestRunNotFound::class.java)
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `상태와 단계가 모순된 성공 원장은 읽기 오류로 거절한다`() {
        writeRun("inconsistent", state = "PASSED", startedAt = "2026-08-20T08:00:00.000Z", secondStep = "FAILED")

        assertThatThrownBy { reader().run("inconsistent") }
            .isInstanceOf(SystemTestRunUnreadable::class.java)
    }

    @Test
    fun `허용시간 동안 갱신되지 않은 진행 실행은 실패 상태와 복구 안내로 반환한다`() {
        writeRun(
            "run-stale",
            state = "RUNNING",
            startedAt = "2026-08-20T08:00:00.000Z",
            updatedAt = "2026-08-20T08:30:00.000Z",
        )

        val result = reader(staleAfterSeconds = 300).run("run-stale")

        assertThat(result.state).isEqualTo(ViewState.STALE)
        assertThat(result.issues).extracting("code").containsExactly("STALE_RUN_ARTIFACT")
        assertThat(result.data.state).isEqualTo("FAILED")
        assertThat(result.data.summary.failureCode).isEqualTo("STALE_RUN_ARTIFACT")
        assertThat(result.data.failure?.retryable).isTrue()
        assertThat(result.data.failure?.nextAction).contains("system-test.sh status")
    }

    @Test
    fun `목록의 stale 진행 실행도 실패 상태로 표시한다`() {
        writeRun(
            "run-stale-list",
            state = "RUNNING",
            startedAt = "2026-08-20T08:00:00.000Z",
            updatedAt = "2026-08-20T08:30:00.000Z",
        )

        val result = reader(staleAfterSeconds = 300).runs()

        assertThat(result.state).isEqualTo(ViewState.STALE)
        assertThat(result.data.runs).hasSize(1)
        val summary = result.data.runs.single()
        assertThat(summary.state).isEqualTo("FAILED")
        assertThat(summary.failureCode).isEqualTo("STALE_RUN_ARTIFACT")
        assertThat(summary.retryable).isTrue()
    }

    @Test
    fun `종결 실행은 갱신시각이 오래되어도 stale로 오판하지 않는다`() {
        writeRun(
            "run-completed",
            state = "PASSED",
            startedAt = "2026-08-20T08:00:00.000Z",
            updatedAt = "2026-08-20T08:30:00.000Z",
        )

        assertThat(reader(staleAfterSeconds = 300).run("run-completed").state).isEqualTo(ViewState.FRESH)
    }

    @Test
    fun `로컬 시나리오는 생성한 account와 주소만 안전한 식별자로 반환한다`() {
        writeRun(
            "run-scenario",
            state = "PASSED",
            startedAt = "2026-08-20T08:00:00.000Z",
            suite = "SCENARIO",
            relatedIds = """{"accountId":"acct-local-1","address":"0x1234"}""",
        )

        val detail = reader().run("run-scenario").data

        assertThat(detail.suite).isEqualTo("SCENARIO")
        assertThat(detail.relatedIds.accountId).isEqualTo("acct-local-1")
        assertThat(detail.relatedIds.address).isEqualTo("0x1234")
        assertThat(detail.relatedIds.transactionHref).isNull()
    }

    private fun reader(staleAfterSeconds: Long = 900) =
        SystemTestRunReader(
            AdminProperties(
                systemTest =
                    SystemTestProperties(
                        enabled = true,
                        stateDirectory = tempDir.toString(),
                        staleAfterSeconds = staleAfterSeconds,
                    ),
            ),
            ObjectMapper(),
            Clock.fixed(Instant.parse("2026-08-20T09:00:00Z"), ZoneOffset.UTC),
        )

    private fun writeRun(
        runId: String,
        state: String,
        startedAt: String,
        secondStep: String = defaultStepState(state),
        updatedAt: String = "2026-08-20T08:30:00.000Z",
        suite: String = "SMOKE",
        relatedIds: String = """{"externalTxId":"ext-1","vendorTxId":"vendor-1","txHash":"0x01"}""",
    ) = writeRunAt(tempDir, runId, state, startedAt, secondStep, updatedAt, suite, relatedIds)

    private fun writeRunAt(
        root: Path,
        runId: String,
        state: String,
        startedAt: String,
        secondStep: String = defaultStepState(state),
        updatedAt: String = "2026-08-20T08:30:00.000Z",
        suite: String = "SMOKE",
        relatedIds: String = """{"externalTxId":"ext-1","vendorTxId":"vendor-1","txHash":"0x01"}""",
    ) {
        val directory = Files.createDirectories(root.resolve(runId))
        val failureJson =
            if (state == "FAILED" || state == "ABORTED") {
                """{"code":"INTERNAL_TEST_FAILURE","message":"안전한 실패 요약","failedStep":"internal-scenario","retryable":true,"nextAction":"runner.log 확인"}"""
            } else {
                "null"
            }
        val completedAt = if (state == "RUNNING" || state == "PENDING") "null" else "\"$updatedAt\""
        val currentStep = if (state == "RUNNING") "\"internal-scenario\"" else "null"
        Files.writeString(
            directory.resolve("run.json"),
            """
            {
              "schemaVersion": 1,
              "runId": "$runId",
              "suite": "$suite",
              "state": "$state",
              "startedAt": "$startedAt",
              "updatedAt": "$updatedAt",
              "completedAt": $completedAt,
              "currentStep": $currentStep,
              "lastSuccessfulStep": "ledger-initialized",
              "progress": {"completedSteps": 2, "totalSteps": 2, "percent": 100},
              "steps": [
                {"id":"ledger-initialized","name":"실행 원장 초기화","state":"PASSED","startedAt":"$startedAt","completedAt":"$startedAt","durationMs":1,"failure":null,"classification":["REAL_LOCAL"],"observations":[]},
                {"id":"internal-scenario","name":"테스트 시나리오","state":"$secondStep","startedAt":"$startedAt","completedAt":"$startedAt","durationMs":2,"failure":null,"classification":["SIMULATED_VENDOR"],"observations":[{"type":"externalTxId","value":"ext-1","observedAt":"$startedAt"},{"type":"externalTxId","value":"ext-2","observedAt":"$startedAt"}]}
              ],
              "components": [{"name":"bcm-api","state":"UP","observedAt":"2026-08-20T08:10:00.000Z"}],
              "classification": ["REAL_LOCAL", "SIMULATED_VENDOR"],
              "relatedIds": $relatedIds,
              "failure": $failureJson,
              "runnerPid": 999,
              "rawPayload": "must-not-leak",
              "privateKey": "must-not-leak"
            }
            """.trimIndent(),
        )
    }

    private fun defaultStepState(runState: String): String =
        when (runState) {
            "PASSED" -> "PASSED"
            "RUNNING" -> "RUNNING"
            else -> "FAILED"
        }
}
