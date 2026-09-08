# 테스트 전략

> 계약(전이 표·dedup·outbox)이 서비스의 본체다. 테스트는 그 계약을 고정하는 장치 — 커버리지 숫자가 아니라 **계약 케이스의 전수**가 기준.

## 스택

| 용도 | 선택 | 비고 |
|---|---|---|
| 테스트 프레임워크 | **JUnit 6** (spring-boot-starter-test, Boot 4 관리 버전) | Boot 4 는 JUnit 6 를 관리한다. 5→6 API 차이는 작음 (org.junit.jupiter 패키지 유지) |
| 단언 | AssertJ 3.27.x | starter-test 포함 |
| mock | MockK 1.14.x + **springmockk 5.x** (`@MockkBean`) | springmockk 4.x 는 Boot 4 비호환. Boot 4 는 `@MockBean` 제거됨 — Mockito 혼용 금지 |
| 컨테이너 | Testcontainers **2.0.x** — `testcontainers-postgresql` · `testcontainers-kafka` | ★ 2.0 에서 artifactId 가 `testcontainers-` 접두로 개명. `spring-boot-testcontainers` + `@ServiceConnection` |
| 아키텍처 검증 | ArchUnit 1.4.x — **core API 를 일반 `@Test` 에서 호출** | `archunit-junit5` 엔진은 JUnit 6 플랫폼에서 안 돈다. junit6 모듈은 merge 됐으나 미출시(2026-08) — 출시되면 전환 |
| Kafka 경량 테스트 | `@EmbeddedKafka` (spring-kafka-test) | 리스너·직렬화 수준만. 통합은 Testcontainers Kafka |

버전 핀은 `gradle/libs.versions.toml` 이 정본 — Boot 관리 버전을 기본으로 쓰고, 위 표는 선정 근거다.

## 테스트 층

```
단위 (domain)          전이 표 · 이벤트 생성 · 트리거 판정 — 컨테이너 없음, ms 단위
      ↓
슬라이스 (infra)       @DataJdbcTest + PostgreSQL 컨테이너 — 매핑 왕복 · SKIP LOCKED
      ↓
통합 (app)             웹훅 투입 → 인박스 → 워커 → outbox → Kafka 소비까지 세로줄
      ↓
아키텍처 (전 모듈)     ArchUnit — domain 무의존 · api 에 비즈니스 로직 금지
```

- **domain 테스트가 최다**여야 한다. 통합 테스트는 세로줄당 소수의 굵은 시나리오만.
- 컨테이너는 모듈당 싱글턴 재사용 — 테스트마다 새로 띄우지 않는다.
- PostgreSQL 스키마는 `db/migration/manifest.txt`의 Git 관리 SQL을 Testcontainers 빈 DB에 순서대로 직접 실행한다.
  Flyway 이력이나 애플리케이션 운영 DDL에 의존하지 않는다.

## 반드시 테스트로 고정하는 계약 (설계 문서 → 테스트)

| 계약 | 근거 문서 | 대표 케이스 |
|---|---|---|
| 허용 전이 표 전 행 | 02-bcm-flow | FINALIZED→FAILED(reorg) 반영 · FINALIZED→CONFIRMED 무시 · (없음)→FINALIZED 감지 합성 · FAILED 종결 |
| evnt_id dedup | 02 | 같은 evnt_id 재소비 시 무변화. txId dedup 이 아님을 역케이스로 |
| noti_id dedup | 03 · 97 | 벤더 재전달(같은 noti_id) 재적재 안 됨 |
| outbox 원자성 | 02 · 03 | 워커 트랜잭션 롤백 시 tx_l·outbox_l 둘 다 미반영. 재기동 시 이중 발행 없음 |
| relay 순차 | 02 | 같은 계정 evnt_id 순 발송. 계정 다르면 병행 |
| 서명 검증 | 97 | 정상 통과 · 서명 없음 401 · 가짜 서명 401 · **원문 바이트 유지**(재직렬화 본문은 실패해야 정상) |
| cnfm_cnt 단조 | 02 | 감소 알림 무시 |
| 귀속 불명 입금 | 02 · 01 | 매핑에 없는 주소 → 큐에 싣지 않음 · 별도 알림 채널로 통지 |
| sweep 오분류 방지 | 01 · openapi.yaml | sweep 거래 웹훅이 고객 토픽(deposit·withdrawal·internal)에 발행되지 않음 |
| 이벤트 스키마 준수 | openapi.yaml | E2E 에서 소비한 메시지가 `ChainEvent` 스키마와 일치 (자동 대조) |
| 출금 제출 멱등 | openapi.yaml | 같은 externalTxId 재제출 시 중복 전송 없음 (벤더 mock 로 제출 1회 검증) |
| boost txId 접기 | 02 | 대체 거래(새 txId) 웹훅이 원 txId 거래로 반영 · DAW-CORE 에는 원 txId 이벤트만 |
| poison 격리 | (설계 결정 대기) | 파싱 실패 건이 정상 건 처리를 막지 않음 — 격리 방식 확정 후 케이스 구체화 |
| 대사 종결 한정 | 97 · 설계 | 종결 건(벤더 COMPLETED·FAILED·출금 REJECTED·BLOCKED)만 대조 — 진행 중 건은 제외 |

## 픽스처 규칙

- 웹훅 payload 는 **실물**만 — [docs/design/evidence/96-payload-sample.md](design/evidence/96-payload-sample.md) 원문을 `src/test/resources/payload/` 에 둔다. 필드를 지어내지 않는다.
- 필드 변형이 필요하면 실물에서 해당 필드만 바꾸고, 바꾼 필드를 테스트 이름에 드러낸다.
- 시간은 `Clock` 주입 — `Instant.now()` 직접 호출 금지.

## 실행

```bash
./gradlew test                          # 전체
./gradlew :blockchain-manager-domain:test    # 도메인만 (빠른 루프)
```

- 게이트 실행은 당분간 **로컬 `./scripts/ci.sh`** (전체 + ArchUnit + drift 체크) — 배포·CI 방식 확정 전까지 로컬 개발을 우선한다. 컨테이너 테스트 실패를 skip 으로 우회하지 않는다.
- 벤더 실호출(sandbox) 테스트는 기본 제외 태그 — 수동 체크리스트로만.
