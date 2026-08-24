package com.whatto.bcm.admin.api

import com.whatto.bcm.admin.application.LocalScenarioCatalog
import com.whatto.bcm.admin.application.LocalScenarioCommandService
import com.whatto.bcm.admin.application.LocalScenarioConflict
import com.whatto.bcm.admin.application.LocalScenarioRejected
import com.whatto.bcm.admin.application.LocalScenarioStart
import com.whatto.bcm.admin.application.LocalScenarioStarted
import com.whatto.bcm.admin.application.SystemTestRunCollection
import com.whatto.bcm.admin.application.SystemTestRunDetail
import com.whatto.bcm.admin.application.SystemTestRunNotFound
import com.whatto.bcm.admin.application.SystemTestRunReader
import com.whatto.bcm.admin.application.SystemTestRunUnreadable
import com.whatto.bcm.admin.application.ViewResult
import com.whatto.bcm.admin.application.ViewState
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Validated
@RestController
@ConditionalOnProperty(prefix = "bcm.admin.system-test", name = ["enabled"], havingValue = "true")
class SystemTestBffController(
    private val reader: SystemTestRunReader,
    private val clock: Clock,
) {
    @GetMapping("/bff/admin/test-runs")
    fun runs(request: HttpServletRequest): BffResponse<SystemTestRunCollection> = respond(request, reader.runs())

    @GetMapping("/bff/admin/test-runs/{runId}")
    fun run(
        @PathVariable
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9_-]+")
        runId: String,
        request: HttpServletRequest,
    ): BffResponse<SystemTestRunDetail> = respond(request, reader.run(runId))

    private fun <T> respond(
        request: HttpServletRequest,
        result: ViewResult<T>,
    ) = BffResponse(result.data, meta(request), result.state, result.issues)

    private fun meta(request: HttpServletRequest) =
        BffMeta(
            request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            Instant.now(clock).toString(),
        )
}

@Validated
@RestController
@ConditionalOnProperty(prefix = "bcm.admin.local-scenario", name = ["enabled"], havingValue = "true")
class LocalScenarioBffController(
    private val service: LocalScenarioCommandService,
    private val clock: Clock,
) {
    @GetMapping("/bff/admin/test-scenarios")
    fun scenarios(request: HttpServletRequest): BffResponse<LocalScenarioCatalog> =
        BffResponse(service.catalog(), meta(request), ViewState.FRESH, emptyList())

    @PostMapping("/bff/admin/test-scenarios/{scenarioId}/runs", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun start(
        @PathVariable @Pattern(regexp = "[a-z-]{1,32}") scenarioId: String,
        @RequestBody request: LocalScenarioStart,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<BffResponse<LocalScenarioStarted>> {
        requireSameOriginMutation(httpRequest)
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(BffResponse(service.start(scenarioId, request), meta(httpRequest), ViewState.FRESH, emptyList()))
    }

    private fun requireSameOriginMutation(request: HttpServletRequest) {
        if (request.getHeader(LOCAL_SCENARIO_HEADER) != LOCAL_SCENARIO_HEADER_VALUE) throw LocalScenarioRequestForbidden()
        if (request.serverName.lowercase() !in LOOPBACK_NAMES) throw LocalScenarioRequestForbidden()
        val host = request.serverName.let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
        val defaultPort = (request.scheme == "http" && request.serverPort == 80) || (request.scheme == "https" && request.serverPort == 443)
        val expectedOrigin = "${request.scheme}://$host${if (defaultPort) "" else ":${request.serverPort}"}"
        if (request.getHeader("Origin") != expectedOrigin) throw LocalScenarioRequestForbidden()
    }

    private fun meta(request: HttpServletRequest) =
        BffMeta(
            request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            Instant.now(clock).toString(),
        )

    private companion object {
        const val LOCAL_SCENARIO_HEADER = "X-BCM-Local-Scenario"
        const val LOCAL_SCENARIO_HEADER_VALUE = "execute"
        val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
    }
}

class LocalScenarioRequestForbidden : RuntimeException("local scenario request origin is not allowed")

@RestControllerAdvice(assignableTypes = [SystemTestBffController::class])
@ConditionalOnProperty(prefix = "bcm.admin.system-test", name = ["enabled"], havingValue = "true")
class SystemTestBffExceptionHandler(
    private val clock: Clock,
) {
    @ExceptionHandler(SystemTestRunNotFound::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<BffErrorResponse> =
        response(HttpStatus.NOT_FOUND, "TEST_RUN_NOT_FOUND", "테스트 실행을 찾을 수 없습니다.", request)

    @ExceptionHandler(SystemTestRunUnreadable::class)
    fun unreadable(
        failure: SystemTestRunUnreadable,
        request: HttpServletRequest,
    ): ResponseEntity<BffErrorResponse> {
        log.warn("BCM Admin system-test artifact is unreadable", failure)
        return response(HttpStatus.BAD_GATEWAY, "TEST_RUN_UNREADABLE", "테스트 실행 원장을 읽을 수 없습니다.", request)
    }

    private fun response(
        status: HttpStatus,
        code: String,
        message: String,
        request: HttpServletRequest,
    ) = ResponseEntity
        .status(status)
        .body(
            BffErrorResponse(
                BffError(code, message),
                BffMeta(
                    request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    Instant.now(clock).toString(),
                ),
            ),
        )

    companion object {
        private val log = LoggerFactory.getLogger(SystemTestBffExceptionHandler::class.java)
    }
}

@RestControllerAdvice(assignableTypes = [LocalScenarioBffController::class])
@ConditionalOnProperty(prefix = "bcm.admin.local-scenario", name = ["enabled"], havingValue = "true")
class LocalScenarioBffExceptionHandler(
    private val clock: Clock,
) {
    @ExceptionHandler(LocalScenarioConflict::class)
    fun conflict(request: HttpServletRequest): ResponseEntity<BffErrorResponse> =
        response(HttpStatus.CONFLICT, "SCENARIO_ALREADY_RUNNING", "진행 중인 로컬 시나리오가 있습니다.", request)

    @ExceptionHandler(LocalScenarioRejected::class)
    fun rejected(
        failure: LocalScenarioRejected,
        request: HttpServletRequest,
    ): ResponseEntity<BffErrorResponse> =
        response(HttpStatus.BAD_REQUEST, "SCENARIO_REJECTED", failure.message ?: "시나리오 요청을 실행할 수 없습니다.", request)

    @ExceptionHandler(LocalScenarioRequestForbidden::class)
    fun forbidden(request: HttpServletRequest): ResponseEntity<BffErrorResponse> =
        response(HttpStatus.FORBIDDEN, "SCENARIO_REQUEST_FORBIDDEN", "로컬 시나리오 요청 출처를 확인할 수 없습니다.", request)

    private fun response(
        status: HttpStatus,
        code: String,
        message: String,
        request: HttpServletRequest,
    ) = ResponseEntity
        .status(status)
        .body(
            BffErrorResponse(
                BffError(code, message),
                BffMeta(
                    request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    Instant.now(clock).toString(),
                ),
            ),
        )
}
