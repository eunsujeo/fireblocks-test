# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~30 완료. Phase 15는 계속 보류.**
- 설계 정본은 이 저장소의 `docs/design/`다. 사용자 요청·확정 결정에 따라 직접 수정·리뷰한다.
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 작업 — 설계 저장소 독립 (2026-09-08, Codex)
- 사용자 요청에 따라 외부 wiki 의존성을 제거했다. 현재 저장소의 커밋된 설계를 기준으로 정본을 전환했다.
- CLAUDE.md·AGENTS.md·SETUP·README·문서 인덱스·PLAN 미해결 항목을 로컬 설계 기준으로 갱신했다.
- `.claude/settings.json`의 설계 Edit deny와 전용 PreToolUse hook을 제거했다. 시크릿 보호와 ktlint hook은 유지한다.
- design-sync·converge 절차와 Admin·DB skill에서 외부 저장소 비교·동기화 선행 조건을 제거했다.
- wiki 전용 URL·깨진 상대 링크는 로컬 정본 또는 명시적인 외부 출처 기록으로 바꿨다. 과거 완료 이력은 보존했다.
- 설계 전환은 문서 관리·참조 경로 변경이다. 런타임 코드·DB 스키마·API 계약·실측 payload는 변경하지 않았다.
- 외부 작업본은 읽거나 가져오지 않았다. 이번 작업은 정식 Phase converge가 아니다.
- 형제 저장소 없는 임시 복사본에서 설계 파일 링크 140개·hook 설정을 검증했다. 시크릿 보호·ktlint hook과 설계 코드 블록·실측 payload 보존을 확인했다.
- shell 문법·`./gradlew --offline ktlintCheck`·`git diff --check` 통과. 문서·도구 설정만 변경해 애플리케이션 테스트는 재실행하지 않았다.

## 직전 개선 — 입금 귀속·Sweep 정합 (2026-09-08)
- `3d6c9ab` 회귀 테스트·`1650315` 구현을 main에 커밋하고 origin/main에 push했다.
- 입금 주소 조회에 symbol 조건을 추가해 동일 주소의 다중 토큰 오귀속을 방지했다.
- 완료·부분 완료 Sweep의 reorg FAILED를 상태·요청·target·실패 outbox에 원자적으로 반영한다.
- 중복 무효화는 멱등 처리하며 기존 성공 이벤트와 후속 실행의 PROCESSING 요청·target claim은 보존한다.
- 잔액이 최소 수량 이상이거나 입금 스냅샷이 바뀌면 요청을 PENDING으로 유지해 후속 Sweep을 선택한다.
- 성공 대사에서 tx 잠금으로 FAILED 역전을 막고 request → target 잠금 순서를 맞췄다.
- 관련 테스트 229건·ktlintCheck·git diff --check 통과. 전체 CI·실벤더 수용 검증은 실행하지 않았다.

## 다음 작업
- 직전 코드 변경의 독립 리뷰 및 필요 시 전체 CI를 진행한다. 현행 Phase 표시는 이번 작업으로 재승인하지 않았다.
- 열린 설계 항목은 PLAN 표를 기준으로 이 저장소의 설계에서 확정한다.
- Phase 15는 운영 논의 재개 전까지 보류한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.
