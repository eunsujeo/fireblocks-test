# blockchain-manager 구현 로드맵

> 설계 정본은 이 저장소의 [docs/design/](docs/design/), HTTP API 계약은 [docs/api/openapi.yaml](docs/api/openapi.yaml) (그대로 구현).
> 각 Phase 는 **세로줄(동작하는 얇은 경로)** 단위 — 층별로 넓게 깔지 않는다.

## 현재 위치

- [x] Phase 0 — 프로젝트 스캐폴드 (2026-08-04)
- [x] Phase 1 — 도메인 + DB (2026-08-05)
- [x] Phase 2 — API 공통 규약 + 계정·주소·잔액 (2026-08-05)
- [x] Phase 3 — 웹훅 수신 (2026-08-06)
- [x] Phase 4 — 판단 워커 + outbox + relay (입금 E2E) (2026-08-07)
- [x] Phase 5 — 출금·내부이체 (출금 E2E) (2026-08-10)
- [x] Phase 6 — sweep (approve + transferFrom 배치로 재개) (2026-08-13)
- [x] Phase 7 — 막힘 점검 · 자동 boost (2026-08-13)
- [x] Phase 8 — 배치 3종 — tx 대사 · 원본 보관 · 수수료 시계열 (2026-08-14)
- [x] Phase 9 — 운영 보강 (2026-08-17)
- [x] Phase 10 — Blockchain Manager Admin (2026-08-17)
- [x] Phase 11 — 로컬 블록체인 + Fireblocks 통합 테스트 환경 (2026-08-20)
- [x] Phase 12 — 전체 시스템 통합 테스트·진단 (2026-08-20)
- [x] Phase 13 — Webhook 독립 경계 + 로컬 기능 점검 UX (2026-08-21)
- [x] Phase 14 — 실행 가능한 API 개발자 포털 + 로컬 시나리오 콘솔 (2026-08-21)
- [x] Phase 14 후속 — Admin 운영 등록·진단 UX 수렴 (2026-08-24)
- [x] Phase 14 후속 — DAW 요청 기반 Sweep·이벤트 완료 확인 (2026-08-28)
- [ ] Phase 15 — Production 배포 준비 계획 (보류 — 운영 논의 재개 시 착수)

## 작업 규칙 (모든 Phase 공통)

- **설계 관리** — `docs/design/`를 이 저장소에서 직접 수정·리뷰한다. 외부 wiki 동기화를 선행 조건으로 두지 않는다.
  아래 완료 항목의 사본 동기화 기록은 2026-09-08 정본 전환 이전의 이력이다.
- **task 층** — Phase 착수 시 첫 작업은 그 Phase 를 체크박스 task 로 분해하는 것이다. task 마다
  완료 기준(어떤 테스트가 통과하면 done)과 근거 설계 절을 붙인다. 분해 결과는 이 문서의 해당 Phase 아래에 둔다.
- **세션 단위** — 1 세션 = task 1~2개 = 리뷰 가능한 diff 1개. 세션이 끝나면 [PROGRESS.md](PROGRESS.md) 갱신.
- **Phase 마무리(converge)** — 완료 기준 통과 후 ① 독립 읽기 전용 design-sync ② 지적 반영 후 독립 code-reviewer를
  **순차 실행** ③ 체크박스와 PROGRESS 갱신. Claude Code는 `./scripts/converge-review.sh <agent> <base>`로 재개하고,
  Codex는 동일한 `.claude/agents/` 체크리스트를 읽은 별도 reviewer agent/session으로 대체할 수 있다. 구현 세션의 자기 승인은
  금지하며 마지막 성공 리뷰의 도구·범위·commit을 PROGRESS에 기록한다. 셋이 끝나야 Phase 종료다.

## 완료 Phase 상세 이력

Phase 0~14의 task·완료 기준·검증 증적은 [완료 Phase 0~14 상세 이력](docs/history/phase-0-14-plan.md)으로 분리했다.
현재 계획 문서에는 다음 작업과 아직 열린 설계 항목만 유지한다.

## Phase 14 후속 — Admin 운영 등록·진단 UX 수렴

- [x] **T14.6 Network·Asset 선택 UX** — Fireblocks 전체 카탈로그는 진단용으로 동기화하되 기본 Network 화면은 BCM 연결분만
  보여 준다. 자산 후보는 검색 간 최대 20개를 유지하고 모든 후보를 검증한 뒤 한 트랜잭션으로 등록한다.
- [x] **T14.7 Vault·sweep 준비 상태** — Fireblocks 전체 vault와 BCM 계정을 읽기 전용으로 대조하고
  `MANAGED/UNMANAGED/MISSING_IN_FIREBLOCKS`를 표시한다. Contracts·Policies는 활성 binding/evidence와 sweep 실행 금지 사유를 함께 보여 준다.
- [x] **T14.8 관리 책임·보안 경계** — 공유 환경의 Network·Asset·정책·컨트랙트 workflow 소유자를 DAW-ADMIN으로 정정한다.
  이 저장소의 Admin은 로컬 개발·진단 콘솔이며, Fireblocks Security Admin Vault 자격증명을 BCM에 두지 않는다.
- [x] **T14.9 실패 계약·문서** — 일괄 등록 실패의 index·network·symbol·reason을 BCM API→로컬 BFF→UI까지 보존하고
  OpenAPI 실행 문서와 회귀 테스트를 갱신한다.
- [x] **T14.10 사본 동기화·converge** — waas-wiki 06·07·08·10을 `docs/design/`에 byte 동일하게 복사하고 전체 CI 후
  독립 design-sync→code-reviewer를 순차 통과한다.

## Phase 14 후속 — DAW 요청 기반 Sweep·이벤트 완료 확인

Phase 15와 분리해 기능 계약부터 수렴한다. DAW-CORE가 고객 vault 자산의 Sweep 필요를 요청하고 BCM은 요청을 불변 원장에
접수한 뒤 온체인 잔액·입금 FINALIZED·활성 정책/컨트랙트·hard ceiling·실행 gate를 독립적으로 검증해 실행한다.
DAW 요청은 실행 승인을 대신하거나 금액·allowance·TAP/Callback·컨트랙트 안전 조건을 완화하지 않는다. DAW-ADMIN은
정책·컨트랙트 관리 workflow 소유자이며 런타임 Sweep 요청자는 아니다.
BCM이 Kafka에 발행한 입금·출금·내부이체와 새 Sweep 결과는 DAW-CORE가 업무 transaction까지 반영한 뒤 BCM의 완료 확인
API를 호출한다. 체인 `CONFIRMED/FINALIZED`, outbox 발행 성공, DAW-CORE 처리 완료는 서로 다른 상태로 보존한다.

식별자는 역할을 섞지 않는다. `eventId`는 DAW-CORE가 실제 소비한 **상태 전이 1건**의 dedup·완료 키, `txId`는 한 온체인
거래의 상태 이벤트들을 묶는 조회 키다. `externalTxId/externalSweepRequestId`는 DAW 요청 멱등·업무 상관관계, `sweepRequestId`는
BCM 접수 원장, `sweepItemId`는 BCM이 부여한 고객 계정 항목, `executionId`는 BCM이 같은 network·symbol로 조립한 온체인 batch 실행을 뜻한다.
완료 원장은 eventId를 PK로 쓰되 txId·요청/항목/실행 ID를 join해 운영자가 한 세로줄로 조회할 수 있게 한다.
Sweep 이벤트는 batch transaction의 `chainStatus`와 고객 leg의 `itemOutcome(SUCCEEDED/FAILED)`을 분리한다. 일부 leg가 실패해도
온체인 transaction은 FINALIZED일 수 있으므로 공통 `FAILED` 하나로 두 의미를 합치지 않는다.

### 계획 task

- [x] **T14.11 DAW batch 요청 계약 결정** — `POST /sweeps`는 `externalSweepRequestId`, 단일 `network/symbol`, 1~N개의
  `{accountId, sourceEventIds[]}`를 받으며 같은 요청 안의 accountId 중복은 거절한다. sourceEvent는 해당 account/network/symbol의
  DEPOSIT+FINALIZED이고 DAW 완료 확인까지 기록된 event만 허용하며, 같은 sourceEvent를 다른 유효 요청에 중복 귀속하지 않는다.
  DAW는 금액·vault 주소·컨트랙트를 지정하지 않고 BCM이 `sweepItemId`를 부여해 실행 직전 실제 available balance와 활성 정책으로
  금액을 결정한다. 요청이 정책 batch 상한보다 크면 여러 `executionId`로 분할하되 각 항목·sourceEvent 관계를 보존한다. 동일 외부
  요청 ID+동일 hash는 같은 접수 결과, 다른 payload는 409다.
- [x] **T14.12 DAW 이벤트 완료 확인 계약** — `PUT /events/{eventId}/completion`을 DAW-CORE의 멱등 acknowledgement로 둔다.
  최초·응답 유실 후 재호출은 같은 성공을 반환하고, 미존재 event·아직 발행되지 않은 event·다른 consumer의 위조 완료는 거절한다.
  완료는 체인 상태나 `bcm_outbox_l.evnt_stcd`를 덮어쓰지 않고 `(eventId, consumer)` 별도 불변 원장과 BCM 수신 시각으로 남긴다.
  응답에는 대응 `txId/status`와 Sweep이면 request/item/execution ID를 반환한다. `FINALIZED` 완료 뒤 reorg `FAILED`가 새 eventId로
  오면 DAW-CORE가 별도로 반영·완료해야 하며, txId 단위 완료로 앞선 event들을 일괄 완료하지 않는다.
- [x] **T14.13 설계 정본 변경·사본 동기화** — waas-wiki 02 flow·03 DB·06 sweep·08 Admin에서 자동 target 생성과 주기 자율 선정을
  DAW 요청 접수→검증→queue→batch 실행으로 바꾸고 `docs/design/` 사본을 byte 동일하게 동기화한다. 요청 중복·여러 입금 합류·
  STOP 중 접수·실행 전 추가 입금·같은 account의 후속 요청 합류·부분 성공·취소 가능 시점과 Kafka 발행→DAW 반영→완료 확인 흐름도
  명시한다. Sweep 결과는
  체인 batch 1건이 아니라 고객 `sweepItemId`별 이벤트로 발행하고 partition key는 `accountId`로 고정한다.
