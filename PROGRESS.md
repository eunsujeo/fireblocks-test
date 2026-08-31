# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~23 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 구현 — T14.23 PLAN #44
- tx 대사 창에서 이미 terminal로 관찰한 vendor tx ID를 `NOT IN` 개별 바인드로 펼치던 경로를 제거했다.
- Spring JDBC `SqlArrayValue("varchar", ...)`로 ID 집합을 PostgreSQL `varchar[]` 단일 파라미터로 전달한다.
- 제외 predicate는 `unnest`+`NOT EXISTS` hash anti join으로 두어 prepared generic plan에서도 배열을 행마다 선형 탐색하지 않는다.
- 후보 상태·종료·영속 backoff·정렬/limit·`FOR UPDATE SKIP LOCKED`·확인 횟수 선기록 계약은 변경하지 않았다.
- PLAN에 T14.23을 완료로 추가하고 #44를 `docs/history/resolved-design-items.md`로 이동했다. `docs/design/`은 미수정이다.

## 검증 완료 (2026-08-31)
- 70,001개 실제 배열 bind에서 PostgreSQL 파라미터 한계 없이 제외 행의 claim 횟수·시각을 보존함을 고정했다.
- PostgreSQL 17 `force_generic_plan` PREPARE/EXPLAIN으로 `Hash Anti Join`과 단일 `Function Scan on unnest`를 고정했다.
- `TxPersistenceTest` 20건과 `./scripts/ci.sh` 최종 통과: local/production boundary, 전체 build/test, ktlint, API drift, dependency check green.
- Dependency-Check의 기존 `kafka-clients 4.2.1 / CVE-2026-41115` 경고 1건은 PLAN #42대로 보고서에 유지한다.
- converge 범위는 `9be4a01a0857..working tree`. Codex 독립 읽기 전용 design-sync와 code-review가
  최종 Critical/Improvement/Minor 0으로 통과했다. 실제 모델명·effort는 reviewer metadata에 노출되지 않았다.

## 다음 작업
- T14.23 후속 필수 작업은 없다. 운영 논의 전까지 Phase 15는 보류한다.
- 열린 설계 항목은 PLAN 표를 기준으로 외부 조건이 추가로 확정된 항목부터 진행한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.
