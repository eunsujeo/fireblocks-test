---
paths:
  - "**/src/test/**/*.kt"
---
# Testing — 작성 규칙

> 스택·버전·계약 케이스 목록의 **정본은 [docs/testing.md](../../docs/testing.md)** — 이 파일은 테스트 코드를 "어떻게 쓰는가"만 다룬다.
> 스택 요약: JUnit 6 + AssertJ + MockK(`@MockkBean` = springmockk) + Testcontainers 2.0. Kotest 는 쓰지 않는다.

## 규칙

- **계약 로직(전이 표·dedup·outbox·서명 검증)은 테스트를 먼저 쓰고 실패(red)를 확인한 뒤 구현(green)한다** —
  리팩토링은 전부 그린인 상태에서만. (버그 수정은 재현 테스트 먼저 — test-writer 원칙과 동일)
- 테스트 메서드명: 한글 백틱 — `` `FINALIZED 에서 CONFIRMED 알림이 오면 무시한다`() `` 처럼 계약을 서술.
- Given-When-Then 또는 Arrange-Act-Assert 패턴.
- 단위 테스트에서 Spring context 로딩 금지 — MockK 로 의존성 mock.
- 테스트 간 상태 공유 금지, 각 테스트는 독립 실행 가능해야 함 (데이터 의존 금지 — 필요한 데이터는 각자 준비).
- 시간은 `Clock` 주입 — `Instant.now()` 직접 호출 금지.

## Fixture 패턴

- 외부 라이브러리 없이 직접 fixture 함수 작성. 테스트 소스에만 위치.
- 도메인 Entity/DTO 마다 `{feature}/fixture/` 패키지에 `{DomainName}Fixture.kt`, `object` + `fixture()` 메서드.
- 모든 필드에 합리적 기본값, 테스트에서 필요한 필드만 override. fixture 끼리 조합해 연관 데이터 생성.
- 테스트 내부에서 직접 생성자 호출로 데이터를 만들지 말고 fixture 함수를 통해 생성.
- **예외 — 웹훅 payload**: fixture 로 지어내지 않는다. docs/design/evidence/96-payload-sample.md 실물만 사용 (docs/testing.md 픽스처 규칙).

```kotlin
// fixture/AccountFixture.kt
object AccountFixture {
    fun fixture(
        accountId: String = "ACT-TEST-001",
        ref: String = "ref-001",
        status: AccountStatus = AccountStatus.ACTIVE,
    ): Account = Account(
        accountId = accountId,
        ref = ref,
        status = status,
    )
}

// 테스트에서
val account = AccountFixture.fixture(status = AccountStatus.SUSPENDED)
```

## 통합 테스트

- `@ServiceConnection` 으로 컨테이너 연결 (수동 property 주입 금지). companion object 에 `@JvmStatic`.
- **공통 컨테이너는 추상 클래스로 추출해 싱글턴 재사용** — 테스트 클래스마다 새 컨테이너 금지 (docs/testing.md).

```kotlin
abstract class IntegrationTestSupport {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:17-alpine")
            .apply { start() }
    }
}
```

### 슬라이스 테스트

- **Controller**: `@WebMvcTest` + `@MockkBean` 으로 service mock — MockMvc 로 요청/응답 검증.
- **Repository**: `@DataJdbcTest` + Testcontainers — `@Transactional` 로 테스트 간 롤백.
- **Full**: `@SpringBootTest` + Testcontainers — E2E 세로줄 시나리오, 최소한으로.

### API 테스트 패턴

- MockMvc + AssertJ/jsonPath 조합. 요청은 jackson serialize + `contentType(APPLICATION_JSON)`.
- 응답은 status 와 envelope(`data`/`error.code` — openapi.yaml 정본) 검증.
- 에러 케이스 필수 (잘못된 입력, 존재하지 않는 리소스, 멱등 재요청).

## 구조

```
src/test/kotlin/com/whatto/bcm/...
  ├── support/
  │   └── IntegrationTestSupport.kt   # Testcontainers 공통 설정 (싱글턴)
  └── {feature}/
      ├── fixture/        # Fixture factory 함수
      ├── controller/     # @WebMvcTest 슬라이스
      ├── service/        # 단위 테스트 (MockK)
      └── repository/     # @DataJdbcTest + Testcontainers
```