- [x] **T14.14 OpenAPI·실행 문서** — 멱등 `POST /sweeps`와 `PUT /events/{eventId}/completion`의 성공·400/404/409/422 응답,
  Sweep request/item/result schema와 `eventId/txId/externalSweepRequestId/sweepRequestId/sweepItemId/executionId/vendorTxId/txHash`의
  역할, `chainStatus`와 `itemOutcome/requestedAmount/actualAmount/failureCode`를 계약화하고 카테고리형 실행 문서를 재생성한다.
  거래 조회는 txId, 소비 멱등·완료는 eventId라는 원칙을 예제에 고정한다.
- [x] **T14.15 요청·완료 원장과 동시성 경계** — append-only `bcm_swp_req_l`·event/실행 연결 및 DAW 완료 원장, request hash·멱등
  unique·claim을 추가한다. 완료 원장은 `eventId + consumer`를 유일키로 하고 발행 성공 event만 받으며 txId는 조회 projection으로 둔다.
  여러 요청이 한 실행으로 합쳐지거나 한 요청이 여러 실행으로 나뉘어도 각 item 결과를 잃지 않고, 같은 계정·자산 동시 요청과 API
  응답 유실에도 중복 실행을 만들지 않는다. 완료되지 않은 필수 consumer event는 outbox 정리 대상에서 제외하고, 완료 원장과 outbox를
  함께 archive/보존하는 규칙도 확정한다. 기존 pending target의 전환·폐기 규칙도 SQL에 명시한다.
- [x] **T14.16 API 접수·검증 세로줄** — DAW 서비스 신원과 Sweep/완료 입력을 검증하고 요청을 외부 호출 전에 저장한다. 계정 소유·지원 Network·
  자산 mapping·source deposit FINALIZED를 확인하되, 최종 잔액·정책·컨트랙트·gate는 claim 및 제출 직전에 다시 확인한다.
  공유 환경 인증이 준비되지 않은 동안 endpoint는 기본 비활성이고 로컬 profile만 명시적으로 연다.
- [x] **T14.17 BAT 실행 경계 전환** — Webhook의 자동 `bcm_swp_trgt` 생성과 BAT의 무요청 자율 후보 선정을 제거한다. BAT는 접수된
  요청만 grouping/claim해 기존 allowance·batch transferFrom·정책 snapshot·crash-safe 제출을 재사용하고, 이미 SUBMITTING인 실행의
  회수와 제출 후 reconciliation은 계속 주기 실행한다.
- [x] **T14.18 Admin·관측·실패 복구** (2026-08-28) — Admin은 요청자·요청 ID·queue/blocked/submitted/partial/finalized/failed 상태와 전체 관련 ID,
  정책/컨트랙트 snapshot, 재시도 가능 여부를 조회 전용으로 표시한다. 요청 적체·오래된 claim·반복 실패·gate 차단을 metric/alert에
  추가한다. txId 검색에서는 전체 상태 event와 각 eventId 완료 여부를, Sweep 요청 검색에서는 item→execution→tx→event→completion을
  보여 준다. Kafka 발행 뒤 미완료 건수·최장 대기 시간도 보이되 로컬 Admin이 운영 완료를 대신 만들지 않는다.
  항목별 결과는 상태 전이와 같은 트랜잭션의 `sweep-events` outbox에 넣고 accountId 파티션 payload를 고정했다. Admin의 Sweeps 화면은
  request/external request/item/execution/tx/hash/source·result event 어느 식별자로도 같은 세로줄을 찾고 policy·contract snapshot,
  outbox 상태, DAW completion, 서버 계산 `retryable/nextAction`을 표시한다. 요청·event·완료 적체와 최장 대기는 별도 gauge로 노출한다.
- [x] **T14.19 테스트·전환 converge** (2026-08-28) — Sweep/완료 API 멱등·충돌, FINALIZED 부재, 동시 요청, STOP·정책 drift, 부분 성공, 응답 유실,
  같은 txId의 CONFIRMED/FINALIZED 개별 완료, reorg 후 새 FAILED 완료, 다항목·다실행 분할, 재기동 회수를 고정한다. full 시스템 테스트는
  DAW 역할의 batch API 요청에서 실제 Stub+Anvil sweep과 대사, 항목별 Kafka 소비 뒤 eventId 완료 확인까지 관통해야 하며,
  FINALIZED batch의 일부 item 실패도 `chainStatus=FINALIZED/itemOutcome=FAILED`로 보존해야 한다. 요청이 없으면 FINALIZED 입금이 있어도
  신규 sweep이 생기지 않음을 검증한다. 전체 CI 후 독립 design-sync→code-reviewer를 통과한다.
- [x] **T14.20 전환·원자성 회귀 테스트** (2026-08-31) — V13의 READY/SUBMITTING/PARTIAL/FAILED/COMPLETED 실행과 RETRY 항목을
  실제 PostgreSQL에 seed한 뒤 V14를 적용해 legacy 요청·항목 백필, 실행 항목 FK, claimed target 보존과 무요청 target 폐기를 검증한다.
  0잔액 무실행 종결은 실제 outbox JSON 적재 실패를 일으켜 request/item/target 변경이 모두 rollback되고 경보로 격리됨을 고정한다.
- [x] **T14.21 운영 조회 실행계획 고정** (2026-08-31) — 1만 건 대표 원장에서 Sweep/거래 Admin 식별자 검색과 적체 집계의
  PostgreSQL `EXPLAIN`을 검증한다. V15에 webhook/outbox vendor, Sweep item JSON·tx hash·submission, 완료 대기·반복 실패 조회 index를
  추가하고, 가장 오래된 DAW 미완료 event는 시간순 index에서 첫 행만 읽도록 조회한다.
- [x] **T14.22 Vault 전체 대사 규모 경계** (2026-08-31) — 단일 HTTP 요청의 전체 vendor/계정 메모리 결합을 제거했다.
  `202 ACCEPTED` 비동기 실행과 V16 실행·항목 원장, page/cursor 원자 기록·재기동 재개, 완료 전 MISSING 미확정,
  고정 결과 cursor와 기본 50/최대 100건 응답, 활성 실행 1개 제한을 API·BFF·로컬 Admin과 PostgreSQL 회귀 테스트에 반영했다.
- [x] **T14.23 tx 대사 제외 ID 단일 파라미터화** (2026-08-31) — 창 안에서 이미 종결 관찰한 vendor tx ID 집합을
  `NOT IN` 개별 바인드 대신 PostgreSQL `varchar[]` 단일 파라미터를 `unnest` 한 뒤 hash anti join으로 제외한다.
  70,001개 ID의 바인드 한계·claim 쿼터 보존과 PostgreSQL generic plan의 `Hash Anti Join`·단일 `unnest`를 회귀 테스트로 고정했다.
- [x] **T14.24 미보관 COMPLETED 선별 비용 고정** (2026-08-31) — 판단 워커가 지원 transaction event를 성공 처리하는
  같은 UPDATE에서 application이 파싱한 결과로 `vndr_cmpl_yn`을 남긴다. V17 expand·구버전 호환 trigger와 V18의 1,000건 단위
  commit backfill·`CREATE INDEX CONCURRENTLY`로 롤링 전환한 뒤 부분 인덱스로 선별한다.
  1만 건 대표 원장의 PostgreSQL `EXPLAIN`에서 `idx_bcm_whk_completed_archive` 사용을 고정해 60초 API·BAT 메트릭과
  일 보관 배치가 보존량에 비례해 payload JSON을 반복 파싱하지 않게 했다.
- [x] **T14.25 V18 기존 원장 backfill 탐색 비용 고정** (2026-08-31) — 기존 데이터가 있는 DB는 V17 직후·V18 직전에
  `vndr_cmpl_yn IS NULL` 임시 partial index를 transaction 밖에서 생성하고, V19가 V18 성공 뒤 concurrent 제거한다.
  1만 건 중 99%가 처리된 꼬리 분포의 PostgreSQL `EXPLAIN`에서 임시 index 사용을 고정해 각 batch가 처리된 PK prefix를
  반복 탐색하지 않게 했다. 빈 DB manifest와 준비·cleanup SQL 재실행은 index 부재를 정상으로 취급한다.
- [x] **T14.26 Fireblocks 자산·Webhook 문서 계약 정정** (2026-08-31) — 07의 신규 API 응답에서 체인 폐기 여부를
  `metadata.deprecated`, 온체인 자산 소수 자릿수를 `onchain.decimals` 중첩 위치로 바로잡고 FIAT용 root `decimals`와 구분했다.
  파서는 중첩값을 우선하고 root 값은 FIAT·기존 평면 응답 호환 fallback으로만 쓴다. Webhooks V2 재전송은 `resend_failed` 최근 24시간,
  resource ID 최대 30일, query 최근 72시간·요청 창 최대 24시간으로 API별 범위를 분리했다. BCM 수동 복구는 기존처럼
  `resend_failed`만 사용하고 24시간보다 오래된 거래 공백은 tx 대사로 회수한다. 99 복구 설계와 runbook의 30일 잔존 문구도
  같은 계약으로 정정했으며 런타임 계약은 바뀌지 않는다.
- [x] **T14.27 계정·주소 생성 결과 회수 원장** (2026-09-01) — V20 `bcm_acnt_crtn_l`·`bcm_addr_crtn_l`에
  사전 생성 의도, 40자 이하 현재 Fireblocks 멱등 키 세대·마지막 POST 준비 시각, vault 이름·assetId snapshot과 시도 횟수를 먼저 커밋한다. 벤더 응답과
  `bcm_acnt_m`·`bcm_addr_m` 공개 매핑은 한 DB 트랜잭션으로 완료하고, 재시도는 전체 page의 exact vault 이름 또는 wallet 주소를
  조회해 유일 후보만 회수한다. 후보 복수·cursor 반복은 계정 HTTP 409·주소별 `CONFLICT`로 fail-closed한다. 후보가 없으면 유효한
  현재 키는 남은 24시간 창이 벤더 최장 호출시간 전체를 수용할 때만 쓰고, 마지막 POST 준비 + 설정된 벤더 최장 호출시간 +
  24시간 + 초 단위 정밀도 여유 1초의 안전시각 전이면
  `CREATION_RETRY_LATER`로 보류하며 최장 호출시간은 5분 이하로 제한한다. 안전시각 뒤 최신 시도만 CAS로 새 키를 준비하고,
  완료도 호출에 사용한 키 세대를 행 잠금 아래 검사해 옛 호출 결과가 새 세대 매핑을 선점하지 못하게 한다. 동시 요청이 먼저 완료한
  의도도 공개 매핑으로 수렴한다. CI 취약점 검사에서 확인한 Spring Framework
  CVE-2026-59313/59314는 Spring Boot 4.1.1(Framework 7.0.9)로 올려 함께 해소했다.
