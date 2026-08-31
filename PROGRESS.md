# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~24 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 구현 — T14.24 PLAN #45
- 판단 application이 이미 파싱한 `rawStatus`를 repository port에 boolean으로 전달하고 `P→S`와 같은 UPDATE에서
  `bcm_whk_l.vndr_cmpl_yn`을 남긴다. 메트릭·보관·삭제 보호는 같은 부분 인덱스 predicate를 공유한다.
- V17은 nullable expand와 insert/P→S 구버전 writer 호환 trigger를 짧게 설치한다. V18은 transaction 밖에서 기존 행을
  1,000건씩 commit하고 제약 검증·NOT NULL 전환 뒤 `CREATE INDEX CONCURRENTLY`를 수행한다. 전체 재실행도 안전하다.
- 정본 wiki 03과 사본을 갱신했다(wiki `6f1127c`, `9b3f1c6`, `c43c34f`, byte-identical).
- PLAN에 T14.24를 완료로 추가하고 #45를 `docs/history/resolved-design-items.md`로 이동했다.

## 검증 완료 (2026-08-31)
- 1만 webhook 대표 원장 EXPLAIN에서 `idx_bcm_whk_completed_archive` 사용을 고정했다.
- PostgreSQL 17에서 V17→구버전 P→S→V18→구버전 P→S, 1,001건 다중 batch, V18 전체 재실행을 검증했다.
- `./scripts/ci.sh` 최종 통과: local/production boundary, 전체 build/test, ktlint, API drift, dependency check green.
- Dependency-Check의 기존 `kafka-clients 4.2.1 / CVE-2026-41115` 경고 1건은 PLAN #42대로 보고서에 유지한다.
- converge 범위는 `b39c880..working tree`(검토 HEAD `93f3877`). Codex 독립 design-sync는 clean 통과했고,
  code-reviewer(GPT-5 계열, effort 미노출)는 Critical 0·커밋 가능으로 판정했다.

## 다음 작업
- T14.24 후속 필수 작업은 없다. 대규모 기존 원장에 V18을 적용하기 전에는 backfill NULL 후보용 임시 partial index나
  keyset 순회로 매 batch의 PK prefix 재탐색 I/O를 줄이는 code-review Improvement를 검토할 수 있다.
- 운영 논의 전까지 Phase 15는 보류한다.
- 열린 설계 항목은 PLAN 표를 기준으로 외부 조건이 추가로 확정된 항목부터 진행한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.
