# Virtual Thread (JDK 25)

> **상태: 채택 — 2026-08-06 확정** (PLAN #8). daw-core 팀 규칙을 가져와 이 프로젝트 조건에 맞게 잘랐다.
> **`StructuredTaskScope` 는 채택하지 않는다** — JDK 25 에서 preview(JEP 505)라 `--enable-preview` 빌드 플래그가 필요하고,
> 수탁 프로덕션 빌드에 preview 플래그를 다는 건 다음 JDK 에서 호환이 깨져도 감수한다는 뜻이다.
> 아래 "구조적 동시성" 절은 채택 시점을 위한 참고로만 남긴다 — 지금 쓰지 않는다.

## Context

JDK 25 기반, 가상 스레드를 기본 동시성 모델로 채택한다.
Spring Boot 4.1 `spring.threads.virtual.enabled=true` 설정 시 Tomcat, `@Scheduled`, TaskExecutor 가 자동으로 가상 스레드 사용.

## 이 프로젝트의 채택 조건 (2026-08-06)

- **수신 동시성에 명시 상한을 둔다.** 가상 스레드는 무한히 늘어도 DB 커넥션은 유한하다. 상한이 없으면 폭주 시 전부
  커넥션 대기로 쌓이고 → 벤더 타임아웃 → 재시도 증폭이 된다. Tomcat `max-connections` 또는 `Semaphore` 로 건다.
- **relay 순차 발행·판단 워커 폴링은 병렬화 대상이 아니다.** 같은 계정 안 `evnt_id` 순서 보장이 깨지면 안 된다.
  동시성은 설정값으로 명시하고, 가상 스레드라는 이유로 무제한 확장하지 않는다.
- 이득이 실제로 나오는 지점은 웹훅 수신부다 — 벤더 재시도가 몰릴 때 동시 수신 수용력이 곧 유실 방어이고,
  수신 트랜잭션이 짧다(검증 → insert → 200).

## 핵심 규칙

### ScopedValue vs ThreadLocal

- 자식 가상 스레드로 전파 필요한 컨텍스트(traceId, userId 등) → `ScopedValue` (JDK 25 정식).
- 단일 스레드 내에서만 쓰는 `ThreadLocal` → 문제 없음.

### Structured Concurrency — 미채택 (참고용)

`StructuredTaskScope` 는 preview 라 쓰지 않는다. 병렬 fan-out 이 필요하면 `ExecutorService` 로 하고,
Phase 3 수신부처럼 순차로 충분한 곳은 순차로 둔다. GA 로 승격되면 아래 정책으로 재검토한다.

- 병렬 비동기 작업은 `StructuredTaskScope` + `Joiner` API 사용 (JDK 25 preview).
- 기본 정책: `Joiner.allSuccessfulOrThrow()` — 하나 실패 시 나머지 자동 취소.

### 비동기 코드 마이그레이션

- 가상 스레드에서 blocking 은 저렴 → 기존 비동기 패턴을 동기 스타일로 단순화.
- `CompletableFuture` 콜백 체인(`.thenApply`, `.thenCompose`) → 순차 동기 코드로 평탄화.
- 병렬 호출 필요 시 → `StructuredTaskScope`.

### 리소스 보호

- DB 커넥션 등 한정 자원은 `Semaphore` 로 보호 (스레드 풀 크기로 동시성 제어 금지).

### CPU 집약 작업

- CPU-bound 작업은 전용 플랫폼 스레드 풀에서 실행.

### Pinning 풀 격리

- JDK 24+ 에서 `synchronized` pinning 은 해결됨.
- 서드파티 라이브러리(네이티브, JNI 등)가 여전히 pinning 유발 시, 전용 플랫폼 스레드 풀로 격리.
- `-Djdk.tracePinnedThreads=short` 로 pinning 지점 탐지.

## 금지 사항

- 자식 스레드 전파 필요한 컨텍스트에 `ThreadLocal` 사용 금지 → `ScopedValue`.
- 가상 스레드를 `FixedThreadPool` 등으로 풀링 금지.
- 새 코드에서 `@Async` 사용 금지 → 동기 호출.
- `CompletableFuture` 콜백 체인 신규 작성 금지 → 동기 스타일.
- `--enable-preview` 빌드 플래그 금지 (`StructuredTaskScope` 포함).