- [x] **T14.28 생성 원장 PostgreSQL 동시성·rollback 테스트 보강** (2026-09-01) — 계정·주소 각각 새 키 세대 준비
  트랜잭션이 행 잠금을 보유한 동안 옛 세대 완료가 대기하고, 커밋 뒤 `CREATION_RETRY_LATER`로 매핑 없이 거절됨을 실제 두
  PostgreSQL 트랜잭션과 latch로 고정했다. 완료 트랜잭션의 공개 매핑 INSERT 뒤 원장 UPDATE에 test trigger로 예외를 주입해
  매핑과 원장 완료가 모두 rollback되는 것도 검증했다. `FOR UPDATE`를 제거한 회귀 상태에서는 두 경합 테스트가 실패한다.
- [x] **T14.29 GET API 오류 계약 정합** (2026-09-01) — `depositAddressesOf`·`balancesOf`의 계정 경로변수와 선택
  query 검증 실패가 런타임에서 `400 VALIDATION_FAILED`인 기존 동작을 OpenAPI v0.10.2 응답 표면에 명시했다. 두 GET의
  실제 400 envelope를 OpenAPI validator로 대조해 파라미터 스키마와 응답 계약이 다시 어긋나지 않게 고정했다.
- [x] **T14.30 잔액 미발급·벤더 drift 계약 고정** (2026-09-01) — 계정은 있지만 조건에 맞는 `bcm_addr_m` 발급
  기록이 없으면 벤더를 호출하지 않고 `200 data: []`, 발급된 자산의 실제 0잔액은 자산 행과 문자열 `"0"`으로 구분한다.
  로컬 발급 기록 뒤 벤더 wallet 404는 drift이므로 빈 결과로 숨기지 않고 `500 INTERNAL`로 전파하며 ERROR로 기록한다.
  OpenAPI v0.10.3과 서비스·HTTP 스펙 회귀 테스트로 경계를 고정했다.

**예상 공수**: 1명 10~16인일(설계·API/DB 3~4, 실행 전환 3~5, 완료 확인·Admin/관측 2~3, 시스템 테스트·converge 2~4).
실 Fireblocks mutation은 포함하지 않으며 별도 명시 승인 전까지 Stub+Anvil로 검증한다.

## 문서 정리 계획 — 탐색과 중복 축소 (2026-09-08)

사용자 승인에 따라 D1~D4를 완료했다. 착수 시 `docs/`는 47개 파일·Markdown 37개였으며 API 생성물도 포함한다.
목표는 처음 보는 사람이 목적에 맞는 문서를 두 번 이내의 링크 이동으로 찾는 것이다.
설계 계약·실측 증거·미해결 결정은 보존하고, 문서 배치와 중복 설명을 정리한다. Phase 15 보류와 별개다.

- [x] **D1 목적별 입구 정리 — 우선 적용**: `docs/README.md`를 60줄 이내, 시작/구조/업무/API/운영/참고의
  6개 주 진입 링크로 구성한다. 시작은 루트 README·SETUP, 구조는 standards/architecture, 업무는 design/README,
  API는 api/README, 운영은 runbooks/README, 참고는 같은 문서 아래 참고자료 절로 연결한다.
  design/README는 번호 순 나열 대신 계정·자산, 거래·이벤트, Sweep, Admin, DB, 로컬 테스트별 읽기 경로를 제공한다.
  루트 README의 낡은 Phase 문구는 PLAN 링크로 대체하고, 01-infra·CLAUDE의 중복 전체 목차는 design/README로 연결한다.
  완료 기준: 로컬 실행·모듈 경계·입금 흐름·API 호출·웹훅 복구를 각각 두 번 이내에 찾고 정본 위치를 알 수 있다.
- [x] **D2 중복 설명과 오래된 안내 축소**: admin-reference/README는 승인 화면·토큰 안내만 남기고 정책·권한은
  08-bcm-admin으로 연결한다. 02/99는 처리 계약과 장애 사례 상세의 소유 범위를 나누고 반복 설명은 링크로 대체한다.
  06/98은 현행 실행 계약·출시 조건과 대안 검토 근거를 구분한다. 98의 고유 조건은 대조 후 06에 보존하고 연결한다.
  operational-metrics와 operational-alert-channel은 runbooks/monitoring.md로 합치고 복구·취약점·배포 계획은 별도 유지한다.
  tooling은 현행 도구·관리 규칙 중심으로 줄이고 과거 검토 근거는 history로 옮긴다. SETUP의 제거된 MCP 안내 등도 대조한다.
  완료 기준: 같은 정책·절차를 두 곳에서 독립 편집하지 않고, 충돌은 임의 해결 없이 PLAN 미해결 표에서 추적한다.
- [x] **D3 참고자료 배치와 긴 문서 탐색 개선**: design/90·93~97은 design/evidence/로, 04·05·12는
  design/context/로 이동해 실측 근거와 외부 시스템 맥락을 구분한다. 98은 고유 현행 조건 대조 후 evidence로 이동한다.
  03-bcm-db(2,039줄)·08-bcm-admin(686줄)은 본문을 더 쪼개기 전에 피처별 목차·절 링크를 보강한다.
  api.md·api.html·spec.js는 배포·공유에 쓰는 생성물로 유지하고 주 읽기 경로는 API 포털로 통일한다.
  완료 기준: 증거 원문 보존, 현행 계약의 참고자료 오분류 없음, 문서·코드·스크립트·.claude 경로 참조 동시 갱신.
- [x] **D4 문서 검증과 유지 규칙**: 상대 링크·heading anchor·이동 전 경로 잔존을 검사하고 코드가 읽는 경로를 대조한다.
  API 생성물 무변경과 포털/BootJar 리소스 경로를 확인한다. 문서마다 대상 독자·범위·정본 링크를 짧게 표시한다.
  새 문서는 기존 문서에 담을 수 없는 독립 목적이 있을 때만 추가한다. 완료 기준은 탐색 목표·링크 유효·계약/증거 유실 0이다.

실행 단위는 D1, D2, D3+D4로 나눈다. 파일 수의 일괄 감축이나 새 문서 사이트 도입은 목표로 삼지 않는다.
검증: 내부 링크·절 앵커, 이동 자료 원문·DB 정의·출시 조건 보존, OpenAPI 재생성 무변경, API 포털 테스트 11건,
전체 ktlintCheck와 API 리소스 패키징 입력 대조를 통과했다. 모듈 경계 보완은 테스트(c9489c4)·구현(59db18d)으로 분리했고 문서 정리도 별도 커밋으로 완료했다.

## Fireblocks·Dfns·로컬 블록체인 호환 계획 (2026-09-14)

**첫 번째 목표는 Fireblocks·Dfns·로컬 블록체인 호환이다.** 같은 API·업무·이벤트·멱등·복구 계약을 세 실행 환경에서 검증하는 [상세 계획](docs/dfns-compatibility-plan.md)이다.
시작 시 `BCM_PROVIDER=fireblocks|dfns|local`로 구현 하나를 조립한다. 요청별 다중 벤더 routing은 초기 범위에서 제외한다.
Fireblocks 지원을 유지하며 종료·자산 이전을 완료 조건으로 두지 않는다. 기존 Stub→Anvil을 재사용하고 Dfns 로컬 검증 경로를 확장한다.
Dfns 경로의 구성은 **노드 직접 운영 업체 + Dfns Baseline 전체 플랫폼 + DAWBC + DAW-CORE**다. 사용자 지정 wiki의 Baseline 정의를 반영했다.
코드/API 인벤토리·공통 호출 시간/웹훅/생성 재시도·Fireblocks/로컬 조립·DB 원천 대조·네트워크 지갑 원장·증적 보관 저장소와 결합 복구 검증·공식 명세 기반 Dfns 인증/지갑 HTTP 어댑터와 보류/충돌 HTTP 계약을 구현했다. 공개 주소 API의 Dfns 연결·조건부 조립·실제 원천 등록·실벤더 검증·운영 전환은 미완료다. Phase 15 보류는 유지한다.
호환 기준은 현행 29개 OpenAPI operation과 런타임 운영 경로·C01~24다. #51 신규 책임 완성은 별도 후속 마일스톤으로 추적한다.
Ethereum·Base·Solana와 USDC·KRWK는 모델 목표다. 사용자 후속 지시에 따라 **멀티체인 인터페이스 선행·실제 체인 확장 후속**으로 조정했다.
초기 Dfns 체인/자산·발행/주소·규모/SLO·기존 주소는 DF0에서 고정한다. 후속 체인 구현·감사는 초기 출시를 막지 않는다.
자산 식별·확정·대납을 체인별로 분리하고, 미지원 기능을 비활성화한 상태를 완전 호환으로 계산하지 않는다.

