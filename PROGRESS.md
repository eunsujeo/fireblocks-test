# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~22 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 구현 — T14.22 Vault 전체 대사 규모 경계
- 동기식 `GET /admin/vaults` 전체 메모리 결합을 제거했다. `POST /admin/vault-reconciliations`는 `202`로 접수하고
  `GET /admin/vault-reconciliations/{runId}`는 실행 상태와 고정 결과 cursor page를 기본 50·최대 100건 반환한다.
- V16에 실행·항목 원장을 추가했다. 시작 계정 snapshot은 `ACCEPTED→RUNNING`과 원자 고정하고 vendor page·다음 cursor·진행량을
  한 트랜잭션으로 기록한다. 완주 뒤에만 `PENDING→MISSING_IN_FIREBLOCKS`를 확정하며 부분 실패는 확인한 범위만 반환한다.
- worker claim/TTL fencing을 추가했다. vendor 호출 직전 claim을 갱신하고 현재 소유자만 page·완료·실패를 기록한다. 모든 인스턴스가
  60초마다 활성 실행을 탐색하되 claim 실패 worker는 호출 없이 종료하고, 만료 claim은 cursor부터 인계한다.
- claim TTL은 Fireblocks 보수적 최대 호출 시간보다 길어야 기동되며 초→밀리초 overflow도 fail-fast한다.
- vendor page는 중복 vault를 page 단위 검증하고 batch update/insert한다. 검색은 `%`·`_`도 literal substring으로 취급한다.
- 결과 상태와 항목은 `REPEATABLE_READ` snapshot에서 읽는다. UI도 진행 중 item/nextCursor를 숨기고 실패 requestId와 runId 복사를 제공한다.
- BFF·로컬 Admin·OpenAPI를 v0.10.0(paths 26/schemas 93)으로 갱신하고 #51을 해결 이력으로 이동했다.
- waas-wiki 정본 03·08을 변경하고 `docs/design/` 사본을 byte 동일하게 동기화했다.

## 검증 완료 (2026-08-31)
- 서비스·persistence·BFF·frontend 회귀 테스트에 재기동 snapshot, claim 경쟁/만료 인계/fencing, 미완주 완료 거부,
  duplicate vendor rollback, executor 거절, vendor 실패, cursor/limit, wildcard literal 검색을 고정했다.
- `./scripts/ci.sh` 최종 통과: local/production boundary, 전체 build/test, ktlint, API 문서 drift, dependency check 모두 green.
- Dependency-Check의 기존 `kafka-clients 4.2.1 / CVE-2026-41115` 경고 1건은 PLAN #42대로 보고서에 유지한다.
- converge 범위는 `5d9eb5a855ce..working tree`다. Codex 독립 읽기 전용 reviewer가 design-sync Critical/Improvement/Minor 0으로
  통과했고, 별도 code reviewer가 Critical 0·`커밋 가능`으로 판정했다. 모델·effort는 실행기 metadata에 노출되지 않았다.

## 다음 작업
- T14.22 후속 필수 작업은 없다. 운영 논의 전까지 Phase 15는 보류한다.
- 열린 설계 항목은 PLAN 표를 기준으로 외부 조건이 충족된 항목부터 선택한다. 실자금 전 #46·47·49·50 결정이 필요하다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.
