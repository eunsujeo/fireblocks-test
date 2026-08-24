package com.whatto.bcm.admin.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AdminExposureBoundaryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `기능 테스트 Admin은 loopback frontend와 BCM만 허용한다`() {
        assertThatCode {
            AdminExposureBoundary(AdminProperties(), "127.0.0.1").afterPropertiesSet()
        }.doesNotThrowAnyException()
    }

    @Test
    fun `기능 테스트 Admin의 외부 바인딩이나 외부 BCM 대상은 시작을 거절한다`() {
        assertThatThrownBy {
            AdminExposureBoundary(AdminProperties(), "0.0.0.0").afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)

        assertThatThrownBy {
            AdminExposureBoundary(AdminProperties(targetBaseUrl = "https://bcm.example.com"), "127.0.0.1")
                .afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)

        assertThatThrownBy {
            AdminExposureBoundary(AdminProperties(webhookManagementBaseUrl = "https://webhook.example.com"), "127.0.0.1")
                .afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `공유 모드는 mTLS JWT 경계 구현 전 시작을 거절한다`() {
        assertThatThrownBy {
            AdminExposureBoundary(AdminProperties(mode = "SHARED"), "127.0.0.1").afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `로컬 테스트 진단은 절대경로의 일반 디렉터리만 허용한다`() {
        assertThatCode {
            AdminExposureBoundary(
                AdminProperties(
                    systemTest = SystemTestProperties(enabled = true, stateDirectory = tempDir.toString()),
                ),
                "127.0.0.1",
            ).afterPropertiesSet()
        }.doesNotThrowAnyException()

        assertThatThrownBy {
            AdminExposureBoundary(
                AdminProperties(systemTest = SystemTestProperties(enabled = true, stateDirectory = "build/system-test")),
                "127.0.0.1",
            ).afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `로컬 테스트 진단 root가 symlink면 시작을 거절한다`() {
        val target = Files.createDirectory(tempDir.resolve("target"))
        val link = tempDir.resolve("link")
        Files.createSymbolicLink(link, target)

        assertThatThrownBy {
            AdminExposureBoundary(
                AdminProperties(systemTest = SystemTestProperties(enabled = true, stateDirectory = link.toString())),
                "127.0.0.1",
            ).afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `로컬 시나리오는 STUB LOCAL 모드에서만 시작한다`() {
        val scripts = Files.createDirectories(tempDir.resolve("scripts"))
        val internal = Files.createDirectories(scripts.resolve("internal"))
        Files.createFile(internal.resolve("local-scenario-runner.py"))
        Files.createFile(scripts.resolve("system-test.sh"))
        val localScenario = LocalScenarioProperties(enabled = true, repositoryDirectory = tempDir.toString())
        val systemTest = SystemTestProperties(enabled = true, stateDirectory = tempDir.toString())

        assertThatCode {
            AdminExposureBoundary(
                AdminProperties(
                    vendorMode = "STUB",
                    chainMode = "LOCAL",
                    systemTest = systemTest,
                    localScenario = localScenario,
                ),
                "127.0.0.1",
            ).afterPropertiesSet()
        }.doesNotThrowAnyException()

        assertThatThrownBy {
            AdminExposureBoundary(
                AdminProperties(
                    vendorMode = "FIREBLOCKS",
                    chainMode = "TESTNET",
                    systemTest = systemTest,
                    localScenario = localScenario,
                ),
                "127.0.0.1",
            ).afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `로컬 자산 관리 감사 actor는 DB 크기와 어휘를 지킨다`() {
        assertThatCode {
            AdminExposureBoundary(
                AdminProperties(localAssetManagement = LocalAssetManagementProperties(enabled = true)),
                "127.0.0.1",
            ).afterPropertiesSet()
        }.doesNotThrowAnyException()

        assertThatThrownBy {
            AdminExposureBoundary(
                AdminProperties(
                    localAssetManagement =
                        LocalAssetManagementProperties(enabled = true, employeeNo = "TOO-LONG", branchCode = "9999"),
                ),
                "127.0.0.1",
            ).afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