- [x] **계획 작성** — 기능별 호환 기준·단계/선행 조건·PoC·출시 게이트·주소/데이터 전환·롤백·외부 의존 정리.
- [ ] **DF0 범위 고정** — 29 API·24영역의 Fireblocks/Dfns/로컬 지원표·초기/후속 체인·자산 구분표, Baseline/RPC 공동 명세·운영 책임·기존 주소·SLO 확정.
- [ ] **DF1 설계** — 공통 제공자 포트·체인 환경·로컬 연결/모사 경계, 자산/계정·확정·대납 인터페이스/입출력·초기 집금·독립 승인·DB/API를 01/02/03/06/07/08/09와 대조.
- [ ] **DF2 성립성 검증** — Baseline/RPC 검증환경, 초기 EVM 범위에 P0~7과 해당 체인 사례로 승인·대납·집금·복구 확인.
- [ ] **DF3 기반 구현** — Fireblocks/Dfns·로컬 Stub/Anvil 경로의 벤더 포트·BCM_PROVIDER 조건부 조립/설정 검증·DB 확장/백필·인증·멱등·체인 인터페이스/초기 어댑터·Dfns Stub, 선택값별 기동/비선택 호출 0·미지원 차단·기존 실행 환경 회귀 검증.
- [ ] **DF4 기본 업무** — 계정/주소/잔액·출금/내부이체/조회·입금/웹훅·outbox·CORE 완료 동등성 검증.
- [ ] **DF5 스윕·보안** — 초기 EVM 집금·권한 회수·부분 결과·대납·재시도/가속·비용·독립 승인 검증.
- [ ] **DF6 운영 기능** — 대사·보관·수동 복구·Admin·비상·밴드S와 #46/#47/#49/#50 계약 해결.
- [ ] **DF7 후속 신규 기능 — 확정 미구현 책임** — #51 주소별 잔고·소유/용도·예약·직접 집금 완성, 양 벤더 공통 검증.
- [ ] **DF8 수용 시험** — Fireblocks/Dfns/로컬 공통 E2E·초기 필수 조합·인터페이스 계약·RPC/플랫폼/Vault/MPC 복구·성능/비용, 해당 시 주소/데이터 전환 훈련.
- [ ] **DF9 독립 리뷰·인수** — design-sync→code-reviewer 순차 검토와 보안·운영 인수, 실제 전환 승인 자료 준비.

- [ ] **MC0~2 후속 멀티체인 확장** — 추가 체인 상세 계약→어댑터/노드/집금·대납 구현→해당 조합 수용·독립 리뷰·활성화. 초기 출시의 선행 조건 아님.

### 착수 작업 (2026-09-14)

