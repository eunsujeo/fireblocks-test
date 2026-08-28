# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 DAW 요청 기반 Sweep T14.11~19 완료. Phase 15는 계속 보류.**
- 공유 환경의 운영 화면·Network/Asset·정책/컨트랙트 workflow 소유자는 DAW-ADMIN이다. 이 저장소 Admin은
  DAW-CORE에 의존하지 않는 로컬 개발·진단 콘솔이며 제거해도 API/Webhook/BAT가 동작한다.
- Sweep 신규 실행은 BCM의 주기적 자율 선정을 유지하지 않고 DAW-CORE 요청을 접수한 뒤 BCM이 독립 검증·처리한다.
- eventId는 상태 전이 1건의 완료 키, txId는 거래 조회 키다. 체인 상태·outbox 발행·DAW 완료는 서로 덮어쓰지 않는다.

## 이번 구현
- waas-wiki 01·02·03·06·08·09 사본을 byte 동일하게 동기화했다.
- `PUT /events/{eventId}/completion`은 발행 성공한 DAW 토픽 event만 별도 불변 원장에 멱등 기록한다. txId·일반 status와
  sweep request/item/execution ID는 조회 projection으로만 반환한다.
- `POST /sweeps`는 단일 network/symbol의 1..N 고객 계정과 완료된 DEPOSIT/FINALIZED source event를 받는다. canonical hash,
  외부 요청 멱등, source event 1회 소비, 계정·자산·DAW 완료·gate를 한 트랜잭션 경계에서 검사한다.
- 두 endpoint는 `BCM_DAW_INTEGRATION_ENABLED` 기본 false이며 로컬 `up stub|fireblocks`만 true로 연다. UUID는 canonical v7만 허용한다.
- Webhook의 FINALIZED 자동 target 생성은 제거했다. BAT는 DAW 요청의 가장 오래된 PENDING item만 claim하며 요청 없이 실행하지 않는다.
- execution item은 request item FK를 필수로 보존한다. 실행 시작 시 request/item을 PROCESSING으로 바꾸고, 항목별 대사 결과에 따라
  COMPLETED/PENDING 및 request의 COMPLETED/PARTIAL/PROCESSING/ACCEPTED를 원자적으로 갱신한다.
- 실패·재시도는 item을 PENDING으로 되돌리고, 같은 계정·자산의 후속 요청은 선행 item 완료 뒤 처리한다. 기존 무요청 target은 V14에서 폐기한다.
- 가용 잔액 0은 실행 없이 `NOT_SUBMITTED/NO_SWEEP_REQUIRED`로 가장 오래된 item 하나를 완료한다. 양수 dust는 PENDING으로 보존한다.
- 최초 claim은 제출 시도 1회이며, 같은 submission의 실제 `FAILED→REQUESTED` 재획득만 target `try_cnt`를 올린다. REQUESTED 진행 중,
  intent 전 crash, 이미 SUBMITTED 회수는 실패로 세지 않는다.
- 항목 대사 성공·부분 실패·최상위 실패를 상태 갱신과 같은 트랜잭션의 `sweep-events` outbox에 기록한다. payload는 accountId 파티션과
  chainStatus/itemOutcome을 분리하며 request/item/execution/tx/hash를 보존한다.
- Admin Sweeps는 관련 식별자 하나로 request→item→execution→tx→event→DAW completion을 연결하고 policy·contract snapshot,
  서버 계산 nextAction을 보여준다. tx timeline도 outbox/DAW 완료를 구분한다. 적체·차단·발행 실패·완료 지연 gauge를 추가했다.
- V14 SQL에 완료 원장과 sweep request/item/source 원장을 추가하고 OpenAPI 실행 문서를 paths 25/schemas 89로 재생성했다.

## 검증 완료 (2026-08-28)
- hash/application/API, Webhook, BAT, persistence 및 로컬 Fireblocks 통합 테스트 전체 통과.
- DAW batch 요청→Stub+Anvil 실제 sweep→부분 성공 대사→항목별 `sweep-events` Kafka 소비→eventId 완료와 Admin 연결까지
  full 시스템 테스트 18/18 통과. 요청 전에는 FINALIZED 입금이 있어도 신규 target/execution이 0임을 함께 확인했다.
- Persistence 전체 162개를 한 번에 통과했다. DataJdbcTest pool의 idle 연결 선점을 제거하고 공유 DB 테스트가 자기 fixture만
  정리·판정하도록 바꿔 PostgreSQL 연결 고갈과 실행 순서 의존을 제거했다.
- `./scripts/ci.sh` 통과: 전체 build/test, ktlint, API 문서 drift, dependency check green. OpenAPI paths 25/schemas 89 fresh.
- 설계 사본 01·02·03·06·08·09·90은 waas-wiki 정본과 byte 동일.
- converge 범위는 base/HEAD `da5b0738cb1ced65e3b7a7d09f3af91275511d25` 대비 working tree tracked 79 + untracked 30,
  총 109개다. Codex 독립 reviewer agent(모델·effort는 실행기 metadata 미노출)가 design-sync `정합` 후 code-reviewer Critical 0·
  `커밋 가능`을 순차 판정했다. 두 reviewer 모두 읽기 전용이었다.

## 다음 작업
- 후속 후보는 V14 seeded upgrade, 0잔액 outbox rollback, 운영/Admin 쿼리 `EXPLAIN`이며, 공유 Admin 전에 PLAN #51 vault 대사를 paging한다.

## 외부 조건·후속
- Phase 15 전제는 Linux+systemd, 초기 API/Webhook/BAT 1/1/1. 실제 운영 배포는 기능 점검 후 별도 승인한다.
- 운영 DB SQL은 DBA가 직접 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.
- 공유 Admin mutation은 private listener, mTLS, 5분 이하 JWT 인증/인가 구현 전까지 닫는다.
- 실제 Fireblocks 변경 호출과 `manual-fireblocks` golden test는 명시 승인 경계를 유지한다.
