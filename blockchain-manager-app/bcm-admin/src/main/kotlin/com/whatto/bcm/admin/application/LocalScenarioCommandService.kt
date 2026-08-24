package com.whatto.bcm.admin.application

import com.whatto.bcm.admin.config.AdminProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class LocalScenarioInput(
    val name: String,
    val label: String,
    val required: Boolean,
    val defaultValue: String? = null,
)

data class LocalScenarioDefinition(
    val id: String,
    val title: String,
    val description: String,
    val estimatedDuration: String,
    val classification: List<String>,
    val inputs: List<LocalScenarioInput>,
)

data class LocalScenarioCatalog(
    val scenarios: List<LocalScenarioDefinition>,
)

data class LocalScenarioStart(
    val ref: String? = null,
    val symbol: String? = null,
)

data class LocalScenarioStarted(
    val runId: String,
    val href: String,
)

class LocalScenarioConflict : RuntimeException("another local scenario is already running")

class LocalScenarioRejected(
    message: String,
) : RuntimeException(message)

@Service
@ConditionalOnProperty(prefix = "bcm.admin.local-scenario", name = ["enabled"], havingValue = "true")
class LocalScenarioCommandService(
    properties: AdminProperties,
    private val reader: SystemTestRunReader,
    private val clock: Clock,
) {
    private val repository = Path.of(properties.localScenario.repositoryDirectory)
    private val artifactRoot = Path.of(properties.systemTest.stateDirectory)
    private val launching = AtomicBoolean(false)

    fun catalog() = LocalScenarioCatalog(SCENARIOS.values.toList())

    @Synchronized
    fun start(
        scenarioId: String,
        request: LocalScenarioStart,
    ): LocalScenarioStarted {
        val scenario = SCENARIOS[scenarioId] ?: throw LocalScenarioRejected("지원하지 않는 시나리오입니다.")
        if (!launching.compareAndSet(false, true)) throw LocalScenarioConflict()
        try {
            if (reader
                    .runs()
                    .data.runs
                    .any { it.state == "PENDING" || it.state == "RUNNING" }
            ) {
                throw LocalScenarioConflict()
            }
            val scenarioArguments = commandArguments(scenario.id, request)
            val runId = runId(scenario.id)
            val launcherDirectory = artifactRoot.resolve(".launcher")
            Files.createDirectories(launcherDirectory)
            val log = launcherDirectory.resolve("$runId.log").toFile()
            val processBuilder =
                ProcessBuilder(arguments(scenario.id, runId, scenarioArguments))
                    .directory(repository.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            processBuilder.environment()["BCM_SYSTEM_TEST_ROOT"] = artifactRoot.toString()
            processBuilder.environment()["BCM_SYSTEM_TEST_RUN_ID"] = runId
            val process = processBuilder.start()
            process.onExit().thenRun { launching.set(false) }
            waitForLedger(runId, process)
            return LocalScenarioStarted(runId, "/admin/test-runs/$runId")
        } catch (failure: Exception) {
            launching.set(false)
            throw failure
        }
    }

    private fun commandArguments(
        scenarioId: String,
        request: LocalScenarioStart,
    ): List<String> =
        when (scenarioId) {
            "customer-vault" -> {
                val ref = request.ref?.takeIf(REF_PATTERN::matches) ?: throw LocalScenarioRejected("ref 형식을 확인하세요.")
                val symbol =
                    request.symbol?.takeIf { SYMBOL_PATTERN.matches(it) && it in LOCAL_SYMBOLS }
                        ?: throw LocalScenarioRejected("symbol 형식을 확인하세요.")
                listOf(ref, symbol)
            }
            else -> emptyList()
        }

    private fun arguments(
        scenarioId: String,
        runId: String,
        scenarioArguments: List<String>,
    ): List<String> =
        when (scenarioId) {
            "smoke", "full" -> listOf(repository.resolve("scripts/system-test.sh").toString(), scenarioId)
            else ->
                listOf(
                    "python3",
                    repository.resolve("scripts/internal/local-scenario-runner.py").toString(),
                    scenarioId,
                    runId,
                ) + scenarioArguments
        }

    private fun waitForLedger(
        runId: String,
        process: Process,
    ) {
        repeat(80) {
            if (Files.isRegularFile(artifactRoot.resolve(runId).resolve("run.json"))) return
            if (!process.isAlive) throw LocalScenarioRejected("시나리오 실행기를 시작하지 못했습니다. launcher 로그를 확인하세요.")
            Thread.sleep(25)
        }
        process.destroy()
        throw LocalScenarioRejected("시나리오 실행 원장이 제한 시간 안에 생성되지 않았습니다.")
    }

    private fun runId(scenarioId: String): String {
        val timestamp = RUN_ID_TIME.format(clock.instant().atZone(ZoneOffset.UTC))
        val suffix = UUID.randomUUID().toString().substring(0, 8)
        return "${RUN_ID_PREFIX.getValue(scenarioId)}-$timestamp-$suffix"
    }

    companion object {
        private val REF_PATTERN = Regex("[A-Za-z0-9._:-]{1,64}")
        private val SYMBOL_PATTERN = Regex("[A-Z0-9_]{1,16}")
        private val LOCAL_SYMBOLS = setOf("USDC", "KRWK")
        private val RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        private val RUN_ID_PREFIX =
            mapOf(
                "asset-catalog" to "asset",
                "customer-vault" to "vault",
                "deposit-success" to "deposit",
                "smoke" to "smoke",
                "full" to "full",
            )
        private val SCENARIOS =
            listOf(
                LocalScenarioDefinition(
                    "asset-catalog",
                    "로컬 자산 준비",
                    "ETHEREUM·BASE의 USDC/KRWK 기본 매핑을 확인하고 누락된 카탈로그를 준비합니다.",
                    "약 20~60초",
                    listOf("REAL_LOCAL", "SIMULATED_VENDOR"),
                    emptyList(),
                ),
                LocalScenarioDefinition(
                    "customer-vault",
                    "고객 vault·주소 생성",
                    "공개 API로 CUSTOMER 계정(vault)과 ETHEREUM 입금 주소를 만듭니다.",
                    "약 20~60초",
                    listOf("REAL_LOCAL", "SIMULATED_VENDOR"),
                    listOf(
                        LocalScenarioInput("ref", "고객 참조키", true),
                        LocalScenarioInput("symbol", "자산 심볼", true, "USDC"),
                    ),
                ),
                LocalScenarioDefinition(
                    "deposit-success",
                    "입금 성공",
                    "Anvil 입금부터 서명 Webhook, FINALIZED, Kafka, Admin 조사까지 확인합니다.",
                    "약 30~90초",
                    listOf("REAL_LOCAL", "SIMULATED_VENDOR"),
                    emptyList(),
                ),
                LocalScenarioDefinition(
                    "smoke",
                    "독립 Smoke",
                    "전용 포트와 데이터로 최소 전체 세로줄을 새로 조립합니다.",
                    "약 1~3분",
                    listOf("REAL_LOCAL", "SIMULATED_VENDOR"),
                    emptyList(),
                ),
                LocalScenarioDefinition(
                    "full",
                    "독립 Full",
                    "출금·gasless·실패 복구·sweep/BAT·reset까지 전체 회귀를 실행합니다.",
                    "약 5~12분",
                    listOf("REAL_LOCAL", "SIMULATED_VENDOR"),
                    emptyList(),
                ),
            ).associateBy(LocalScenarioDefinition::id)
    }
}
