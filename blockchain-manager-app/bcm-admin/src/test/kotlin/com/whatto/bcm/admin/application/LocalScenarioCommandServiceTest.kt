package com.whatto.bcm.admin.application

import com.whatto.bcm.admin.config.AdminProperties
import com.whatto.bcm.admin.config.LocalScenarioProperties
import com.whatto.bcm.admin.config.SystemTestProperties
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LocalScenarioCommandServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `고정 실행기에 검증한 입력만 전달하고 원장 링크를 반환한다`() {
        val repository = repository()
        val root = Files.createDirectories(tempDir.resolve("artifacts"))
        val reader = mockk<SystemTestRunReader>()
        every { reader.runs() } returns ViewResult(SystemTestRunCollection(emptyList(), 0), ViewState.FRESH, emptyList())
        val service = service(repository, root, reader)

        val started = service.start("customer-vault", LocalScenarioStart("DAW-10001", "USDC"))

        assertThat(started.runId).startsWith("vault-20260821T030000-")
        assertThat(started.href).isEqualTo("/admin/test-runs/${started.runId}")
        assertThat(
            Files.readString(root.resolve(".launcher/${started.runId}.log")),
        ).contains("customer-vault ${started.runId} DAW-10001 USDC")
    }

    @Test
    fun `허용하지 않은 ref는 프로세스를 시작하기 전에 거부한다`() {
        val repository = repository()
        val root = Files.createDirectories(tempDir.resolve("artifacts"))
        val reader = mockk<SystemTestRunReader>()
        every { reader.runs() } returns ViewResult(SystemTestRunCollection(emptyList(), 0), ViewState.FRESH, emptyList())

        assertThatThrownBy { service(repository, root, reader).start("customer-vault", LocalScenarioStart("bad ref", "USDC")) }
            .isInstanceOf(LocalScenarioRejected::class.java)
        assertThat(root.resolve(".launcher")).doesNotExist()
    }

    private fun repository(): Path {
        val scripts = Files.createDirectories(tempDir.resolve("repo/scripts"))
        val internal = Files.createDirectories(scripts.resolve("internal"))
        Files.writeString(
            internal.resolve("local-scenario-runner.py"),
            """
            import json, os, pathlib, sys
            root = pathlib.Path(os.environ["BCM_SYSTEM_TEST_ROOT"])
            run_id = sys.argv[2]
            print(" ".join(sys.argv[1:]), flush=True)
            directory = root / run_id
            directory.mkdir()
            (directory / "run.json").write_text(json.dumps({"runId": run_id}))
            """.trimIndent(),
        )
        Files.writeString(scripts.resolve("system-test.sh"), "#!/usr/bin/env bash\nexit 0\n")
        return scripts.parent
    }

    private fun service(
        repository: Path,
        root: Path,
        reader: SystemTestRunReader,
    ) = LocalScenarioCommandService(
        AdminProperties(
            systemTest = SystemTestProperties(enabled = true, stateDirectory = root.toString()),
            localScenario = LocalScenarioProperties(enabled = true, repositoryDirectory = repository.toString()),
        ),
        reader,
        Clock.fixed(Instant.parse("2026-08-21T03:00:00Z"), ZoneOffset.UTC),
    )
}
