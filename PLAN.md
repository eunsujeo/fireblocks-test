# blockchain-manager 구현 로드맵

> 설계 문서는 [docs/design/](docs/design/) 사본 (정본은 waas-wiki), HTTP API 계약은 [docs/api/openapi.yaml](docs/api/openapi.yaml) (그대로 구현).
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

**예상 공수**: 1명 10~16인일(설계·API/DB 3~4, 실행 전환 3~5, 완료 확인·Admin/관측 2~3, 시스템 테스트·converge 2~4).
실 Fireblocks mutation은 포함하지 않으며 별도 명시 승인 전까지 Stub+Anvil로 검증한다.

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
| 21 | **입금 주소 memoTag 비영속** — 스펙 Address.memoTag(Tag/Memo 체인용)가 있으나 03 `bcm_addr_m` 에 태그 컬럼이 없어 발급 후 재조회에서 돌려줄 수 없다. EVM 한정이면 무해(항상 null) — Tag/Memo 자산 지원 시 03 개정 필요 | Tag/Memo 자산 채택 시 — 설계(waas-wiki 03) 질의 |
| 22 | **GET 오퍼레이션의 400 이 스펙 응답 표면에 없음** (T2.5 design-sync) — 코드는 경로변수 maxLength 초과를 400 처리(파라미터 스키마는 스펙에 있음), `depositAddressOf`·`balanceOf` 응답 표면은 200·404 만 — 스펙 내부 비일관. 스펙에 400 추가 또는 GET 검증 제거 | 차기 스펙 개정 시 — 사용자 결정 |
| 23 | **balanceOf — "계정 있음·자산 지갑 미발급" 케이스 계약 미정의** (T2.5 design-sync, 중) — 벤더 4xx → VendorApiException → 500 으로 떨어짐. `depositAddressOf` 는 같은 구분을 `data: null` 로 명시하는데 balanceOf 는 침묵. DAW-CORE 가 주소 발급 전 잔액 조회 시 500 | 차기 스펙 개정 시 — DAW-CORE 정합 포함 사용자 결정 |
| 30 | **카탈로그 동기화 다중 인스턴스 실행 제어** — 현재 각 bcm-bat 인스턴스의 `@Scheduled`가 동시에 실행될 수 있다. 중복 실행을 허용할지, `bcm_job_m`/DB lock으로 단일 실행할지 배치 운영 규약 확정 필요 | bcm-bat 다중 인스턴스 배포 전 |
| 34 | **거래 목록 커서의 벤더 조합 동작 미실측** — ① 정렬 지정 시 next 커서가 오는가 ② next 가 `sourceType`/`sourceId` 필터를 보존하는가 ③ next 와 필터를 함께 보내도 되는가. 공식 API 에 파라미터는 있으나 **조합은 실측 없음**. **외부 계약은 벤더와 분리 완료** — 매니저 커서에 최초 필터·정렬과 `(createdAt, txId)` 위치를 담고, 벤더 커서는 한 HTTP 요청 안의 내부 페이징에만 쓴다. 마지막에도 nextCursor를 발급하며 동일 시각 거래·asc 증분 회귀 테스트가 있다. 내부 페이징은 커서마다 발신 vault 필터를 재전송하고 응답 vault가 다르면 전체 거절한다 | sandbox 실측 — 내부 페이징 조합 확인 |
| 35 | **제출 직후 조회·알림의 빈 필드** — 벤더 문서상 `sourceAddress`·`destinationAddress` 는 체인 등장 전 비어 있을 수 있다. 우리 PoC 는 입금 `CONFIRMING` 부터라 그 구간 미관측. **스펙은 nullable 로 열었다**(v0.6.0 — `Transfer.from`/`to`, `ChainEvent.to`). 근거: 열지 않으면 제출 응답을 못 받았을 때 쓰는 `transactionByExternalTxId` 가 바로 그 시점에 깨진다. ★ **`amountInfo.amount` 가 제출 직후에도 항상 있는지는 미확인** — 없으면 현재 파서가 필수로 읽어 그 알림이 격리된다 | Phase 5 E2E 실측 — 결과에 따라 파서·스펙 조정 |
| 36 | **내부이체(delta)의 대납 적용 여부** — 출금·sweep 은 대납 근거가 설계에 있으나(02 출금 시퀀스 · 06 수수료 표) INTERNAL 은 없다. **확인 전까지 켜지 않는다**(근거 없는 설정을 넣지 않는다 — 안 켜도 된다고 확인한 것은 아니다). 대납 없이 가면 출발 vault 에 native 가 있어야 하고, 없으면 `INSUFFICIENT_FUNDS_FOR_FEE` 로 실패한다 | Phase 5 내부이체 E2E 전 — 벤더·운영 확인 |
| 37 | **출금 요청 본문 크기 상한** — `note`와 구조가 아직 불투명한 `travelRule`에 스키마 상한이 없어 큰 JSON이 벤더 호출·claim 점유를 늘릴 수 있다. 구현이 임의로 필드 상한을 만들면 OpenAPI보다 좁아지므로, 전체 HTTP 본문 상한과 필드별 상한·초과 응답(400/413)을 스펙에서 먼저 확정해야 한다 | 실트래픽 연동 전 — waas-wiki/OpenAPI 결정 |
| 41 | **CVE-2026-53914 Kotlin 안전 GA 대기** — 취약점은 build cache metadata 역직렬화에 있고 runtime `kotlin-stdlib`·`kotlin-reflect`에는 해당 코드가 없지만 NVD의 광범위한 Kotlin CPE가 둘을 매칭한다. 수정 기준 2.4.20은 2026-08-17 현재 RC만 실재한다 | T9.6에서 Gradle build cache를 전역·CI 모두 비활성화하고 runtime purl+CVE만 2026-09-30까지 suppression. Kotlin 2.4.20 GA 실재·Boot 4.1 호환·전체 테스트 확인 후 업그레이드, suppression 제거, build cache 재활성화 |
| 42 | **CVE-2026-41115 Kafka ACL 문서 불일치** — Dependency-Check가 `kafka-clients` 4.2.1에 Medium 4.3으로 보고한다. Apache는 `CONSUMER_GROUP_DESCRIBE` 구현의 `DESCRIBE GROUP` 검사가 정확하고 4.0.0~4.3.0을 affected이자 fixed로 표기하며 기존 ACL 검토를 권고한다 | 게이트 기준 미만이라 숨기지 않고 보고서에 유지한다. 운영 broker 도입 전 consumer group ACL이 최소 권한인지 확인하고, NVD/Apache 메타데이터 정정 또는 실제 수정 버전이 나오면 재평가 |
| 43 | **Webhooks V2 구독 관리 API 실측·설계 근거** — 공식 reference에는 `GET/PATCH /v1/webhooks/{id}`, `enabled=true`, `DISABLED/ENABLED/SUSPENDED`가 있으나 저장소 규칙의 근거인 97·90에는 아직 없다 | JMX 복구 endpoint는 기본 비활성. sandbox 실측 또는 담당자 확답을 waas-wiki 97/90에 반영하고 사본을 동기화한 뒤 환경별로 활성화한다 |
| 46 | **밴드S cold→hot 정족수 정본 모순** — 06·08은 옴니버스 입금 확인 뒤 출금 풀 보충에 재개와 같은 강화 정족수를 요구하지만, 03은 모든 BAND_S를 `risk_dvcd='FUND'`·독립 승인자 1명으로 고정하고 V3 DB trigger도 이를 강제한다 | cold→hot 승인 실행 전 waas-wiki 03에서 위험코드·DB 제약·정족수 파생을 확정. 현재 구현은 정본을 추측해 바꾸지 않음 |
| 47 | **밴드S sweep 선행·풀별 최소잔액 증적 자리 미정** — hot→cold에서 고객 vault sweep FINALIZED 선행과 출금 풀 최소 운영잔액 보호가 필요하지만 현재 proposal은 기존 sweep 실행 ID·풀별 관찰/최소 잔액을 보관하지 않는다 | DAW-CORE 입력 payload 계약만으로 충분한지, BCM 원장 FK/증적 컬럼이 필요한지 03·06에서 확정 후 구현 |
| 49 | **고정 cold 목적지 변경의 보안 정족수 원장 부재** — 06·08은 목적지 변경에 서로 다른 승인자 2명+보안 승인자 1명과 TAP 재검증을 요구하지만 현재 `fixedColdAddresses`는 배포 설정이고 version/change request 대상이 아니다 | 실자금 실행 전 목적지 registry의 정책 version·변경 요청·TAP evidence DB/API 자리를 03·08에서 확정하고 배포 설정 직접 변경을 차단 |
| 50 | **cold→hot 입금 FINALIZED 증적 구조 미정** — `COLD_DEPOSIT` proposal item에는 외부 cold 발신 주소/tx hash가 없고 현재 event 기록은 구조화되지 않은 observation payload를 신뢰해 `FINALIZED`를 추가할 수 있다 | cold→hot 실행 전 고정 외부 cold 발신 주소·tx hash·독립 체인 재조회·FINALIZED 증적과 다음 item 개방 조건을 03·06·08에서 확정 |


## 범위 밖 (이 저장소가 아님) · 시점 미배정

- 컴플라이언스 게이트(cmpl_) · 정책 관리 · API Co-signer — 별도 구성 요소
- DAW-CORE 컨슈머 — 코어 쪽 구현
- **밴드S(핫↔콜드 균형, 06 ②)** — 판정(산식·환산 입력)은 코어/Admin 소관. 매니저 몫은 **실행부(지시 수신·전송 제출)뿐이며 이것도 시점 미배정** — 정책 자료·콜드월렛 결정(06 미확정) 대기. 침묵이 아니라 명시적 보류다
- 발신 IP allowlist(02 서명 검증 행) — 인프라 소관으로 추정, 소유 확인 필요
