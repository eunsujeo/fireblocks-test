package com.whatto.bcm.admin.application

import com.whatto.bcm.admin.config.AdminProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Instant

data class SystemTestProgress(
    val completedSteps: Int,
    val totalSteps: Int,
    val percent: Int,
)

data class SystemTestRunSummary(
    val runId: String,
    val suite: String,
    val state: String,
    val startedAt: String,
    val updatedAt: String,
    val completedAt: String?,
    val currentStep: String?,
    val lastSuccessfulStep: String?,
    val progress: SystemTestProgress,
    val failureCode: String?,
    val failedStep: String?,
    val retryable: Boolean?,
)

data class SystemTestStep(
    val id: String,
    val name: String,
    val state: String,
    val startedAt: String?,
    val completedAt: String?,
    val durationMs: Long?,
    val classification: List<String>,
    val observations: List<SystemTestObservation>,
)

data class SystemTestObservation(
    val type: String,
    val value: String,
    val observedAt: String,
    val transactionHref: String? = null,
)

data class SystemTestComponent(
    val name: String,
    val state: String,
    val observedAt: String?,
)

data class SystemTestFailure(
    val code: String,
    val message: String,
    val failedStep: String,
    val retryable: Boolean,
    val nextAction: String,
)

data class SystemTestRelatedIds(
    val requestId: String? = null,
    val externalTxId: String? = null,
    val submissionId: String? = null,
    val vendorTxId: String? = null,
    val txHash: String? = null,
    val eventId: String? = null,
    val executionId: String? = null,
    val jobRunId: String? = null,
    val accountId: String? = null,
    val address: String? = null,
    val transactionHref: String? = null,
)

data class SystemTestRunDetail(
    val summary: SystemTestRunSummary,
    val steps: List<SystemTestStep>,
    val components: List<SystemTestComponent>,
    val classification: List<String>,
    val relatedIds: SystemTestRelatedIds,
    val failure: SystemTestFailure?,
    val artifactPath: String,
) {
    val runId: String = summary.runId
    val suite: String = summary.suite
    val state: String = summary.state
    val progress: SystemTestProgress = summary.progress
}

data class SystemTestRunCollection(
    val runs: List<SystemTestRunSummary>,
    val unreadableRunCount: Int,
)

class SystemTestRunNotFound(
    runId: String,
) : RuntimeException("system-test run not found: ${runId.take(MAX_SAFE_ERROR_ID_LENGTH)}")

class SystemTestRunUnreadable(
    runId: String,
) : RuntimeException("system-test run is unreadable: ${runId.take(MAX_SAFE_ERROR_ID_LENGTH)}")

