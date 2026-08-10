---
name: integration-test
description: Testcontainers 통합·슬라이스 테스트 배선 절차. 새 통합 테스트 클래스 추가, 새 모듈에 테스트 인프라 구축, 컨테이너/@ServiceConnection 문제 해결 시 사용.
---

# integration-test — Testcontainers 배선 절차

> T1.4~T1.5(2026-08-05)에서 채록. 규칙 정본은 docs/testing.md · .claude/rules/testing.md.

## 통합 테스트 클래스 추가 (기존 모듈)

1. 모듈의 `support/{모듈}TestSupport` 추상 클래스를 **상속**한다 — 컨테이너를 직접 선언하지 않는다
   (모듈당 싱글턴: bcm-api = `IntegrationTestSupport`, persistence = `PersistenceTestSupport`).
2. `@SpringBootTest`(E2E 세로줄, 최소한으로) 또는 `@DataJdbcTest + @Import(어댑터)`(슬라이스).
3. `@Testcontainers`/`@Container` 를 쓰지 않는다 — 베이스가 수동 `start()` 싱글턴이라 클래스마다 재기동이 없다.

## 새 모듈에 테스트 인프라 구축

1. test deps: `spring-boot-starter-test` + `spring-boot-testcontainers` + `testcontainers-postgresql`
   + `testcontainers-junit-jupiter` + `testRuntimeOnly junit-platform-launcher`.
2. **Boot 4 모듈화 함정** — 슬라이스 어노테이션은 별도 스타터다:
   `@DataJdbcTest` → `spring-boot-starter-data-jdbc-test`, 패키지 `org.springframework.boot.data.jdbc.test.autoconfigure`
   (Boot 3 의 `org.springframework.boot.test.autoconfigure.data.jdbc` 아님).
3. 라이브러리 모듈은 `@SpringBootConfiguration + @EnableAutoConfiguration` 앵커 클래스를 테스트 소스
   루트 패키지에 둔다 — 없으면 "Unable to find a @SpringBootConfiguration".
4. 싱글턴 컨테이너 베이스 작성:
   ```kotlin
   abstract class XxxTestSupport {
       companion object {
           @JvmStatic
           @ServiceConnection
           val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine").apply { start() }
       }
   }
   ```
   Testcontainers 2.0 주의: artifactId 는 `testcontainers-` 접두, 패키지는 `org.testcontainers.postgresql`(제네릭 없음).

## 자주 걸리는 것

- **Spring Data + Kotlin data class** → `kotlin-reflect` 필요 (`ClassNotFoundException: kotlin.reflect.full.KClasses`).
- **@Repository 빈 CGLIB 프록시** → Spring 빈 보유 모듈에 kotlin-spring(allopen) 플러그인
  (`Cannot subclass final class`). domain 은 제외 — 무의존 원칙.
- Flyway 마이그레이션은 persistence 의 main/resources 에 있다 — 테스트 클래스패스에 자동 포함.
- 검증은 좁게: `./gradlew :모듈:test`, 신선도 의심되면 `--rerun`. 결과는 build/test-results XML 로 확증.
