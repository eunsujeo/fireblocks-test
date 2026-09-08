# 현재 도구와 관리 규칙

개발자가 빌드·검증 도구와 변경 기준을 확인하는 문서다. 설치 절차는 [SETUP](../SETUP.md), 테스트 작성은 [테스트 전략](testing.md)을 따른다.
이 문서는 저장소에 채택된 도구를 안내한다. 과거 비교·선정 근거는 [2026-08 도구 선정 이력](history/tooling-research-2026-08.md)에 보존한다.

## 스택과 버전 정본

| 용도 | 채택 도구 | 버전·설정 확인 위치 |
|---|---|---|
| 애플리케이션 | Kotlin·Spring Boot·Spring Kafka·Spring Batch | [version catalog](../gradle/libs.versions.toml), Boot BOM·모듈 lockfile |
| 빌드 | JDK toolchain·Gradle Wrapper | [루트 빌드](../build.gradle.kts) · [Wrapper 설정](../gradle/wrapper/gradle-wrapper.properties) |
| 테스트 | JUnit·AssertJ·MockK·Testcontainers·ArchUnit | [테스트 전략](testing.md) · version catalog |
| 로컬 EVM | Foundry/Anvil·Solidity | [test-support 빌드](../blockchain-manager-test-support/build.gradle.kts) · [로컬 통합 계약](design/10-local-fireblocks-integration.md) |
| 로컬 인프라 | Docker·PostgreSQL·Kafka | [실행기](../scripts/local.sh) · [Compose](../config/local-compose.yaml) |
| 진단 | 로컬 개발 DB의 psql·kcat, 선택적 IntelliJ MCP | [설치 안내](../SETUP.md) |

- 라이브러리 버전은 version catalog와 Boot BOM에서 관리한다. 문서마다 현재 패치 번호를 복사하지 않는다.
- 프로젝트 버전은 [VERSION](../VERSION), 의존성 해석 결과는 각 모듈 `gradle.lockfile`이 정본이다.
- 신규 의존성은 Maven Central에서 좌표를 검증하고 사용자 승인 후 별도 커밋한다. 변경 시 보안 항목과 호환성을 검증한다.
- kotlin-spring은 Spring 빈이 필요한 모듈에만 적용하며 순수 domain에는 적용하지 않는다.
- 사내 BOM 도입 등 보류·해결 상태는 [현재 계획](../PLAN.md)과 [해결 이력](history/resolved-design-items.md)을 따른다.

## 검사와 강제 장치

| 목적 | 실행·설정 |
|---|---|
| 전체 개발 게이트 | [scripts/ci.sh](../scripts/ci.sh) |
| 포맷 검사 | `./gradlew ktlintCheck` — 커밋 전에 실행 |
| 시크릿 검사 | [.githooks/pre-commit](../.githooks/pre-commit)의 gitleaks — 우회 금지 |
| 의존성 취약점 | [검사·예외 처리 절차](runbooks/dependency-vulnerability-scan.md) |
| 테스트·아키텍처 | [테스트 전략](testing.md) · [모듈 경계 검사](standards/architecture.md#자동-검증) |
| Claude Code 편집 hook·권한 | [.claude/settings.json](../.claude/settings.json) — 다른 도구도 같은 저장소 규칙을 직접 준수 |

Kotlin build cache 차단·한시적 suppression 등 미해결 보안 조치는 [PLAN 미해결 표](../PLAN.md)가 정본이다.
예외는 근거·만료일·추적 항목을 갖춰야 하며 게이트 실패나 검증 오류를 숨기지 않는다.

## AI 작업 도구

- 작업 진입점은 [AGENTS.md](../AGENTS.md)와 [CLAUDE.md](../CLAUDE.md)다. 저장소의 `.claude/rules/`·`skills/`·`agents/`를 따른다.
- `kotlin-lsp`·`commit-commands` 플러그인은 보류 상태이며 필수 설치 항목이 아니다.
- Fireblocks Docs MCP·context7은 제거됐다. IntelliJ 내장 MCP만 선택적으로 사용하며 brave mode는 켜지 않는다.
- API 자격증명을 가진 `fireblocks-mcp`는 금지한다. DB 도구는 로컬 개발 DB·읽기 전용으로 제한한다.
- 토큰·자격증명이 필요한 MCP 도입은 사용자와 권한 범위를 먼저 확인한다.
- 작업은 task 단위로 검증하고 [독립 리뷰 절차](ai/converge-review.md)를 따른다. 무인 반복은 불채택이며 Stop hook은 미도입이다.
- 세션 기록의 감사 보관은 하지 않는다. 확정 결정은 CLAUDE, 미해결은 PLAN, 다음 세션 인계는 PROGRESS에 기록한다.
