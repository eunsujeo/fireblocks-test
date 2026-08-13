# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 6 T6.11과 converge를 완료했다. 다음 작업은 Phase 7 T7.5다.**
- 고객 vault별 제한 allowance와 운영 계정 `batchSweep`을 구현했다. approve·batch는 `SWEEP_APPROVE`·`SWEEP_BATCH`
  제출 원장으로 분리하고 `SweepExecution 1:N SweepItem`으로 항목별 대사한다.
- allowance는 온체인이 정본이다. cap 변경은 active item 없음 → approve(0) → 온체인 0 → 새 cap 순서이며,
  긴급 회수는 독립 게이트에서 REVOKING→REVOKED를 추적한다.
- batch는 ACTIVE allowance를 잠금 재검증하고 실행·N개 항목·N개 target claim을 원자 생성한다. 최상위 종결,
  network records, receipt `SweepLeg`가 실행·순번·원천·금액·실패코드까지 맞아야 COMPLETED/PARTIAL로 종결한다.
- T6.11 PostgreSQL/Flyway E2E는 vault 2개의 approve 선기록→cap 재관측→batch 단일 제출→1 성공·1 실패 대사→
  전부 approve(0)·온체인 0 재관측을 통과했다. 중복 batch 무제출·gasless·outbox 0건·게이트 fail-closed도 검증했다.
- converge 결함인 실행 경보 운영 빈과 저장 tx hash 대사 비교를 고쳤다. 관련 커밋은 `75d6ed9` 이전 이력에 있다.
- PLAN #39는 `bcm_sbmt_l.call_data TEXT`에 정규화 calldata를 저장해 해소했다. SWEEP 타입에서만 필수이고,
  코드·DB가 소문자 짝수바이트 `0x` hex를 강제하며 원장 필드로 cc-v1 hash를 재계산한다.
- cc-v1 금액은 NUMERIC(36,18)에 무손실 저장 가능한 non-negative만 허용해 approve(0)는 유지한다.
- 테스트 커밋 `9fc2b9f`, 구현 커밋 `5fe345b`; `./gradlew check ktlintCheck` 전체 391건 그린이다.
- Claude Code code-reviewer 최종 재검토는 Critical 0·커밋 가능 판정이다.
- waas-wiki 03 개정 `fe92927`은 origin/main에 포함됐고 최신 03·06을 포함한 설계 사본 전부가 byte-동일하다.
- Claude Code design-sync는 Critical 0·Phase 6 완료 가능으로 판정했다. OpenAPI 생성물 재생성 후 diff도 없다.
- 기존 Phase 7 T7.0~T7.4 구현은 보존돼 있다.

## 다음 작업

- PLAN 순서대로 Phase 7 T7.5 RBF 제출·이력·txId 접기를 테스트 우선으로 진행한다.
- T7.5 착수 시 V1의 축약된 `bcm_boost_l`을 최신 03 정의에 맞춘다. Phase 6 design-sync의 유일한 Improvement다.
- T7.6에서 대체 웹훅 접기 E2E와 converge를 수행한다.

## 주의·외부 조건

- **2026-08-13 사용자가 현재 변경의 commit·push를 명시적으로 허용했다.**
- `docs/design/`은 AI 직접 수정 금지다. 기존 DB는 없다는 사용자 확인에 따라 V1을 직접 수정했고 V2는 만들지 않았다.
- 실연동 전 TAP approve 매칭·Callback·gasless, gas/M/network records 지연, 컨트랙트 감사와 전체 회수 훈련이 필요하다.
- `TXRJ`는 코어 회신 후 단일 enum 상수만 교체한다. 경보 채널은 PLAN #13 확정 전 logging adapter다.
