package com.whatto.bcm.domain.exception

/**
 * 비즈니스 예외 계층 — 코드·HTTP 매핑은 api 레이어 resolver 소관 (.claude/rules/error-handling.md).
 * domain 은 CodeEnumType 에 의존하지 않는다. 각 예외는 root cause 추적용 맥락 필드(식별자만, PII 금지)를 담는다.
 */
sealed class BcmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 계정 없음 — 주소 미발급(NotFound)과 구분되는 별도 코드 (openapi 에러 표). */
class AccountNotFoundException(
    val accountId: String,
) : BcmException("account not found: accountId=$accountId")

/** 계정 외 리소스 없음 — resource 는 리소스 종류(예: "depositAddress"), key 는 조회 식별자. */
class ResourceNotFoundException(
    val resource: String,
    val key: String,
) : BcmException("$resource not found: key=$key")

/** 상태·멱등 충돌 — 예: 이미 쓴 externalTxId (openapi CONFLICT). */
class ConflictException(
    val resource: String,
    val key: String,
    cause: Throwable? = null,
) : BcmException("$resource conflict: key=$key", cause)

/** 같은 externalTxId의 앞선 제출이 아직 claim을 보유 중이다. */
class SubmissionInProgressException(
    val externalTransactionId: String,
    val retryAfterSeconds: Long,
) : BcmException("submission in progress: externalTransactionId=$externalTransactionId")

/** 생성 키 교체 전 보수적 대기 — 마지막 POST 준비 이후 이 시간만큼 지나면 같은 업무 요청을 재시도한다. */
class CreationRetryLaterException(
    val resourceKey: String,
    val retryAfterSeconds: Long,
) : BcmException("vendor resource creation must be retried later: resourceKey=$resourceKey retryAfterSeconds=$retryAfterSeconds")

/** 등록되지 않은 (network, symbol) — 형식 오류와 구분해 호출자가 지원 자산 여부를 판별한다. */
class AssetNotSupportedException(
    val network: String,
    val symbol: String,
) : BcmException("asset not supported: network=$network symbol=$symbol")

/** Admin 자산 매핑 값이 벤더 카탈로그와 맞지 않는다. */
class InvalidAssetMappingException(
    val network: String,
    val reason: String,
) : BcmException("invalid asset mapping: network=$network reason=$reason")

/** 자산 매핑 저장 경합 — 일괄 등록 호출자가 실패 항목을 문자열 추정 없이 식별하게 한다. */
class VendorAssetMappingRegistrationConflictException(
    val network: String,
    val symbol: String,
    cause: Throwable? = null,
) : BcmException("vendor asset mapping registration conflict: network=$network symbol=$symbol", cause)

/** Admin 일괄 자산 등록에서 실패한 항목을 특정한다. network·symbol은 공개 운영 식별자이며 PII가 아니다. */
class BulkAssetMappingException(
    val index: Int,
    val network: String,
    val symbol: String,
    val reason: String,
    val failure: BcmException,
) : BcmException(
        "bulk asset mapping failed: index=$index network=$network symbol=$symbol reason=$reason",
        failure,
    )

/** 요청 파라미터 사이의 조건처럼 Bean Validation만으로 표현하기 어려운 계약 위반. */
class InvalidRequestException(
    val field: String,
) : BcmException("invalid request: field=$field")

/** 문법은 맞지만 현재 리소스 상태 때문에 처리할 수 없는 요청. */
class UnprocessableRequestException(
    val resource: String,
    val key: String,
) : BcmException("unprocessable request: resource=$resource key=$key")

/** 대납 relay 가 전송을 못 대거나 거절 (openapi RELAY_REJECTED) — infra 가 벤더 에러를 변환해 던진다. */
class RelayRejectedException(
    val reason: String,
    cause: Throwable? = null,
) : BcmException("relay rejected: reason=$reason", cause)

/** 벤더 API 호출 실패(HTTP 에러·타임아웃·재시도 소진) — infra 가 기술 예외를 변환해 던진다. cause 보존. */
class VendorApiException(
    val operation: String,
    val httpStatus: Int?,
    cause: Throwable? = null,
) : BcmException("vendor api failure: operation=$operation httpStatus=$httpStatus", cause)

/** 벤더 네트워크별 자산 카탈로그 동기화 중 일부가 실패했다. */
class VendorAssetCatalogSyncException(
    val failedSources: List<String>,
    cause: Throwable,
) : RuntimeException("vendor asset catalog sync failed: sources=${failedSources.joinToString(",")}", cause)