@Component
@ConditionalOnProperty(prefix = "bcm.admin.system-test", name = ["enabled"], havingValue = "true")
class SystemTestRunReader(
    properties: AdminProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val staleAfterSeconds = properties.systemTest.staleAfterSeconds
    private val root =
        Path.of(properties.systemTest.stateDirectory).toAbsolutePath().normalize().let { configured ->
            if (Files.exists(configured)) configured.toRealPath() else configured
        }

    fun runs(): ViewResult<SystemTestRunCollection> {
        if (!Files.exists(root)) {
            return ViewResult(SystemTestRunCollection(emptyList(), 0), ViewState.FRESH, emptyList())
        }
        validateRoot()
        var unreadable = 0
        var stale = 0
        val summaries =
            Files.newDirectoryStream(root).use { entries ->
                entries
                    .asSequence()
                    .filter { RUN_ID_PATTERN.matches(it.fileName.toString()) }
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                    .mapNotNull { runDirectory ->
                        try {
                            val detail = readDetail(runDirectory.fileName.toString())
                            if (isStale(detail.summary)) {
                                stale += 1
                                staleFailure(detail).summary
                            } else {
                                detail.summary
                            }
                        } catch (_: SystemTestRunUnreadable) {
                            unreadable += 1
                            null
                        }
                    }.sortedByDescending(SystemTestRunSummary::startedAt)
                    .take(MAX_VISIBLE_RUNS)
                    .toList()
            }
        val issues =
            buildList {
                if (unreadable > 0) {
                    add(SourceIssue("system-test", "CORRUPT_RUN_ARTIFACT", "읽을 수 없는 로컬 테스트 실행 ${unreadable}건을 제외했습니다."))
                }
                if (stale > 0) {
                    add(SourceIssue("system-test", "STALE_RUN_ARTIFACT", "허용시간 동안 갱신되지 않은 진행 실행 ${stale}건이 있습니다."))
                }
            }
        return ViewResult(
            SystemTestRunCollection(summaries, unreadable),
            when {
                unreadable > 0 -> ViewState.PARTIAL
                stale > 0 -> ViewState.STALE
                else -> ViewState.FRESH
            },
            issues,
        )
    }

    fun run(runId: String): ViewResult<SystemTestRunDetail> {
        val detail = readDetail(runId)
        if (!isStale(detail.summary)) return ViewResult(detail, ViewState.FRESH, emptyList())
        return ViewResult(
            staleFailure(detail),
            ViewState.STALE,
            listOf(SourceIssue("system-test", "STALE_RUN_ARTIFACT", "실행 원장이 허용시간 동안 갱신되지 않았습니다.")),
        )
    }

    private fun staleFailure(detail: SystemTestRunDetail): SystemTestRunDetail {
        val failedStep = detail.summary.currentStep ?: "system-test-run"
        val failure =
            SystemTestFailure(
                code = "STALE_RUN_ARTIFACT",
                message = "실행 원장이 허용시간 동안 갱신되지 않아 실패로 판정했습니다.",
                failedStep = failedStep,
                retryable = true,
                nextAction = "./scripts/system-test.sh status ${detail.runId}와 component 로그를 확인한 뒤 다시 실행하세요.",
            )
        return detail.copy(
            summary =
                detail.summary.copy(
                    state = "FAILED",
                    currentStep = null,
                    failureCode = failure.code,
                    failedStep = failure.failedStep,
                    retryable = failure.retryable,
                ),
            failure = failure,
        )
    }

    private fun isStale(summary: SystemTestRunSummary): Boolean =
        summary.state in ACTIVE_RUN_STATES &&
            Instant.parse(summary.updatedAt).plusSeconds(staleAfterSeconds).isBefore(clock.instant())

    private fun readDetail(runId: String): SystemTestRunDetail {
        val runDirectory = resolveRunDirectory(runId)
        val document = readDocument(runDirectory.resolve("run.json"), runId)
        try {
            require(document.requiredInt("schemaVersion") == SCHEMA_VERSION)
            require(document.requiredText("runId", MAX_RUN_ID_LENGTH) == runId)
            val suite = document.requiredText("suite", 16).also { require(it in SUITES) }
            val state = document.requiredText("state", 16).also { require(it in RUN_STATES) }
            val startedAt = document.requiredInstant("startedAt")
            val updatedAt = document.requiredInstant("updatedAt")
            val completedAt = document.optionalInstant("completedAt")
            val currentStep = document.optionalText("currentStep", MAX_RUN_ID_LENGTH)
            val lastSuccessfulStep = document.optionalText("lastSuccessfulStep", MAX_RUN_ID_LENGTH)
            val steps = parseSteps(document.requiredArray("steps"))
            val declaredTotal = document.required("progress").requiredInt("totalSteps")
            require(declaredTotal in 1..MAX_STEPS && declaredTotal >= steps.size)
            val completedSteps = steps.count { it.state == "PASSED" }
            val progress = SystemTestProgress(completedSteps, declaredTotal, completedSteps * 100 / declaredTotal)
            val failure = parseFailure(document.get("failure"))
            validateState(state, steps, progress, failure)
            val components = parseComponents(document.requiredArray("components"))
            val classification = parseClassification(document.requiredArray("classification"))
            val relatedIds = parseRelatedIds(document.required("relatedIds"))
            val summary =
                SystemTestRunSummary(
                    runId = runId,
                    suite = suite,
                    state = state,
                    startedAt = startedAt,
                    updatedAt = updatedAt,
                    completedAt = completedAt,
                    currentStep = currentStep,
                    lastSuccessfulStep = lastSuccessfulStep,
                    progress = progress,
                    failureCode = failure?.code,
                    failedStep = failure?.failedStep,
                    retryable = failure?.retryable,
                )
            return SystemTestRunDetail(
                summary = summary,
                steps = steps,
                components = components,
                classification = classification,
                relatedIds = relatedIds,
                failure = failure,
                artifactPath = runDirectory.toString(),
            )
        } catch (_: SystemTestRunUnreadable) {
            throw SystemTestRunUnreadable(runId)
        } catch (_: Exception) {
            throw SystemTestRunUnreadable(runId)
        }
    }

    private fun resolveRunDirectory(runId: String): Path {
        if (!RUN_ID_PATTERN.matches(runId)) throw SystemTestRunNotFound(runId)
        if (!Files.exists(root)) throw SystemTestRunNotFound(runId)
        validateRoot()
        val candidate = root.resolve(runId).normalize()
        if (
            candidate.parent != root ||
            Files.isSymbolicLink(candidate) ||
            !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw SystemTestRunNotFound(runId)
        }
        if (candidate.toRealPath().parent != root.toRealPath()) throw SystemTestRunNotFound(runId)
        return candidate
    }

    private fun validateRoot() {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || root.toRealPath() != root) {
            throw SystemTestRunUnreadable("root")
        }
    }

    private fun readDocument(
        path: Path,
        runId: String,
    ): JsonNode {
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.size() > MAX_SNAPSHOT_BYTES) throw SystemTestRunUnreadable(runId)
            return objectMapper.readTree(Files.readString(path))
        } catch (_: SystemTestRunUnreadable) {
            throw SystemTestRunUnreadable(runId)
        } catch (_: Exception) {
            throw SystemTestRunUnreadable(runId)
        }
    }

    private fun parseSteps(array: JsonNode): List<SystemTestStep> {
        require(array.size() <= MAX_STEPS)
        return array.values().map { node ->
            val classification =
                node.get("classification")?.let { parseStepClassification(it) }.orEmpty()
            val observations =
                node.get("observations")?.let { parseObservations(it) }.orEmpty()
            SystemTestStep(
                id = node.requiredText("id", MAX_RUN_ID_LENGTH).also { require(RUN_ID_PATTERN.matches(it)) },
                name = node.requiredText("name", MAX_LABEL_LENGTH),
                state = node.requiredText("state", 16).also { require(it in STEP_STATES) },
                startedAt = node.optionalInstant("startedAt"),
                completedAt = node.optionalInstant("completedAt"),
                durationMs = node.optionalLong("durationMs")?.also { require(it >= 0) },
                classification = classification,
                observations = observations,
            )
        }
    }

    private fun parseStepClassification(array: JsonNode): List<String> {
        require(array.isArray && array.size() in 1..MAX_CLASSIFICATIONS)
        return array
            .values()
            .map { node -> node.also { require(it.isString) }.asString().also { require(it in LOCAL_CLASSIFICATIONS) } }
            .distinct()
    }

    private fun parseObservations(array: JsonNode): List<SystemTestObservation> {
        require(array.isArray && array.size() <= MAX_OBSERVATIONS)
        return array.values().map { node ->
            val type = node.requiredText("type", MAX_ERROR_CODE_LENGTH).also { require(it in RELATED_ID_TYPES) }
            val value = node.requiredText("value", MAX_RELATED_ID_LENGTH)
            SystemTestObservation(
                type = type,
                value = value,
                observedAt = node.requiredInstant("observedAt"),
                transactionHref =
                    value
                        .takeIf { type in TRANSACTION_IDENTIFIER_TYPES }
                        ?.let { "/admin/transactions/${encodePathSegment(it)}" },
            )
        }
    }

    private fun parseComponents(array: JsonNode): List<SystemTestComponent> {
        require(array.size() <= MAX_COMPONENTS)
        return array.values().map { node ->
            SystemTestComponent(
                name = node.requiredText("name", MAX_RUN_ID_LENGTH).also { require(RUN_ID_PATTERN.matches(it)) },
                state = node.requiredText("state", 16).also { require(it in COMPONENT_STATES) },
                observedAt = node.optionalInstant("observedAt"),
            )
        }
    }

    private fun parseClassification(array: JsonNode): List<String> {
        require(array.size() in 1..MAX_CLASSIFICATIONS)
        return array
            .values()
            .map { node ->
                node.also { require(it.isString) }.asString().also { require(it in LOCAL_CLASSIFICATIONS) }
            }.distinct()
    }

    private fun parseFailure(node: JsonNode?): SystemTestFailure? {
        if (node == null || node.isNull) return null
        return SystemTestFailure(
            code = node.requiredText("code", MAX_ERROR_CODE_LENGTH),
            message = node.requiredText("message", MAX_MESSAGE_LENGTH),
            failedStep = node.requiredText("failedStep", MAX_RUN_ID_LENGTH),
            retryable = node.requiredBoolean("retryable"),
            nextAction = node.requiredText("nextAction", MAX_MESSAGE_LENGTH),
        )
    }

    private fun parseRelatedIds(node: JsonNode): SystemTestRelatedIds {
        val externalTxId = node.optionalText("externalTxId", MAX_RELATED_ID_LENGTH)
        val vendorTxId = node.optionalText("vendorTxId", MAX_RELATED_ID_LENGTH)
        val txHash = node.optionalText("txHash", MAX_RELATED_ID_LENGTH)
        val transactionIdentifier = externalTxId ?: vendorTxId ?: txHash
        return SystemTestRelatedIds(
            requestId = node.optionalText("requestId", MAX_RELATED_ID_LENGTH),
            externalTxId = externalTxId,
            submissionId = node.optionalText("submissionId", MAX_RELATED_ID_LENGTH),
            vendorTxId = vendorTxId,
            txHash = txHash,
            eventId = node.optionalText("eventId", MAX_RELATED_ID_LENGTH),
            executionId = node.optionalText("executionId", MAX_RELATED_ID_LENGTH),
            jobRunId = node.optionalText("jobRunId", MAX_RELATED_ID_LENGTH),
            accountId = node.optionalText("accountId", MAX_RELATED_ID_LENGTH),
            address = node.optionalText("address", MAX_RELATED_ID_LENGTH),
            transactionHref = transactionIdentifier?.let { "/admin/transactions/${encodePathSegment(it)}" },
        )
    }

    private fun validateState(
        state: String,
        steps: List<SystemTestStep>,
        progress: SystemTestProgress,
        failure: SystemTestFailure?,
    ) {
        when (state) {
            "PASSED" -> require(progress.completedSteps == progress.totalSteps && steps.all { it.state == "PASSED" } && failure == null)
            "FAILED" -> require(steps.any { it.state == "FAILED" } && failure != null)
            "ABORTED" -> require(steps.any { it.state == "ABORTED" } && failure != null)
            "PENDING", "RUNNING" -> require(steps.none { it.state == "FAILED" || it.state == "ABORTED" })
        }
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder
            .encode(value, Charsets.UTF_8)
            .replace("+", "%20")

    private fun JsonNode.required(field: String): JsonNode = get(field) ?: throw IllegalArgumentException("missing field")

    private fun JsonNode.requiredArray(field: String): JsonNode = required(field).also { require(it.isArray) }

    private fun JsonNode.requiredText(
        field: String,
        maxLength: Int,
    ): String = required(field).also { require(it.isString) }.asString().also { require(it.isNotBlank() && it.length <= maxLength) }

    private fun JsonNode.optionalText(
        field: String,
        maxLength: Int,
    ): String? =
        get(field)
            ?.takeUnless(JsonNode::isNull)
            ?.also { require(it.isString) }
            ?.asString()
            ?.also { require(it.length <= maxLength) }

    private fun JsonNode.requiredInt(field: String): Int = required(field).also { require(it.isIntegralNumber) }.asInt()

    private fun JsonNode.optionalLong(field: String): Long? =
        get(field)?.takeUnless(JsonNode::isNull)?.also { require(it.isIntegralNumber) }?.asLong()

    private fun JsonNode.requiredBoolean(field: String): Boolean = required(field).also { require(it.isBoolean) }.asBoolean()

    private fun JsonNode.requiredInstant(field: String): String = requiredText(field, MAX_TIME_LENGTH).also(Instant::parse)

    private fun JsonNode.optionalInstant(field: String): String? = optionalText(field, MAX_TIME_LENGTH)?.also(Instant::parse)

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_VISIBLE_RUNS = 20
        private const val MAX_SNAPSHOT_BYTES = 1_048_576L
        private const val MAX_STEPS = 200
        private const val MAX_COMPONENTS = 32
        private const val MAX_OBSERVATIONS = 1_000
        private const val MAX_CLASSIFICATIONS = 4
        private const val MAX_RUN_ID_LENGTH = 64
        private const val MAX_LABEL_LENGTH = 160
        private const val MAX_ERROR_CODE_LENGTH = 64
        private const val MAX_MESSAGE_LENGTH = 500
        private const val MAX_RELATED_ID_LENGTH = 256
        private const val MAX_TIME_LENGTH = 40
        private val RUN_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,$MAX_RUN_ID_LENGTH}")
        private val SUITES = setOf("SMOKE", "FULL", "SCENARIO")
        private val RUN_STATES = setOf("PENDING", "RUNNING", "PASSED", "FAILED", "ABORTED")
        private val ACTIVE_RUN_STATES = setOf("PENDING", "RUNNING")
        private val STEP_STATES = RUN_STATES
        private val COMPONENT_STATES = setOf("STARTING", "UP", "DOWN", "FAILED")
        private val LOCAL_CLASSIFICATIONS = setOf("REAL_LOCAL", "SIMULATED_VENDOR")
        private val RELATED_ID_TYPES =
            setOf(
                "requestId",
                "externalTxId",
                "submissionId",
                "vendorTxId",
                "txHash",
                "eventId",
                "executionId",
                "jobRunId",
                "accountId",
                "address",
            )
        private val TRANSACTION_IDENTIFIER_TYPES = setOf("externalTxId", "vendorTxId", "txHash")
    }
}

private const val MAX_SAFE_ERROR_ID_LENGTH = 64