- [x] **DF0.1 코드/API 호환 인벤토리** — [29 API·구현/테스트 위치·24영역](docs/design/evidence/92-provider-compatibility-inventory.md)을 고정하고 Fireblocks/로컬 현행과 Dfns 미구현·실환경 미검증을 구분했다. 실제 토큰 배포·Baseline·SLO는 미확정이므로 DF0 전체는 열어 둔다.
- [x] **DF1.1 공통 포트 설계·첫 의존 분리** — [설계12](docs/design/12-provider-compatibility.md)에 경계/입출력·조건부 조립·API/DB 선행 조건을 기록. `VendorExecutionLimits`를 추가하고 지갑 생성/제출/지갑 대사/미응답 회수/boost의 FireblocksProperties 직접 의존을 제거했다. 기존 산식·엄격한 TTL 경계를 보존. 테스트 컴파일 red→green, 신규 6건 포함 관련 22건(API5/BAT13/client4)·전체 ktlintCheck 통과. 기존 테스트 수정 없음. Phase converge 미수행.
- [x] **DF3.1 제공자 조립 경계** — `BCM_PROVIDER` 필수 선택·Fireblocks/로컬 조건부 조립, 미구현 Dfns 조기 거절, 선택 자격/로컬 API·JWKS·RPC/기존 모드 충돌 검증과 실행 스크립트 연결. 기존 배치 게이트 유지. 세 앱의 조기 실패·단일 포트·로컬 실제 EVM 이체·실행 경계 회귀 및 ktlint 검증은 [설계12](docs/design/12-provider-compatibility.md)에 기록. DDL/OpenAPI 변경·실벤더 호출·Phase converge 없음.
- [x] **DF1.2 Dfns 계약 대조** — 사용자 지정 Baseline 자료와 공개 지갑/멱등/웹훅 명세를 [연결 계약13](docs/design/13-dfns-contracts.md)에 대조했다. 실제 도입 릴리스·서명 원문/멱등 확답은 미확보이며 공개 명세를 Baseline 실측으로 취급하지 않는다. 계정·네트워크 wallet 관계, 원천·요청/이동/수신 시도·CORE 이벤트 분리와 DB/API 선행 변경을 기록했다. DDL/OpenAPI 변경 없음.
- [x] **DF3.2 웹훅 공통 수신 경계** — WebhookProtocol/WebhookEnvelope(domain)와 FireblocksWebhookProtocol(infra)로 헤더·id/eventType/data.id 해석을 분리. 서명 성공 후 envelope 파싱, 원문·실제 서명·해시와 기존 인박스 처리 유지. 비선택/누락/중복 헤더 거절·대체 protocol 대역 검증 추가. Dfns 프로토콜/인증/기동은 미구현이며 차단 유지. 검증은 설계12에 기록.
- [x] **DF1.3 영속 원천 binding 상세 설계** — 03에 단일 데이터셋의 `bcm_prvd_bndg_m` 컬럼/PK/허용 조합·앱 조회 전용 권한·기존 데이터 원천 확인/백필·기동 전 불일치 차단·롤백 계약을 상세화했다. 현행 ID/이벤트/원문을 보존하며 자동 원천 등록·혼합 원천 수용은 금지한다. SQL/guard는 DF3.4에서 구현했으며 Dfns 개별 wallet/요청/이동 연결 테이블은 후속이다.
- [x] **DF3.3 지갑 생성 재시도 정책 분리** — `WalletCreationPolicy`와 조건부 `FireblocksWalletCreationPolicy`를 추가했다. 공통 생성 정책은 판정을 위임하고 API는 시간 상한만으로 Fireblocks의 24시간 규칙을 선택하지 않는다. 기존 시간 경계 테스트를 Fireblocks 소유 모듈로 이동하고 판정 전달·정책 누락 거절·선택 조립·계정/로컬 실행 회귀를 검증한다. 검증 결과는 설계12에 기록한다.
- [x] **DF3.4 영속 원천 검증** — V21·ProviderOrigin/Repository·조회 어댑터·필수 원천 설정과 세 앱의 처리 시작 전 guard를 구현했다. 누락/불일치/조회 실패는 중단하며 전역 lazy 설정에서도 검증한다. 기존 원천의 자동 등록/재표기는 없다. PostgreSQL 제약·조회 전용 역할·기존 의도/보관 원문 보존, 세 앱 실패 시 벤더 호출/실행 빈 생성 0, Fireblocks/별도 로컬 DB 회귀 포함 161건·전체 ktlintCheck 통과. local 스크립트와 신규 Stub DB 원천 초기화·등록 runbook을 연결했다. 실제 원천 등록/DB 권한 부여·운영 적용·Dfns 실행 수용은 미수행이다.
- [x] **DF3.5 네트워크 지갑 생성·회수 인터페이스** — NetworkWalletProvisioningPort·scope/request/정규화 관찰과 순수 RecoveryPolicy를 구현했다. 미관찰/미완료/주소 대기는 새 POST를 허가하지 않고 원천·네트워크·correlation·조직 소유/known ID 불일치와 중복 지갑은 충돌로 구분한다. 03·13에 논리 계정/네트워크 지갑/자산 주소의 키·의도 CAS·원자 완료·호환 전환 및 API 후속 계약을 상세화했다. 기존 accountId의 vault 설명 3곳만 교정하고 API 문서를 재생성했다. 신규 14건 포함 domain18/API49=67건·포털11건·전체 ktlintCheck 통과. 영속화/업무 연결·Dfns 어댑터/Stub은 미구현이다.
- [x] **DF3.6 네트워크 지갑 생성 의도 영속화** — V22의 의도·조회 페이지/후보·완료 연결 4테이블과 domain Repository/상태 판단·JDBC 어댑터를 구현했다. 독립 커밋의 최초 제출 권한, 원천 대조·행 잠금/revision CAS, cursor 재개/반복·이전 worker 거절, 미완료 조회에서도 known ID 보존, 충돌 증적·완료 연결 원자 저장을 검증했다. 신규16건 포함 domain17/persistence41/API60/Webhook3/BAT2=123건·전체 ktlintCheck 통과. 기존 SQL V1~20/계정/미완료 의도 보존 확인. 증적은 참조/hash를 저장하며 실제 원문 보관 어댑터·논리 계정 전환·API/벤더 호출자는 후속이다. 기존 계정/주소 DDL과 Dfns 기동 차단 유지.
- [x] **DF3.7 논리 계정 모델 분리와 내부 생성 유스케이스** — V23·Account의 VAULT/LOGICAL 구분과 원천/모델 제약, 논리 계정 독립 예약을 구현했다. 기존 vault 소비 지점을 명시적 검증으로 전환하고 논리 계정의 vault 대사를 거절한다. 내부 생성 서비스는 V22의 최초 제출 권한과 포트를 연결하고 응답 유실 뒤 조회만 재개하며 실제 바이트/해시의 증적 보관 입구를 요구한다. 신규23건 포함 domain17/application12/persistence47/API60/BAT44/Webhook7=187건·전체 ktlintCheck 통과. 기존 Fireblocks/로컬·SQL 업그레이드 회귀와 기존 assertion 유지. 공개 API/실제 증적 저장소/Dfns 어댑터는 미연결이며 Dfns 기동 차단 유지.
- [x] **DF3.8 응답 증적 보호 저장소와 서비스·실제 DB 결합 복구 검증** — 원문 접근권한·보관/조회·무결성·실패 전파 계약을 03의 V24와 계약13에 고정하고 `bcm_ntwk_wlt_evdc_l`(원문 BYTEA·DB 계산 길이/SHA-256 CHECK·append-only)과 `NetworkWalletEvidenceJdbcAdapter`를 구현했다. 내부 생성 서비스와 실제 PostgreSQL 원장·증적 저장소를 별도 Dfns 데이터셋에서 결합해 최초 생성 1 POST, 응답 유실 뒤 재시작 조회 회수, 증적 저장 실패 전파, 원장 저장 실패 후 증적 보존·롤백·재개, 동시 요청 create 1회, 완료 재요청의 외부 호출 0을 검증했다. 공식 OpenAPI 1.1018.3의 지갑 작업/응답 schema를 보관 대상으로 기록했고 실제 Baseline 대조·서명 원문은 수용 항목으로 남겼다. 벤더 포트는 내부 대역이며 Dfns 기동 차단·기본 빈 미연결을 유지한다. 검증 기록은 [설계12](docs/design/12-provider-compatibility.md#응답-증적-보관과-서비스db-결합-검증-2026-09-15).
- [x] **DF3.9 Dfns 보류/충돌 HTTP 계약과 공식 명세 기반 인증·지갑 어댑터** — Pending/Conflict의 공개 HTTP 매핑을 OpenAPI 0.11.0(`PROVISIONING_PENDING` 503·`Retry-After`, 충돌은 기존 `CONFLICT`)과 `NetworkWalletCreationIntent.requireCompleted`·API 수용 테스트로 고정했다. 공식 OpenAPI 1.1018.3과 공식 Credentials data·Signing flows 문서(해시는 계약13)를 근거로 `DfnsCredentialSigner`(Key credential clientData·EC/RSA/Ed25519 서명)·`DfnsUserActionClient`(`/auth/action/init`→`/auth/action`→`X-DFNS-USERACTION`)·`DfnsNetworkWalletClient`(`POST /wallets`·`GET /wallets/{id}`·`GET /wallets` externalId 필터, 저장 requestHash 대조, 404 원문 보존, custodial/위임/Vault/status 소유 판정)를 구현하고 MockRestServiceServer 계약 테스트와 실제 PostgreSQL V24 증적 결합(응답 바이트=보관 바이트=DB 해시)을 검증했다. 어댑터는 실행 빈으로 등록하지 않고 `BCM_PROVIDER=dfns` 기동 차단·공개 주소 API의 Dfns 연결·Baseline 수용(clientData `origin` 요구 여부·토큰 유효기간·429·서명 원문)은 후속이다. 검증 기록은 [설계12](docs/design/12-provider-compatibility.md#dfns-인증지갑-http-어댑터와-보류충돌-계약-검증-2026-09-15).
- [x] **DF3.10 공개 계정·주소 API의 Dfns 연결과 조건부 조립** — `AccountOperations` 경계로 제공자별 유스케이스를 나누고 `DfnsAccountService`가 논리 계정 멱등 등록, 논리 계정·활성 매핑·수신 주소 모델(`bcm.dfns.account-address-networks`) 선검증, `(origin, accountId, network)` 지갑 의도 예약과 생성/회수, Ready 지갑 주소의 `bcm_addr_m` 저장(EVM 계정 모델 네트워크만), 항목별 `PROVISIONING_PENDING`/`CONFLICT` 반환, 잔액 조회 422 거절을 구현했다. `ConditionalOnDfnsProtocol`·`DfnsClientConfig`·`DfnsAccountConfig`로 `dfns`에서만 조립하고 `AccountService`·`WalletProvisioningConfig`는 Fireblocks 한정으로 바꿨다. 단위·조립(실제 DB + 명세 형태 로컬 HTTP) 검증은 [설계12](docs/design/12-provider-compatibility.md#dfns-계정주소-api-연결과-조건부-조립-검증-2026-09-15). API 전체 `BCM_PROVIDER=dfns` 기동 차단은 유지하며 해제는 Baseline 수용 뒤 사용자 결정이다.
- [x] **DF3.11 Dfns 데이터셋 자산 매핑 등록과 잔액 계약** — 계약13·07·03·09에 Dfns 자산 키(`<Network>:Native|Erc20:<contract>`)·DBA seed 네트워크 행·EVM 모델 한정·온체인 대조 수용 항목과 `GET /wallets/{walletId}/assets` 기반 잔액 계약(온체인 잔액=total/available, pending/frozen/locked null)을 고정했다. Admin 등록의 벤더 재해소를 도메인 포트 `ChainAssetResolver`로 분리해 Fireblocks(카탈로그 대조)·Dfns(설정·네트워크 행·주소 형식·키 길이) 관문을 조건부 조립하고, `NetworkWalletAssetPort`·Dfns 어댑터·`DfnsAccountService.balancesOf`(발급 네트워크마다 한 번 관찰, 미보유 0, drift 500)를 구현했다. OpenAPI 0.12.0(AssetBalance pending/locked nullable, fireblocksAssetId 선택, AssetMapping dfnsAssetKey). 검증은 [설계12](docs/design/12-provider-compatibility.md#dfns-데이터셋-자산-매핑-등록과-잔액-계약-검증-2026-09-15). DDL 변경·실벤더 호출·기동 차단 해제는 없다.
- [x] **DF3.12 Solana 자산 locator와 계정·자산 모델 컬럼** — V25(`bcm_blkc_m.chain_mdl_dvcd` EVM/SOLANA, `vndr_ast_id` 128자)와 도메인 `ChainModel`·`TokenStandard`를 추가하고, Dfns 등록 관문이 행의 모델로 분기해 Solana 네이티브/mint(base58 32바이트, 운영자 명시 SPL·SPL_2022)를 `<Network>:Spl|Spl2022:<mint>` 키로 해소한다. Solana 수신 주소는 지갑 owner 주소이며 ATA는 계산·저장하지 않고 `account-address-networks` 등록은 Baseline 수용 뒤 운영 결정이다(계약13). Admin 요청 `tokenStandard`(OpenAPI 0.12.1). 검증은 [설계12](docs/design/12-provider-compatibility.md#dfns-solana-자산-모델과-계정자산-모델-컬럼-검증-2026-09-16). 실벤더 호출·기동 차단 해제는 없다.
- [x] **DF3.13 Dfns 웹훅 수신 프로토콜** — 공식 가이드·명세 `WebhookEvent`를 근거로 `DfnsWebhookSignatureVerifier`(`sha256=<hex>` HMAC-SHA256을 **수신 바이트**로 검증, secret 목록 순서 대조, `timestampSent` 허용 오차 기본 300초, 상수 시간 비교)와 `DfnsWebhookProtocol`(`id`/`kind`만, `vendorTransactionId=null`)을 구현했다. `WebhookProtocol`은 모든 앱, HMAC 검증기는 `bcm.webhook.ingestion.enabled=true`인 Webhook 앱에서만 조립하고 secret 누락은 조립에서 실패한다. 가이드의 재직렬화 서명 예제와 원문 검증의 관계·kind별 `data` 형식은 수용 항목이다(계약13). 판단 워커·이력 복구·기동 차단 해제는 후속이다. 검증은 [설계12](docs/design/12-provider-compatibility.md#dfns-웹훅-수신-프로토콜-검증-2026-09-16).
- [x] **DF3.14 Dfns 전송 제출·조회 어댑터(내부 대역)** — 도메인 포트 `NetworkTransferPort`와 `DfnsNetworkTransferClient`를 추가했다. 등록 자산 키에서 `kind`·locator를 되돌려 `to`·`amount`(최소 단위)·`externalId`(≤50자)만 보내고, 지갑 경로 사용자 행위 서명을 거치며, 벤더 멱등 409는 조회 없이 `Conflict`(수신 바이트·duplicate ID)로 돌려준다. 응답은 지갑·network·자산 지정을 대조하고 `Confirmed`를 `FINALIZED`로 번역하지 않는다. 실행 빈 미등록·제출 원장 미연결이며 검증은 [설계12](docs/design/12-provider-compatibility.md#dfns-전송-제출조회-어댑터-검증-2026-09-16).
- [x] **DF3.15 Dfns 웹훅 전송 사건 관찰(내부 대역)** — 채택 명세 1.1018.3의 `webhooks` 항목(`wallet.transfer.*` 다섯)·`WebhookEnvelopeBase`·`TransferRequest`를 근거로 도메인 포트 `NetworkTransferEventParser`(`NetworkTransferEvent`·`NetworkTransferEventKind`)와 `DfnsNetworkTransferEventParser`를 구현했다. 수신 envelope와 조회 모델(`WebhookEvent`)이 다른 schema라는 점을 계약13에 적고 실제 Baseline 본문의 일치를 수용 항목으로 남겼다. `TransferRequest` 정규화를 `DfnsTransferRequests`로 분리해 조회 어댑터와 같은 검사를 쓰며, 업무 상태는 종류가 아니라 `status`에서 읽는다. 전송이 아닌 종류는 null, 전송 종류의 형식 오류·설정 밖 네트워크는 거절한다. `DfnsWebhookProtocol`은 전송 종류에서만 `vendorTransactionId`를 채우고 어긋나도 인박스 수용을 막지 않는다. 파서 빈 미등록·판단 워커 미조립이며 검증은 [설계12](docs/design/12-provider-compatibility.md#dfns-웹훅-전송-사건-관찰-검증-2026-09-16).
- [x] **DF3.16 Dfns 웹훅 온체인 이동 사건 관찰(내부 대역)** — 채택 명세 `webhooks`의 `wallet.blockchainevent.detected`·`wallet.blockchain_event.transfer.included`와 `WalletHistoryEvent`·`Wallet`을 근거로 도메인 포트 `NetworkChainEventParser`(`NetworkChainEvent`·`NetworkChainTransfer`·방향/상태)와 `DfnsNetworkChainEventParser`를 구현했다. 알림 메타는 전송 사건과 공통인 `VendorWebhookDelivery`로 뽑았다. 이동 종류를 목록으로 자산 kind에 대응시켜 등록과 같은 키 규칙을 쓰고, 모델 밖 종류는 키·금액 없이 원어만 남긴 채 사건을 보존한다. `data.wallet.id` 결속·설정 밖 네트워크·필수 필드 형식을 검사하며 `Confirmed`를 BCM 확정으로 번역하지 않고 `timestamp`는 원문 그대로 둔다. 빈 미등록·판단 워커 미조립이며 검증은 [설계12](docs/design/12-provider-compatibility.md#dfns-웹훅-온체인-이동-사건-관찰-검증-2026-09-16).
- [x] **DF3.17 Dfns 확정 판정의 블록 깊이 계약과 체인 head 어댑터(내부 대역)** — 사용자 확정(2026-09-16)에 따라 벤더의 `Confirmed`(reorg 가능)를 `FINALIZED` 근거로 쓰지 않고 사건 `blockNumber`와 체인 head의 깊이를 직접 계산한다. 도메인 `ChainHeadPort`·`BlockDepthFinality`(블록 자체가 1컨펌, head 미달은 0)와 `EvmChainHeadClient`(`eth_blockNumber`, 위탁 RPC)를 추가했고 임계는 기존 `bcm.finality-confirmations.<network>`를 그대로 쓴다. head 조회 실패는 확정 보류(예외 전파)다. 결정은 CLAUDE.md 3절·[02](docs/design/02-bcm-flow.md#dfns-경로의-확정-근거-2026-09-16-사용자-확정), 계약은 [계약13](docs/design/13-dfns-contracts.md#확정-판정--구현), 검증은 [설계12](docs/design/12-provider-compatibility.md#dfns-확정-판정의-블록-깊이-계약-검증-2026-09-16). 빈 미등록이며 Solana 확정 모델은 범위 밖이다.
- [ ] **다음 구현: 판단 워커의 Dfns 조립과 거래·Sweep·Admin** — `WebhookTransactionParser`·`VendorStatusTranslator`의 Dfns 구현과 입금 귀속(`bcm_addr_m` 대조)·논리 사건/outbox 연결, 제출 원장(`bcm_sbmt_l`)과 출금/Sweep 유스케이스, Admin 조회의 Dfns 구현을 계약13·02·06·08에서 확정한 뒤 구현한다. 완료 뒤 `BCM_PROVIDER=dfns` 기동 차단 해제는 Baseline 수용과 함께 사용자 결정이다.

DF8은 DF6 이후 진행하며 DF7 신규 기능 완료를 선행 조건으로 두지 않는다. 초기 세 실행 환경 호환·#51 신규 기능·전체 멀티체인 호환은 구분한다. 완료 기준은 상세 계획 5~7절을 따른다.
기존 85~135인일은 전체 Baseline 구축·3체인·Solana 집금/감사 반영 전 추정이다. DF0에서 초기/후속 및 담당별 분해, DF2 뒤 초기 실제 차이로 재산정한다.
공식 명세 확인 후 `ad25681` 기준 잔여 작업을 재산정했다. 초기 EVM 한 체인 가정으로 코드·계약/로컬 통합 시험 43~70인일,
실환경 수용·독립 리뷰 6~10인일과 약 20% 수정 여유를 포함한 BCM 측 계획 범위는 약 60~100인일이다.
항목별 가정·완료 조건·범위 제외·외부 대기는 [상세 계획의 잔여 공수](docs/dfns-compatibility-plan.md#공식-명세-확인-후-잔여-공수-추정-2026-09-14)를 따른다.
실벤더 호출은 별도 사용자 지시 후, 신규 의존성은 기존 검증/승인 절차 후, 운영 배포는 Phase 15 재개 후 수행한다.

## Phase 15 — Production 배포 준비 계획

Phase 14까지 통과한 기능·프로세스 경계를 실제 운영 환경에 옮기기 위한 **계획만** 수립한다. 사용자가 운영 논의를
재개할 때까지 Phase 전체를 보류하며 서버 설치·배포·DNS/방화벽 변경·실 Fireblocks 변경 호출을 실행하지 않는다.
현재 확정된 전제는 Linux 일반 서버, `systemd`, API/Webhook/BAT 각 1대의 초기 검증 구성뿐이다. Admin 배포 여부와
PostgreSQL·Kafka, ingress/TLS, Secret, 운영 일정은 미정이다.

### 계획 task

- [ ] **T15.0 배포 요구사항 결정표** — 운영 OS·프로세스 관리자·서비스별 인스턴스 수·네트워크 인/아웃바운드·DNS·TLS,
  외부 PostgreSQL·Kafka·secret 전달·배포 승인권자·RTO/RPO를 질문과 결정 로그로 확정한다.
  [운영 배포 결정표](docs/runbooks/production-deployment-plan.md)에 현재 답변을 기록했으며 나머지는 Phase 재개 시 확정한다.
- [ ] **T15.1 산출물·버전·공급망 계획** — API/Webhook/Admin/BAT 독립 BootJar, 설정 템플릿, checksum·SBOM,
  서명·보관·불변 release ID·rollback 단위를 정하고 local/test-support 모듈 미포함을 검증한다.
- [ ] **T15.2 DB·Kafka 배포 순서** — DBA SQL 적용 소유권·사전 백업·하위 호환성·rollback 한계, 4개 토픽의 생성·파티션·보관·ACL과
  서비스 기동 순서를 문서화한다.
- [ ] **T15.3 런타임 토폴로지·독립성** — 구성 요소별 port·health/readiness·graceful shutdown·수평 확장·singleton 작업·장애 영향을
  정하고 Admin·로컬 체인·Stub 제거 조합에서 production 모듈의 런타임 의존 0을 재검증한다.
- [ ] **T15.4 보안·시크릿·통신 경계** — Fireblocks API private key·JWKS·PUBLIC Webhook ingress, 아웃바운드 allowlist, Admin private
  listener·mTLS·5분 이하 JWT, 키 교체·폐기·마스킹·접근 감사 계획을 확정한다.
- [ ] **T15.5 관측·운영·복구 runbook** — component별 로그·메트릭·trace/request ID, Webhook/outbox·Kafka·DB·Fireblocks 경보, 재시작·재처리·
  중지·rollback·DB/Kafka 장애 훈련과 Admin 진단 동선을 하나의 runbook으로 묶는다.
- [ ] **T15.6 스테이징·실벤더 수용 게이트** — 자동 Stub/LOCAL regression, 실행별 승인 후 Fireblocks read-only·최소 변경 계약,
  웹훅 서명·중복·역순·재전송, 소액 포함 기능 점검과 수동 승인 증거를 분리한다.
- [ ] **T15.7 rollout·rollback 계획 converge** — 사전 조건·배포 순서·중단 기준·canary/점진 전환·롤백 발동·승인 증거를
  검토하고, design-sync→code-reviewer·운영/보안 사람 리뷰 후에만 배포 실행 Phase를 연다.

**계획 예상 공수**: 1명 4~7인일 + 인프라·보안·DB/Kafka 담당자 각 0.5~1인일 리뷰. 배포 자동화·실제 반영 공수는
T15.0 결정 후 별도 산정한다.

## 스펙-설계 불일치 · 미해결 (구현 전/중 해결)

해결된 항목은 [해결된 스펙·설계 항목](docs/history/resolved-design-items.md)에 보존한다.

| # | 내용 | 상태 |
|---|---|---|
| 3 | **relay 의 stuck 자동 처리 여부** — 자동이면 막힘 점검의 boost 트리거를 뺀다 | 🟡 공개 문서 기준 임시 해결 (2026-08-12) — 자동 boost는 Gas Station auto-fueling에만 명시되고, 일반 EVM은 stuck 알림+RBF API를 안내한다. 일반 gasless relay 자동 처리를 보장하지 않는 것으로 보고 트리거를 유지하되 담당자 확답 전까지 미해결 유지 |
| 4 | **귀속 불명 해소 절차** — 매핑 갱신 트리거·해소 후 이벤트 재흘림 | DAW-CORE 정합 후 확정 (02 미확정) — Phase 4 는 통지까지만 |
| 15 | **REJECTED 이벤트의 `evt_typ_dvcd` 미정** | 🟡 임시 해결 (2026-08-07) — 코어 회신 전까지 `TXRJ`를 `OutboxEventType` 한 곳에서 관리·발행. 회신 후 그 상수만 확정 또는 교체 |
| 21 | **입금 주소 memoTag 비영속** — 스펙 Address.memoTag(Tag/Memo 체인용)가 있으나 03 `bcm_addr_m` 에 태그 컬럼이 없어 발급 후 재조회에서 돌려줄 수 없다. EVM 한정이면 무해(항상 null) — Tag/Memo 자산 지원 시 03 개정 필요 | Tag/Memo 자산 채택 시 — 이 저장소의 설계(03)에서 확정 |
| 30 | **카탈로그 동기화 다중 인스턴스 실행 제어** — 현재 각 bcm-bat 인스턴스의 `@Scheduled`가 동시에 실행될 수 있다. 중복 실행을 허용할지, `bcm_job_m`/DB lock으로 단일 실행할지 배치 운영 규약 확정 필요 | bcm-bat 다중 인스턴스 배포 전 |
| 34 | **거래 목록 커서의 벤더 조합 동작 미실측** — ① 정렬 지정 시 next 커서가 오는가 ② next 가 `sourceType`/`sourceId` 필터를 보존하는가 ③ next 와 필터를 함께 보내도 되는가. 공식 API 에 파라미터는 있으나 **조합은 실측 없음**. **외부 계약은 벤더와 분리 완료** — 매니저 커서에 최초 필터·정렬과 `(createdAt, txId)` 위치를 담고, 벤더 커서는 한 HTTP 요청 안의 내부 페이징에만 쓴다. 마지막에도 nextCursor를 발급하며 동일 시각 거래·asc 증분 회귀 테스트가 있다. 내부 페이징은 커서마다 발신 vault 필터를 재전송하고 응답 vault가 다르면 전체 거절한다 | sandbox 실측 — 내부 페이징 조합 확인 |
| 35 | **제출 직후 조회·알림의 빈 필드** — 벤더 문서상 `sourceAddress`·`destinationAddress` 는 체인 등장 전 비어 있을 수 있다. 우리 PoC 는 입금 `CONFIRMING` 부터라 그 구간 미관측. **스펙은 nullable 로 열었다**(v0.6.0 — `Transfer.from`/`to`, `ChainEvent.to`). 근거: 열지 않으면 제출 응답을 못 받았을 때 쓰는 `transactionByExternalTxId` 가 바로 그 시점에 깨진다. ★ **`amountInfo.amount` 가 제출 직후에도 항상 있는지는 미확인** — 없으면 현재 파서가 필수로 읽어 그 알림이 격리된다 | Phase 5 E2E 실측 — 결과에 따라 파서·스펙 조정 |
| 36 | **내부이체(delta)의 대납 적용 여부** — 출금·sweep 은 대납 근거가 설계에 있으나(02 출금 시퀀스 · 06 수수료 표) INTERNAL 은 없다. **확인 전까지 켜지 않는다**(근거 없는 설정을 넣지 않는다 — 안 켜도 된다고 확인한 것은 아니다). 대납 없이 가면 출발 vault 에 native 가 있어야 하고, 없으면 `INSUFFICIENT_FUNDS_FOR_FEE` 로 실패한다 | Phase 5 내부이체 E2E 전 — 벤더·운영 확인 |
| 37 | **출금 요청 본문 크기 상한** — `note`와 구조가 아직 불투명한 `travelRule`에 스키마 상한이 없어 큰 JSON이 벤더 호출·claim 점유를 늘릴 수 있다. 구현이 임의로 필드 상한을 만들면 OpenAPI보다 좁아지므로, 전체 HTTP 본문 상한과 필드별 상한·초과 응답(400/413)을 스펙에서 먼저 확정해야 한다 | 실트래픽 연동 전 — 이 저장소의 설계/OpenAPI 결정 |
| 41 | **CVE-2026-53914 Kotlin 안전 GA 대기** — 취약점은 build cache metadata 역직렬화에 있고 runtime `kotlin-stdlib`·`kotlin-reflect`에는 해당 코드가 없지만 NVD의 광범위한 Kotlin CPE가 둘을 매칭한다. 수정 기준 2.4.20은 2026-08-17 현재 RC만 실재한다 | T9.6에서 Gradle build cache를 전역·CI 모두 비활성화하고 runtime purl+CVE만 2026-09-30까지 suppression. Kotlin 2.4.20 GA 실재·Boot 4.1 호환·전체 테스트 확인 후 업그레이드, suppression 제거, build cache 재활성화 |
| 42 | **CVE-2026-41115 Kafka ACL 문서 불일치** — Dependency-Check가 `kafka-clients` 4.2.1에 Medium 4.3으로 보고한다. Apache는 `CONSUMER_GROUP_DESCRIBE` 구현의 `DESCRIBE GROUP` 검사가 정확하고 4.0.0~4.3.0을 affected이자 fixed로 표기하며 기존 ACL 검토를 권고한다 | 게이트 기준 미만이라 숨기지 않고 보고서에 유지한다. 운영 broker 도입 전 consumer group ACL이 최소 권한인지 확인하고, NVD/Apache 메타데이터 정정 또는 실제 수정 버전이 나오면 재평가 |
| 43 | **Webhooks V2 구독 관리 API 실측·설계 근거** — 공식 reference에는 `GET/PATCH /v1/webhooks/{id}`, `enabled=true`, `DISABLED/ENABLED/SUSPENDED`가 있으나 저장소 규칙의 근거인 97·90에는 아직 없다 | JMX 복구 endpoint는 기본 비활성. sandbox 실측 또는 담당자 확답을 `docs/design/`의 97/90에 반영한 뒤 환경별로 활성화한다 |
| 46 | **밴드S cold→hot 정족수 정본 모순** — 06·08은 옴니버스 입금 확인 뒤 출금 풀 보충에 재개와 같은 강화 정족수를 요구하지만, 03은 모든 BAND_S를 `risk_dvcd='FUND'`·독립 승인자 1명으로 고정하고 V3 DB trigger도 이를 강제한다 | cold→hot 승인 실행 전 `docs/design/03-bcm-db.md`에서 위험코드·DB 제약·정족수 파생을 확정. 현재 구현은 정본을 추측해 바꾸지 않음 |
| 47 | **밴드S sweep 선행·풀별 최소잔액 증적 자리 미정** — hot→cold에서 고객 vault sweep FINALIZED 선행과 출금 풀 최소 운영잔액 보호가 필요하지만 현재 proposal은 기존 sweep 실행 ID·풀별 관찰/최소 잔액을 보관하지 않는다 | DAW-CORE 입력 payload 계약만으로 충분한지, BCM 원장 FK/증적 컬럼이 필요한지 03·06에서 확정 후 구현 |
| 49 | **고정 cold 목적지 변경의 보안 정족수 원장 부재** — 06·08은 목적지 변경에 서로 다른 승인자 2명+보안 승인자 1명과 TAP 재검증을 요구하지만 현재 `fixedColdAddresses`는 배포 설정이고 version/change request 대상이 아니다 | 실자금 실행 전 목적지 registry의 정책 version·변경 요청·TAP evidence DB/API 자리를 03·08에서 확정하고 배포 설정 직접 변경을 차단 |
| 50 | **cold→hot 입금 FINALIZED 증적 구조 미정** — `COLD_DEPOSIT` proposal item에는 외부 cold 발신 주소/tx hash가 없고 현재 event 기록은 구조화되지 않은 observation payload를 신뢰해 `FINALIZED`를 추가할 수 있다 | cold→hot 실행 전 고정 외부 cold 발신 주소·tx hash·독립 체인 재조회·FINALIZED 증적과 다음 item 개방 조건을 03·06·08에서 확정 |
| 51 | **설계자 원장 PDF와 BCM 흐름·DB 대조** — [원장_v0.1.2.pdf](docs/원장_v0.1.2.pdf)의 DAWBC 전체 및 회사·고객 주소별 온체인 잔고 관리는 BCM 범위로 사용자 확정(2026-09-08). all sync는 변경 예정 설명으로 제외. 소유·용도 식별 구조 필요도 확인됨 | 잔고 관찰·이동 기록·대사 DB/API, 직접 집금안을 적용할 등록 DB/API와 정산 TID↔BCTX 책임을 구체화한다. 설계자 의도는 받는주소→핫 출금 풀 직접 Sweep이다. AI의 앞선 분리 유지 확정 기록은 정정했다. 기존 계약은 리뷰만으로 변경하지 않는다 |

### 실행 환경 호환 미해결 — 초기 DF0~2 / 후속 MC0~2

| ID | 내용 | 담당·완료 조건 |
|---|---|---|
| BC-L | 로컬 호환 경로·벤더 모사와 실환경 증적 구분 | BCM: DF1에서 기존 Stub/Anvil 재사용·Dfns Stub 연결·공통 시나리오·실패/리셋 경계 확정. 별도 직접 RPC 어댑터 필요성 대조 |
| DF-A | Baseline 사내 지원·키트/릴리스·플랫폼 운영자와 위탁 RPC 호환 | Dfns·플랫폼·노드 업체: 내부 API부터 전송·인덱싱·과거 복구까지 인수 |
| DF-B | USDC/KRWK의 3체인별 실제 발행·주소·표준·정밀도 | 발행사·CORE·BCM: 초기 등록만 DF0, 후속 조합은 MC0에서 발행/증적·범위 확정 |
| DF-C | 체인별 확정 근거와 기존 업무 FINALIZED 매핑 | CORE·BCM: 초기에는 공통 인터페이스와 선택 체인 계약, 추가 체인은 MC0~1에서 확정 |
| DF-D | 독립 승인·최종 payload 결합·Solana 집금/철회/원자 결과 | 보안·Dfns·BCM: 초기 승인 통제는 DF2, Solana 집금/철회/원자 결과는 후속 MC0~1. Governance Engine만으로 충족 판정 금지 |
| DF-E | 대납 재원·외부업체 여부·실패/rent 비용·법정화폐 정산 | 운영·CORE/재무·BCM: 체인별 단일 실행 경로와 비용 증적/예약/대사 계약 확정 |
| DF-F | 실운영/주소 유지·규모/SLO·전체 일정 | 사용자·운영·BCM: 이전 적용 여부·성능/복구 목표 고정, DF2 후 플랫폼/노드/감사 포함 재산정 |

### 원장 PDF 1·2번 검토 — 책임과 주소 역할 (2026-09-08)

사용자 확정은 CLAUDE.md 3절에 기록했다. DAWBC 범위와 주소별 온체인 잔고 관리 주체는 더 이상 미확정이 아니다.
이하 구현 현황은 저장소 코드·DDL 정적 대조다. PDF p.4·8·11을 함께 읽었고, 후속 진행 요청에 따라 주소/vault 매핑과 잔고 저장 경계를 01·02·03·06·09에 반영했다. 물리 스키마·API와 잔고 처리 구현은 남아 있다.

- **의미**: 회사/고객은 관리 주소에 담긴 자산의 귀속 구분이고, 주소별 잔고는 `(네트워크, 자산, 주소)`에서 관찰한 수량이다. 고객 공동 보관 주소의 잔액이 고객 개인의 소유권 잔액은 아니다. 예를 들어 고객 받는주소의 100이 공동 보내는주소로 Sweep되면 BCM의 주소 잔고는 100→0 / 0→100으로 바뀌지만 그 자체로 CORE 고객 소유권 100이 달라지지 않는다.
- **계정유형과 별개**: `CUSTOMER/SYSTEM`은 CORE 참조 ID의 이름 공간이다. 회사 vault를 여러 SYSTEM ref로 만들 수 있으나 그 유형만으로 고객 공동 보관·회사 소유나 구체 용도를 식별하지 못한다.
- **앞선 리뷰 정정**: `06-sweep.md`의 '주소 3계층 ↔ 우리 구조'에는 이미 **고객 보내는주소 = 옴니버스 + 출금 풀**이 명시돼 있다. 이를 놓쳐 옴니버스 업무 분류 자체가 미정이라고 한 결론을 정정한다. PDF의 한 업무 분류가 물리 주소/vault 하나를 뜻하지는 않는다.

현행 분리 구조의 주소/vault 대응표는 [09 매핑 대조](docs/design/09-asset-map.md#주소와-vault-매핑)에서 관리한다. 회사 콜드까지 포함한 7개 행으로 정리했으며 CORE=DAW-CORE를 명시했다.

**주소별 온체인 잔고 구현 현황**

| 확인한 구조 | 구현 상태와 한계 |
|---|---|
| `bcm_acnt_m`·`bcm_addr_m` | 계정↔vendor vault, 계정·네트워크·토큰↔발급 주소 매핑. 소유·용도와 주소별 잔고 컬럼 없음 |
| `AccountService.balancesOf`·`FireblocksClient.balanceOf` | 발급 주소가 등록된 자산에 대해 vendor vault 잔액을 요청 시 조회. 주소 미등록 자산은 목록 제외. 자체 잔고 저장 없음 |
| `VaultAssetResponse`·`VendorBalance` | total/available/pending/frozen/lockedAmount만 전달. 잔고 기준 blockHeight/blockHash·관찰 시각 모델 없음 |
| `bcm_whk_l`·`bcm_raw_tx_l`·`bcm_tx_l`·`bcm_sbmt_l`·outbox | 원문·거래 상태·제출·이벤트 기록은 있음. 일반 주소별 잔고 projection과 같은 블록 기준 대사를 완성한 구조는 아님 |
| `bcm_swp_item_l` 등 Sweep 기록 | 주소·실제 이동량·log index 등 재사용할 증적 있음. 전체 관리 주소·자산을 포괄하는 이동 장부는 아님 |
| 밴드S snapshot·예약 및 Vault 전체 대사 | 특정 업무의 입력·예약, vault 등록 대조용. 일반 주소별 온체인 잔고 이력/대사를 대신하지 않음 |

**잔고 DB 설계 방향(03 반영, 물리 스키마 미구현)**: 주소 등록부(자산 귀속·업무 용도·물리 역할·vault/외부 주소 연결), 잔고 관찰 이력(네트워크·자산·주소·기준 블록 높이/해시·수량·관찰 시각·출처·확정 수준), 주소별 이동 증적 및 최신 조회 모델을 구분한다. 요청 중 예약과 온체인 관찰값도 분리한다. 중복·역순 이벤트·reorg·누락 복구를 고려하고, 관찰 잔고에 이미 포함된 이동을 다시 가산하지 않는다. EVM 이동은 tx hash만이 아니라 log index 등 실제 이동 식별자가 필요하며 native 수수료·토큰 종류별 관찰 방식을 따로 검토한다.
주소별 수량과 vendor vault 집계가 동일하다고 일괄 가정하지 않는다. 기준 블록이 없는 응답은 없는 사실을 남기고 체인 수준 검증 완료로 표시하지 않는다. 외부 콜드의 조회 경로도 필요하다.
PDF p.11의 온체인잔고·감지/확정 전 이동은 BCM 데이터로 다루되, 델타정산대기처럼 '업무상 언제 잔액으로 인정할지'의 값은 온체인 잔고를 덮어쓰지 않도록 CORE 책임과 구분하는 것을 제안한다. PDF와의 구체 필드 배치는 후속 대조 대상이다.

**집금·출금 물리 구성 비교 (검토 의견)**

| 대안 | 이점 | 비용·조건 |
|---|---|---|
| 옴니버스 + 복수 출금 vault 유지 | 고정 Sweep 목적지 유지, 출금 재고·제출 경로를 나눠 운영 가능 | 보충 이체 비용·지연, 보충/회수 정책과 예약 필요. 실제 권한·한도 분리가 있어야 자금 노출 분리 효과가 있음 |
| 집금·출금을 단일 vault에서 수행 | 중간 보충 이체 제거, 운영 경로 단순 | 출금 제출 경로 집중과 대기 거래 영향, 공동 잔액에 대한 동시 예약 필요 |
| 여러 vault가 각각 집금·출금 겸임 | 중간 보충 감소와 출금 분산 가능 | Sweep 목적지 배정, 편중 재분배, vault별 잔액 예약 필요. 현재 불변 목적지 컨트랙트와 등록/선정 구조 변경 범위가 큼 |

**후속 정정**: 사용자가 설계자의 의도를 고객 받는주소→핫 출금 풀 직접 Sweep이라고 설명했다. 앞서 AI가 분리 유지로 확정한 기록을 철회한다. 직접 집금은 유효한 구성이고 중간 집금 vault는 필수가 아니다. 기존 분리안과의 비교는 검토 이력으로 남긴다. 다중 겸임도 가능한 설계이며, 출금 분산만으로 물리 분리가 필수라고 단정하지 않는다. 예상 처리량·보충 비용·허용 지연 자료가 없으므로 성능 우위가 실측된 결론은 아니다.
현재 `BandSConfig`는 옴니버스가 출금 풀에도 포함되면 거절한다. Sweep은 별도 operator가 제출하고 목적지는 컨트랙트에 불변 고정된다(06, 로컬 검증용 `BcmSweep.sol`). 따라서 합치는 작업은 명칭 변경만으로 끝나지 않는다. Sweep 수신 자체가 목적지의 송신 nonce를 소비하는 것으로 설명하지 않는다.
BCM의 공통 주소 잔고 저장 구조는 어느 안에서도 필요하다. 소유(회사/고객), 업무 용도(받는/보내는/콜드 등), 물리 역할(집금/출금 등)을 분리해 정의하되 임의 역할 조합을 허용하는 API/DDL은 아직 확정하지 않았다.

참고 확인: [Fireblocks 잔고 검증 안내](https://developers.fireblocks.com/docs/validate-balances)는 잔고의 기준 블록 정보를 제공 가능한 경우에만 보장하며 일부 자산/상황에서 없을 수 있다고 명시한다. [Omnibus 구성 안내](https://developers.fireblocks.com/reference/create-omnibus-structure)는 집금 vault와 복수 출금 풀 구성을 설명한다. 이 자료는 설계 검토 근거이며, 우리 환경의 신규 벤더 필드·동작은 실측/담당자 확인 전 구현 계약으로 채택하지 않는다.
### 고객 받는주소에서 핫 출금 풀로 직접 Sweep — 후속 검토

설계자 의도: `고객 받는주소 → 고객 보내는주소(핫 출금 풀: 집금+출금) → 외부`, 별도 중간 집금 vault 없음. 실제 핫 vault 수는 처리량과 지연 조건에 맞춰 결정한다. omnibus는 공동 보관 의미로도 사용할 수 있어 중간 단계를 반드시 뜻하지 않는다.

- **평가**: 직접 집금안을 설계 검토의 기본안으로 삼는다. 보충을 위한 중간 전송 비용·대기와 상태 추적이 줄어든다. 여러 겸임 vault를 두면 출금 제출도 분산할 수 있다. 동시성 문제가 자동으로 해결되는 구조는 아니다.
- **풀 선정**: 동일 네트워크·자산에서 활성·출금 가능 여부, 확정 재고·예약·들어오는 Sweep 의도를 함께 보고 목적지를 선정한다. 동시 선택의 편중을 막고 실행 의도에 풀을 고정하며 응답 유실 재시도에서 바꾸지 않는다. 풀 간 재분배 필요가 완전히 없어지는 것은 아니다.
- **재고와 상태**: Sweep 미확정 금액을 확정 재고로 선반영하지 않는다. 온체인 관찰과 이동 증적을 중복 가산하지 않고 reorg·누락을 대사한다. 고객 출금·콜드 이동·회사 정산의 동일 주소 자산 예약을 원자적으로 관리한다. vendor 잠김과 BCM 예약도 같은 요청이면 이중 차감하지 않도록 연결해야 한다.
- **주소/소유권**: 입금주소와 핫 주소의 이동은 고객자산 내부 이동이다. Sweep으로 고객 소유권을 다시 증가시키지 않는다. 집금/출금 역할이 겹쳐도 주소 잔고는 한 번만 집계한다.
- **송신 경합**: EVM에서는 동일 발신 주소의 nonce 순서와 대기 거래를 관리해야 한다. 현재 approve+transferFrom은 별도 operator가 제출하므로 핫 주소의 Sweep 수신 자체를 핫 주소의 송신 nonce 경합으로 설명하지 않는다.
- **통제**: 고객 핫 주소에 모인 자금의 출금 한도·허용 경로·콜드 이동 조건을 정한다. 별도 집금 vault도 hot이면 분리만으로 콜드 보관 효과가 생기지는 않는다.
- **현재 코드와 차이**: `SweepProperties.omnibusAccountId`와 후보 선정은 단일 목적지 전제, `BandSConfig`는 옴니버스/출금 풀 겸임 거절, Sweep 컨트랙트는 destination 불변이다. 불변 목적지를 승인된 핫 주소로 지정하는 설계는 가능하지만 기존 배포 목적지를 런타임에 바꿀 수는 없다. 복수 풀은 풀별 고정 목적지 컨트랙트와 등록/활성 binding 구조 등을 함께 설계한다. 임의 목적지 입력으로 안전 경계를 풀지 않는다.

참고: [Fireblocks Object Model](https://developers.fireblocks.com/docs/object-model)은 omnibus를 하나 또는 여러 공동 vault로 설명한다. [출금 확장 안내](https://developers.fireblocks.com/docs/manage-withdrawals-at-scale)는 복수 출금 wallet의 분산과 각 wallet의 잔액·재충전 관리 필요를 설명한다. 특정 처리량이나 벤더 필드의 우리 환경 동작은 실측 결론이 아니다.
이번에는 설계자 의도와 앞선 확정 표현을 정정했다. 직접 집금 코드·DDL/API·정책 전환은 아직 수행하지 않았다.

후속 작업 순서:

- [x] DAW-CORE/BCM 책임과 온체인 잔고 논리 저장 경계를 설계 정본에 반영한다. 매핑의 분리 유지 확정 표현은 후속 설명에 따라 정정했다.
- [ ] 직접 집금 기준의 목적지 풀 선정·고정 컨트랙트 등록·밴드S/정산 경로를 확정하고 06·09의 현행 대조표와 그림을 전환한다.
- [ ] 주소 등록·소유/용도/물리 역할의 코드값, 기존 계정·외부 콜드와의 FK·유일성·변경 이력 및 등록 API를 구체화한다.
- [ ] 온체인 관찰·이동 증적·최신 조회 모델의 물리 스키마와 초기 잔고 적재·중복/역순/reorg·대사 및 예약 원자성을 설계한다.
- [ ] 정산 TID↔BCTX와 회사 고객거래주소↔고객 보내는주소 정산의 업무 인정·체인 상태를 대조한다.
- [ ] 확정한 DB/API 계약에 맞춰 코드와 의미 있는 동시성·복구 테스트를 구현한다.


## 범위 밖 (이 저장소가 아님) · 시점 미배정

- 컴플라이언스 게이트(cmpl_) · 정책 관리 · API Co-signer — 별도 구성 요소
- DAW-CORE 컨슈머 — 코어 쪽 구현
- **밴드S(핫↔콜드 균형, 06 ②)** — 판정(산식·환산 입력)은 코어/Admin 소관. 매니저 몫은 **실행부(지시 수신·전송 제출)뿐이며 이것도 시점 미배정** — 정책 자료·콜드월렛 결정(06 미확정) 대기. 침묵이 아니라 명시적 보류다
- 발신 IP allowlist(02 서명 검증 행) — 인프라 소관으로 추정, 소유 확인 필요
