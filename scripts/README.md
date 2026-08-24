# 실행 스크립트 안내

개발자가 직접 실행하는 명령만 이 디렉터리의 최상위에 둔다. `internal/`과 `tests/`는 공개 진입점이 아니며,
README나 CI에서 안내하는 최상위 명령을 통해 실행한다.

## 개발자 명령

| 명령 | 용도 |
|---|---|
| `./scripts/local.sh` | 로컬 PostgreSQL·Kafka·API·Webhook·Admin과 Fireblocks 또는 Stub+Anvil 실행 |
| `./scripts/system-test.sh` | 독립된 smoke/full 시스템 테스트와 상태·로그 조회 |
| `./scripts/ci.sh` | 커밋 전 전체 검증 |
| `./scripts/system-test-ci.sh` | CI 제품에 연결할 PR/nightly/manual-fireblocks lane 선택 |
| `./scripts/fireblocks-contract-test.sh` | 승인된 실 Fireblocks 읽기 전용 계약 검사 |
| `./scripts/build-local-distribution.sh` | 폐쇄망용 Stub+Anvil 배포 파일 생성 |
| `./scripts/converge-review.sh` | Claude Code용 Phase 독립 리뷰 어댑터 |
| `./scripts/local.ps1` | Windows에서 `local.sh`를 호출하는 PowerShell 진입점 |

명령과 옵션은 `./scripts/local.sh help`, `./scripts/system-test.sh help`,
`./scripts/system-test-ci.sh help`로 확인한다.

## 내부 구조

- `internal/`: 공개 명령이 호출하는 Python 실행기와 폐쇄망 smoke 구현
- `tests/`: 스크립트 자체의 안전 경계·회귀 검사. `./scripts/ci.sh`가 실행 순서와 환경을 관리

내부 파일을 직접 실행하는 경로는 호환성을 보장하지 않는다. 새 기능은 기존 공개 명령의 하위 명령으로 추가하고,
해당 안전 계약을 `tests/`에 고정한다.

## 로그 위치

- 상시 로컬 환경: `build/local/<component>.log`; `./scripts/local.sh logs <component>`로 조회
- 시스템 테스트: `build/system-test/<runId>/logs/`; `./scripts/system-test.sh logs <runId> [component]`로 조회
- 실행 원장: 같은 runId 디렉터리의 `run.json`과 append-only `events.jsonl`

서비스 로그는 현재 Spring Boot 콘솔 텍스트 형식이다. 운영 JSON 포맷, 중앙 수집기, 보존·마스킹 정책은
Production 배포 준비가 재개될 때 확정하며, Secret·JWT·PEM·원문 Webhook body를 Admin 화면에 노출하지 않는다.
