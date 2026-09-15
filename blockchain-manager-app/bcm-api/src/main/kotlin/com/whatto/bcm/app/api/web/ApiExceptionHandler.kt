package com.whatto.bcm.app.api.web

import com.whatto.bcm.domain.exception.BcmException
import com.whatto.bcm.domain.exception.BulkAssetMappingException
import com.whatto.bcm.domain.exception.CreationRetryLaterException
import com.whatto.bcm.domain.exception.ProvisioningPendingException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.VendorApiException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 예외 → 에러 응답 일괄 변환 — presentation 은 직접 catch 하지 않는다 (.claude/rules/error-handling.md).
 * 해석 우선순위: 도메인 예외 → validation → Spring 표준 → INTERNAL fallback. 로깅은 여기서 1회만.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(BcmException::class)
    fun handleDomain(
        exception: BcmException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val errorCode = DomainExceptionResolver.resolve(exception)
        val retryAfterSeconds =
            when (exception) {
                is SubmissionInProgressException -> exception.retryAfterSeconds
                is CreationRetryLaterException -> exception.retryAfterSeconds
                is ProvisioningPendingException -> exception.retryAfterSeconds
                else -> null
            }
        if (exception is VendorApiException) {
            log.error(
                "벤더 시스템 예외 code=${errorCode.code} operation=${exception.operation} httpStatus=${exception.httpStatus}",
                exception,
            )
        } else {
            log.warn("비즈니스 예외 code=${errorCode.code}: ${exception.message}", exception)
        }
        return respond(
            errorCode,
            request,
            retryAfterSeconds = retryAfterSeconds,
            details =
                (exception as? BulkAssetMappingException)?.let {
                    ErrorResponse.ErrorDetails(it.index, it.network, it.symbol, it.reason)
                },
        )
    }

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
        ConstraintViolationException::class,
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        ServletRequestBindingException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleValidation(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        // 실패한 필드 이름만 남긴다 — BindingResult 메시지는 거절된 값(주소·금액)을 그대로 렌더링한다.
        log.warn("요청 검증 실패 type={} fields={}", exception.javaClass.simpleName, rejectedFields(exception))
        return respond(ErrorCode.VALIDATION_FAILED, request)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(
        exception: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("매핑 없는 경로: ${exception.message}")
        return respond(ErrorCode.NOT_FOUND, request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("예상치 못한 예외", exception)
        return respond(ErrorCode.INTERNAL, request)
    }

    /** 값은 절대 담지 않는다 — 필드 이름만으로 어디가 틀렸는지 특정할 수 있다. */
    private fun rejectedFields(exception: Exception): List<String> =
        when (exception) {
            is MethodArgumentNotValidException -> {
                exception.bindingResult.fieldErrors
                    .map { it.field }
                    .distinct()
            }

            is MissingServletRequestParameterException -> {
                listOf(exception.parameterName)
            }

            is MethodArgumentTypeMismatchException -> {
                listOf(exception.name)
            }

            else -> {
                emptyList()
            }
        }

    private fun respond(
        errorCode: ErrorCode,
        request: HttpServletRequest,
        retryAfterSeconds: Long? = null,
        details: ErrorResponse.ErrorDetails? = null,
    ): ResponseEntity<ErrorResponse> {
        val response = ResponseEntity.status(errorCode.status)
        retryAfterSeconds?.let { response.header(HttpHeaders.RETRY_AFTER, it.toString()) }
        return response
            .body(
                ErrorResponse(
                    error =
                        ErrorResponse.ErrorBody(
                            code = errorCode.code,
                            message = errorCode.message,
                            retryAfterSeconds = retryAfterSeconds,
                            details = details,
                        ),
                    meta = Meta(requestId = RequestIdFilter.requestIdOf(request)),
                ),
            )
    }
}
