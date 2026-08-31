# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~25 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 구현 — T14.25 V18 backfill 탐색 비용 고정
- 적용된 V18은 변경하지 않았다. 기존 데이터 DB에서 V17 직후·V18 직전에 DBA가 transaction 밖 준비 SQL을 실행해
  `vndr_cmpl_yn IS NULL`인 `noti_id` 임시 partial index를 concurrent 생성한다.
- 처리 행은 임시 index에서 즉시 빠져 V18 batch가 처리된 PK prefix를 반복 탐색하지 않는다. V19는 V18 성공 뒤 임시
  index를 concurrent 제거한다. 준비·cleanup은 재실행 가능하고 빈 DB는 준비 SQL을 생략해도 안전하다.
- 정본 wiki 03을 `3b5c036`으로 푸시했고 서비스 사본을 포함한 설계 18개가 byte-identical이다.

## 검증 완료 (2026-08-31)
- PostgreSQL 17에서 1만 건 중 99%가 처리된 꼬리 분포의 실제 V18 후보 SQL이 임시 index를 선택함을 고정했다.
- 기존 데이터 upgrade의 V17→준비 SQL→V18→V19, 준비·cleanup 반복 실행, 빈 DB V1~V19 manifest를 검증했다.
- `./scripts/ci.sh` 최종 통과: local/production boundary, 전체 build/test, ktlint, API drift, dependency check green.
- Dependency-Check의 기존 `kafka-clients 4.2.1 / CVE-2026-41115` 경고 1건은 PLAN #42대로 보고서에 유지한다.
- converge 범위는 `eea4360..working tree`(검토 HEAD `4dbc3cd`). Codex 독립 design-sync는 clean 통과했고,
  code-reviewer(GPT-5 계열, effort 미노출)는 Critical·Improvement·Minor 0, 커밋 가능으로 판정했다.

## 다음 작업
- T14.25 후속 필수 작업은 없다. 기존 데이터 DB 운영 배포에서는 V17→준비 SQL→V18→V19 순서를 지킨다.
- 운영 논의 전까지 Phase 15는 보류한다.
- 열린 설계 항목은 PLAN 표를 기준으로 외부 조건이 추가로 확정된 항목부터 진행한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.
