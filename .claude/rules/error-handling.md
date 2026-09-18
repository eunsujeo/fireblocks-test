---
paths:
  - "**/*.kt"
---
# Error Handling

> HTTP 에러 응답 형식(envelope·`error.code`)의 정본은 [docs/api/openapi.yaml](../../docs/api/openapi.yaml)이고 구현은 `ErrorCode.kt`다 — 개수는 늘어나므로 여기 적지 않는다.

## 에러 코드 체계

- 모든 에러는 `CodeEnumType` 인터페이스를 구현하는 enum 으로 정의 (사내 표준 — docs/standards/architecture.md 의 `CodeEnumConfig`).
- 프로젝트 에러 코드 enum 이름·값은 openapi.yaml 의 `error.code` 표와 1:1로 맞춘다. 코드를 더하면 양쪽을 함께 고친다.
- 기능별로 에러 코드가 많아지면 feature 별 enum 분리 가능하되 `CodeEnumType` 구현 필수.

## 도메인 예외 (sealed class)

- 비즈니스 예외는 sealed class 계층으로 정의.
- **domain 레이어는 `CodeEnumType` 에 의존하지 않음** (domain 무의존 규칙과 동일 원리).
- 각 예외는 root cause 추적에 필요한 맥락 필드를 포함.

## Exception Resolver

- sealed class → `CodeEnumType` + HTTP status 매핑을 전담하는 resolver 분리.
- `@RestControllerAdvice` 에서 resolver 를 통해 응답 변환.
- sealed class exhaustive matching — 새 예외 추가 시 컴파일 에러로 누락 방지.

### 해석 우선순위

1. **도메인 예외** (sealed class) → resolver 가 CodeEnumType + HTTP status 매핑.
2. **Validation 예외** (`MethodArgumentNotValidException` 등) → VALIDATION_FAILED(400).
3. **Spring 표준 예외** → 적절한 CodeEnumType 매핑.
4. **그 외 모든 예외** → INTERNAL(500) fallback, ERROR 로깅.

### 응답 규칙

- resolver 가 변환한 code/message 로 응답 구성 (형식은 openapi.yaml).
- 내부 예외 메시지·스택트레이스는 클라이언트에 절대 노출 금지.
- presentation 에서 예외를 직접 catch 하지 않음 → resolver 가 일괄 처리.

## Root Cause 추적 규칙

- 예외 맥락 필드에 **"무엇이 왜 실패했는지"** 식별 가능한 정보 포함 (entity ID, 요청값 등).
- cause 체인 반드시 보존 — 예외 래핑 시 원본 cause 전달.
- 로깅 시 string template: `log.warn("에러설명 accountId=$id status=$status", exception)`.
- 에러 메시지만 보고 코드를 열지 않아도 발생 지점과 원인을 특정할 수 있어야 함.
- Sentry 전송 시 맥락 필드를 extra data 로 포함 (PII 제외).

## 레이어별 에러 전파

- **domain**: sealed class 예외 throw (맥락 필드 포함, CodeEnumType 의존 없음).
- **application**: 도메인 예외 그대로 전파, 조합 시 컨텍스트 추가하여 래핑.
- **infra**: 외부 시스템(벤더 API·DB·Kafka) 에러를 도메인 예외로 변환 — 기술 세부사항 누출 금지, cause 보존.
- **presentation(api)**: 직접 catch 금지 → ExceptionResolver 가 일괄 처리.

## 민감 정보 보호

- 에러 메시지/맥락 필드에 PII·시크릿 포함 금지. 웹훅 payload 원문·주소·금액도 무분별 출력 금지.
- 디버깅 필요 시 식별자(ID)만 사용, 원본 데이터 로깅 금지.

## 로깅

- 비즈니스 예외(예상된 에러): WARN + 맥락 필드.
- 시스템 예외(예상치 못한 에러): ERROR + 전체 스택트레이스.
- ExceptionResolver 에서 1회만 로깅 (중복 로깅 금지). 에러를 삼켜 "통과처럼" 만들지 않는다 (CLAUDE.md 0절).
