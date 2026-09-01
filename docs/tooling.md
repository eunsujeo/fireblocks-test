# 도구·버전 확정 (2026-08 리서치)

> 리서치 근거와 함께 확정한 스택 버전, Claude Code 확장(skill·plugin·MCP) 도입 목록.
> 업그레이드 시 이 문서를 갱신한다.

## 1. 스택 버전 (2026-08 확정)

| 항목 | 버전 | 근거 |
|---|---|---|
| Spring Boot | **4.1.x** (현재 4.1.1) | OSS 지원 2027-07 까지. 3.5.x 는 2026-06-30 OSS 종료 — 신규 프로젝트에 부적합. 4.0.x 도 2026-12 종료라 4.1 이 착지점 |
| Spring Framework | 7.0.x | Boot 4.1 요구 (현재 7.0.9). Jakarta EE 11 · JSpecify null-safety |
| JDK | **25 LTS** | Spring 권장 LTS · Oracle 지원 2033. Boot 4.1 은 17~26 지원 |
| Kotlin | **2.3.x** (Boot-managed) | Boot 4.1 관리 버전. CVE-2026-53914의 안전한 2.4.20 GA 대기 중 — PLAN #41의 build cache 차단 유지 |
| Gradle | **9.6.x** · Kotlin DSL | Boot 4.1 은 8.14+/9.x 지원. version catalog 사용 |
| Foundry / Anvil | **1.7.1** | T11.2 결정적 EVM 체인. [공식 immutable release](https://github.com/foundry-rs/foundry/releases/tag/v1.7.1), 개발 빌드만 Forge 사용 · 실행 패키지는 Anvil만 사용 |
| Solidity | **0.8.35** · Prague | 테스트 ERC-20·운영 ABI Sweep artifact compiler. optimizer 200 + via IR + metadata hash 제거 |
| Spring Kafka | 4.1.1 | Boot 4.1 페어. ★ Boot 4 는 `spring-boot-starter-kafka` 명시 필요 (모듈화된 스타터) |
| Spring Batch | 6.0.x | Boot 4 페어. 6 은 메이저 개편 (`ChunkOrientedStep` 등) — 5.x 예제 코드 참고 시 주의 |

Boot 4 에서 신규 시작이라 싸게 얻는 것: Jackson 3 (2와 비호환 — 처음부터 3), 모듈화 스타터, JSpecify.

**openapi-request-validator-mockmvc 3.0.0** (2026-08-05 도입, T2.5 사용자 승인) — 스펙 스키마 자동 대조
(bcm-api testImplementation 한정). 구 swagger-request-validator 의 v3 개명판 — OpenAPI 3.1(`type: [x, "null"]` 배열)은
v3 부터 지원, 2.x 는 3.0 까지. repo1 실물·바이트코드(servlet 무참조 — spring-test FQCN 만) 확인, Framework 7 에서
실검출 동작 검증(스펙 밖 경로 → `validation.request.path.missing` ERROR). 자식 POM 의 spring 5.3 속성은
문서상 흔적일 뿐 바이너리는 무관함을 확인했다.

**사내 표준 정합 (2026-08-05 도입)** — 기존 프로젝트(사내 표준 빌드)와 대조해 반영:

| 항목 | 버전 | 비고 |
|---|---|---|
| jacoco | 0.8.15 | sonarqube CI 가 `jacocoTestReport`·`jacocoRootReport` 호출 — root 에 aggregation |
| sonarqube plugin | 7.3.1.8318 | root 적용 |
| git-properties plugin | 4.0.1 | 전 leaf 모듈 — actuator info·배포 추적 |
| sentry-bom | 8.51.0 | platform 만 — 실사용은 support 모듈에서 (architecture.md) |
| VERSION 파일 버저닝 | — | root `version = file("VERSION")` — 프로젝트 버전 단일 정본 |
| 컴파일러 플래그 | — | `-Xjsr305=strict` · `javaParameters`/`-parameters` · `-Xjvm-default=all` |
| 의존성 저장소 | — | Gradle Plugin Portal + Maven Central (settings.gradle.kts) |

**로컬 개발 인프라 (2026-08-05 확정)** — 테스트는 **Testcontainers**(PostgreSQL·Kafka, `@ServiceConnection`) 유지.
앱 직접 실행은 **`spring-boot-docker-compose`(developmentOnly) + 루트 `compose.yaml`** — bootRun 이 compose 서비스를
자동 기동·연결한다. 도입 시점: postgres 는 Phase 1, kafka(KRaft 단일 노드, apache/kafka)는 Phase 4.
관측·디버깅은 `psql`(로컬 dev DB 한정)·`kcat` — MCP 보류 결정과 일치. README 의 docker run + env 방식은 compose 도입 시 대체.

**미도입 (사내 기준과의 의도적 차이)**: 사내 Spring Boot BOM + spring-dependency-management 플러그인 — Boot 4.1.x 지원 팀 확인 대기 (PLAN 미해결 #9). kotlin-spring(allopen)은 사내 기준(전 모듈)과 달리 **Spring 빈을 가진 모듈만**(app + infra/persistence·client — @Repository·@Configuration CGLIB 프록시가 final 클래스에 못 붙는 실측, 2026-08-05). **domain 은 제외** — 무의존 원칙. 구모듈명(manager-api)은 표준 architecture.md 의 bcm-api 로 대체.

**버전 관리 규칙**: 전 버전은 `gradle/libs.versions.toml` 단일 관리. 분기별로 패치 추종 + release note 의 보안 항목 확인.
CI 의존성 취약점 검사는 OWASP Dependency-Check 12.2.2 aggregate task를 사용하고 CVSS 7.0 이상 또는 분석 오류에서 실패한다.
기본 데이터 소스는 NVD 공식 JSON 2.0 feed이며 CI는 Gradle user home의 NVD DB를 캐시한다. 운영용 내부 mirror는
`NVD_DATAFEED_URL`로 교체한다. suppression은 근거·만료일·추적 이슈 없이 추가하지 않으며, 추가된 규칙이 더 이상 쓰이지 않아도
빌드를 실패시킨다.

**2026-08-17 스캔 후속** — pgJDBC는 CVE-2026-54291 수정 계열의 현재 42.7.13, Log4j는 CVE-2026-49844 수정 버전 2.25.5로
Boot BOM을 좁게 보완한다. Kotlin CVE-2026-53914는 build cache metadata 문제이며 안전한 2.4.20 GA가 Maven Central에 아직
없다. `org.gradle.caching=false`와 CI `--no-build-cache`로 실제 영향면을 차단하고, runtime stdlib/reflect CPE 오탐 suppression은
PLAN #41·2026-09-30 만료로 제한한다.

## 2. Claude Code — skill·plugin (리서치 결론)

2026-08 기준 **Kotlin+Spring 백엔드용 쓸 만한 기성 skill 은 사실상 없다** (anthropics/skills 는 문서·프론트엔드 중심, 서드파티는 Java+Maven 기반 소수). 결론: **first-party 플러그인 + 자체 skill 작성**.

### 도입

| 것 | 무엇 | 설치 |
|---|---|---|
| 내장 `/code-review` · `/security-review` | 별도 설치 불필요 — 저장소 전용 리뷰는 `.claude/agents/code-reviewer` 와 병용 | — |

**플러그인 보류 강등 (2026-08-05 재검토)**:
- `kotlin-lsp` — 작업 환경이 IntelliJ 라 **내장 MCP(3절)가 대체**. Phase 0 을 LSP 없이 무리 없이 진행했고
  컴파일 검증 정본은 `./gradlew build`. Claude Code 의 Kotlin 탐색이 답답해지면 재검토.
- `commit-commands` — 실수요 0 (커밋 규율이 문서+관행으로 충분, Phase 0 에서 수동 커밋 20여 개 무리 없음).

### 자체 작성 (해당 규약이 코드로 생기는 Phase 에서)

| skill 후보 | 내용 | 시점 |
|---|---|---|
| `integration-test` | Testcontainers 배선 — 싱글턴 컨테이너 · `@ServiceConnection` · 모듈 단독 실행 | Phase 0~1 |
| `db-migration` | Git SQL 순서·코어 규약 컬럼(일시 VARCHAR(16) 등) · 감사 4컬럼 절차 | Phase 1 |
| `kafka-patterns` | 토픽·파티션 키 규약 · outbox→relay 테스트 레시피 | Phase 3 |

스타일 규칙은 skill 이 아니라 CLAUDE.md + ktlint/detekt(강제)에 둔다.

### 보류 · 불채택

- 서드파티 Spring skill 모음(rrezartprebreza/spring-boot-skills 등) — Java+Maven 전제라 그대로 도입 불가. 필요 시 개별 파일만 참고해 Kotlin/Gradle 로 재작성.
- Testcontainers 공식 skill 저장소 — 아직 Go/.NET 만 있음. JVM 추가되면 재검토.
- **improve-codebase-architecture (mattpocock) — 불채택 (2026-08-05 검토).** 능동적 아키텍처 리팩토링 제안 skill —
  이 저장소의 "요청받지 않은 리팩토링 제안 금지"·"확정 결정 재제안 금지"와 정면 충돌. 아키텍처는 사내 표준 + ArchUnit 으로
  고정·강제 중이라 개선 제안 통로가 불필요. 전제(CONTEXT.md·ADR·GitHub RFC)도 전부 불일치.
- **grill-me (mattpocock) — 보류 (2026-08-05 검토).** 계획 스트레스 테스트 인터뷰 — 해는 없으나(호출형) 실수요 없음:
  결정 전 검토·게이트 확인·converge 교차 검증이 그 역할을 이미 수행. **skill 없이 "이 설계 그릴해줘" 요청으로 동일 효과.**
- **grill-with-docs (mattpocock) — 불채택 (2026-08-05 검토).** "domain glossary 갱신" 동작이 정본 침범 —
  용어 정본은 docs/design(수정 금지 사본), ADR 은 불채택. mattpocock 생태계의 CONTEXT.md·ADR 전제가
  우리 정본 체계와 구조적 불일치 (3회째 확인).
- **tdd (mattpocock) — skill 불채택 · 원칙 흡수 (2026-08-05 검토).** "무엇을" 테스트하나는 우리 규율이 이미 더 구체적
  (계약 표 전수·실물 payload). 보태는 "순서"(red→green→refactor)만 .claude/rules/testing.md 에 한 줄로 흡수.

## 3. MCP 서버 (리서치 결론)

### 도입 (2026-08-05 재검토 — Phase 0 실사용 0회 근거로 축소)

| MCP | 무엇 | 상태 |
|---|---|---|
| **IntelliJ 내장 MCP** (2025.2+ IDE 기본 내장) | Kotlin 인스펙션·디버거·리팩터링 | 선택 (user 설정) — brave mode 금지 |

**Fireblocks Docs MCP — 제거 (2026-08-05, Phase 2 converge).** "실사용 실적 없으면 제거" 조건의 집행 —
T2.2(벤더 클라이언트, 실수요 지점)에서 실사용 0회. 세션 미연결이었고 JWT 서명·엔드포인트 근거는
공식 문서 + fireblocks-openapi-spec 실물로 충분했다. 벤더 문서가 다시 필요하면 WebFetch(developers.fireblocks.com)로 조회.

**context7 — 제거 (2026-08-05).** 목적(컷오프 보완)은 WebSearch/WebFetch 로 대체되고, Phase 0 실사용 0회.
버전·좌표는 문서 조회보다 **정본 실물 검증**(repo1 메타데이터·jar 검사)이 이 저장소의 "추측 금지" 규율에 맞는 패턴임이 확인됐다
(검색 API 의 낡은 인덱스가 오답을 준 실사례). 안 쓰는 제3자 문서 유입 통로(prompt-injection 표면)는 닫는다.

### 보류 / 금지

| 것 | 판정 | 이유 |
|---|---|---|
| **fireblocks-mcp (API-level)** | **금지** | 워크스페이스 자격증명을 쥐고 write 가능 옵션 존재 — 수탁 백엔드에서 벤더 호출은 감사된 우리 코드로만 |
| GitHub MCP | 보류 | `gh` CLI 로 동일 기능 — 상시 PAT 만 늘어남 |
| Postgres MCP | 보류 | 공식 서버는 보안 문제로 archived, 대안(Pro)은 개발 정체. read-only DB 롤 + `psql` 이 더 안전 |
| Kafka MCP (mcp-confluent) | 보류 | 로컬 루프엔 `kcat`/console-consumer 로 충분. 도구 50+ 로 컨텍스트 부담 |
| Gradle MCP | 불필요 | 커뮤니티 서버 archived — `./gradlew` Bash 실행이 낫다 |
| Spring Initializr MCP | 불필요 | 방치 상태. `curl start.spring.io` 로 대체 |

### 원칙

- DB 계열 MCP 는 도입하더라도 **로컬 dev DB 만 · read-only**. 운영 DB 연결 금지.
- 토큰·자격증명이 필요한 MCP 는 도입 전 권한 범위를 사용자와 확인.

## 4. 강제 장치 (2026-08 리서치 반영 · 사용자 확정)

지시문(CLAUDE.md)은 권고일 뿐이라, 어겨선 안 되는 것은 결정론적 장치로 이중화한다.

| 장치 | 무엇 | 위치 |
|---|---|---|
| PreToolUse hook + deny | docs/design/ 쓰기를 **실행 전** 차단 (deny 규칙과 이중) | `.claude/hooks/pre-edit-guard.sh` + settings.json deny |
| PostToolUse hook | `.kt` 편집 직후 ktlint 단일 파일 검사 | `.claude/hooks/post-edit.sh` |
| permission deny | `.env*`·인증서(pem/p12/jks)·secrets 경로 Read 차단 | `.claude/settings.json` |
| permission allow | `./gradlew`·git 읽기 명령 사전 허용 (승인 피로 제거) | `.claude/settings.json` |
| pre-commit | **gitleaks** 시크릿 스캔, fail-closed (사내 표준 없음 → gitleaks 로 확정, 2026-08-04). 오탐 예외는 `.gitleaks.toml` 전역 `[[allowlists]]`+targetRules 로 파일 단위 최소 등재 — 변경 시 canary 양방향 검증 (2026-08-05) | `.githooks/pre-commit` + `git config core.hooksPath .githooks` + `.gitleaks.toml` |
| dependency locking | `gradle.lockfile` 커밋 — 헛것 패키지(slopsquatting) 방어의 기반 | Phase 0 |

세션 기록의 감사 보관은 **하지 않기로 확정** (2026-08-04 사용자 결정).

## 5. loop / graph engineering 판단 (2026-08 리서치)

- **loop engineering** — 실체 있는 2026 주류 용어 (Osmani·O'Reilly·IBM). 본질 = "사람이 프롬프트를 치는 것"에서
  "에이전트를 검증 루프(테스트 통과 = 종료 조건) 안에 넣고 그 루프를 설계하는 것"으로의 전환.
  **부분 채택**: task 단위 bounded loop (완료 기준 테스트 = 종료 조건 · 반복 상한 · fresh context · 사람이 지켜보며) —
  PLAN 의 task 층·완료 정의와 그대로 맞물린다. Stop hook 은 미도입 — task 층을 운영해 보고 필요성이 확인되면 추가.
  **불채택**: 무인 야간 반복(Ralph loop 원형) — 성공 사례가 전부 저위험 greenfield 이고, 수탁지갑 도메인은
  사람 승인 게이트(diff 승인) 유지가 맞다.
- **graph engineering** — 생성 2주째의 논쟁적 신조어 (다중 에이전트 조직을 그래프로 설계). LangChain 스스로
  "LangGraph 로 해오던 것, 새것 아님"이라 반박. 1인 감독·단일 서비스에는 과잉 — **관망**.
- 용어 계보: prompt → context (2025 주류) → harness → loop (2026-06) → graph (2026-07, 논쟁 중).
  루프는 컨텍스트 오류를 증폭하므로 context 위생(짧은 세션·문서 정본)이 전제라는 점에 유의.
