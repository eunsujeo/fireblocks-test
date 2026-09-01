---
title: 블록체인 매니저 — DB
status: To Do
group: 블록체인 매니저
---

블록체인 매니저 DB(`bcm_`)의 테이블 전체 — 계정·주소 매핑, 거래 운영 상태, 수신 인박스, sweep 대상, 주기 작업, boost 이력, 수수료 견적, finalize 원본, 발행 아웃박스. 자산의 현재 매핑·변경 snapshot과 블록체인·자산 검색 카탈로그는 [자산 매핑](07-asset-master.md) 에서 정의한다.
회계 진실(고객 원장·귀속·잔액·출금 지시 상태)은 여기 없다 — 그것은 DAW-CORE DB(`daw_`)다.

## 명명 규약

코어 DB(`daw_`) 규약을 그대로 따른다 — BC·컴플라이언스·코어가 한 규약을 쓴다.

- **접두** `bcm_` · **접미** `_m`(마스터) · `_l`(내역/로그) · `_trgt`(작업 대상)
- **컬럼 축약** `_stcd`(상태코드) · `_dvcd`(구분코드) · `_yn`(VARCHAR(1) Y/N) · `_cnt`(횟수) · `_dttm`(일시) · `_dt`(일자) · `_id` · payload(JSONB — 단 수신 바이트를 그대로 보존하는 `bcm_whk_l`·`bcm_raw_tx_l` 은 TEXT)
- **일시는 VARCHAR(16)** — 코어와 동일(TIMESTAMP 안 씀) · 값은 UTC `yyyyMMddHHmmss` 14자 · **일자는 VARCHAR(8)** UTC `yyyyMMdd`
- **DB 일시·일자는 모두 UTC** (2026-08-14 확정) — `_dttm`, `_dt`, `base_dt`, 벤더 `createdAt` · epoch ms를 UTC 기준으로 저장한다. 포맷에 오프셋이 없으므로 DB 값은 항상 UTC로 해석한다. API는 ISO 8601 UTC(`Z`), 화면·정산·보고서는 필요한 시간대로 변환하여 산정한다
- **id 길이** — 벤더가 주는 값(벤더 tx·vault·알림 id)은 잘리지 않게 VARCHAR(64) 유지. 코어 자체 id 는 16~36
- **자산은 두 컬럼** — `ntwk_cd`(네트워크코드 20자) + `tkn_smbl`(토큰심볼 16자). 코어 마스터의 컬럼명·크기를 따르되 **값은 우리가 정한다** — 코어 키(`tkn_id` 등)에 의존하지 않는다
- **감사 4컬럼** — 모든 테이블에 `frst_reg_empno`(6)·`frst_reg_brcd`(4)·`last_chng_empno`(6)·`last_chng_brcd`(4). 자동 처리 행은 시스템 센티넬 `empno='SYSTEM'`·`brcd='9999'`(2026-08-05 확정 — 구현은 단일 상수), Admin 수동 개입(수동 boost·동결 해제 등)은 실제 직원/부점. 행 발생·변경 "시각"은 별도 도메인 `_dttm` 이 담당한다(코어와 동일 분리)

## 테이블 한눈에

| 테이블 | 무엇을 저장하나 | 쓰는 곳 |
|---|---|---|
| `bcm_acnt_crtn_l` | vault 생성 의도·현재 멱등 키 세대·회수 결과 | Fireblocks 호출 선기록 · 응답 유실/로컬 저장 실패 회수 |
| `bcm_acnt_m` | 계정 매핑 — (계정유형, ref) ↔ vault | 계정 생성 · 모든 오퍼레이션의 계정 해석 |
| `bcm_addr_crtn_l` | vault wallet·주소 생성 의도·assetId snapshot·회수 결과 | Fireblocks 호출 선기록 · 응답 유실/로컬 저장 실패 회수 |
| `bcm_addr_m` | 주소 매핑 — (계정, 네트워크, 토큰) ↔ 입금 주소 | 주소 발급·조회 · 입금 감지의 주소→계정 대응 |
| `bcm_whk_l` | 수신 웹훅 알림 원본 — 인박스 | 수신부 적재 → 판단 워커 집기 · finalize 원본의 출처 |
| `bcm_tx_l` | 거래 운영 상태 — 감지·발행 추적 | 판단 워커 → 발행 예약 · 막힘 점검 · 제출 중복 차단 |
| `bcm_outbox_l` | 발행 대기 이벤트 — 상태 변경과 원자 기록 | 워커가 같은 트랜잭션에 적재 → relay 가 발송 |
| `bcm_evnt_cmpl_l` | 소비자 처리 완료 확인 | DAW-CORE 업무 커밋 뒤 `eventId` 멱등 확인 |
| `bcm_sbmt_l` | 제출 원장 — 우리가 벤더에 낸 건 | 출금·내부이체 제출의 멱등 판정 · sweep 제출 · 웹훅 분류의 기준 |
| `bcm_swp_req_l` | DAW-CORE sweep 요청 | 외부 요청 멱등·상태·canonical hash |
| `bcm_swp_req_item_l` | sweep 요청의 고객 계정 항목 | 한 요청 안의 1..N 계정·처리 상태 |
| `bcm_swp_req_src_l` | 요청 항목의 근거 이벤트 | 완료된 DEPOSIT/FINALIZED `eventId` 소비 |
| `bcm_swp_trgt` | sweep 실행 후보 projection | 접수된 요청 항목을 주기 배치가 claim |
| `bcm_swp_auth_m` | 고객 vault별 sweep 승인 관찰 상태 | allowance 확인 · approve 준비 · 긴급 회수 추적 |
| `bcm_swp_exec_l` | sweep 실행 — 최상위 배치 1건 | 실행 의도 선기록 · batch 제출·종결 |
| `bcm_swp_item_l` | sweep 실행 항목 | 한 배치 아래 원천 vault N개의 요청·실제 결과 |
| `bcm_boost_l` | boost intent·이력 | 자동 boost의 선기록·장애 복구·Admin 조회 |
| `bcm_job_m` | 주기 작업 상태 — heartbeat · 대사 커서 | tx 대사 대조 범위 · 밖에서 읽는 heartbeat |
| `bcm_fee_qt_l` | 자산별 LOW·MEDIUM·HIGH 네트워크 수수료 견적 시계열 | 제출·boost 요청 시각의 최근 견적 대응 · sweep 가스비 조건 입력 |
| `bcm_raw_tx_l` | finalize 트랜잭션 원본 | 일 배치 보관 — 장기 보존 |
| `bcm_blkc_m` | 벤더 블록체인 카탈로그 — 일 1회 동기화 | 네트워크 채택 · 자산 등록 때 고르기 ([자산 매핑](07-asset-master.md)) |
| `bcm_vndr_ast_ctlg_m` | 모든 네트워크의 벤더 자산 카탈로그 캐시 — 일 1회 동기화 | Admin 심볼·표시명 후보 검색. 미지원 후보는 읽기 전용이며 등록 때는 Fireblocks 재검증 ([자산 매핑](07-asset-master.md)) |
| `bcm_vndr_ast_m` | 자산의 현재 매핑 — (네트워크, 토큰) ↔ 벤더 assetId · 활성 여부 | 활성 매핑 판정 · 벤더 호출 직전 변환 ([자산 매핑](07-asset-master.md)) |
| `bcm_vndr_ast_chng_l` | 자산 매핑 변경 원장 — 변경 전후 snapshot, 추가 전용 | 등록·해제·재활성·교체 감사와 장애 대조 ([자산 매핑](07-asset-master.md)) |
| `bcm_vlt_rcnc_l` | Vault 전체 대사 실행 원장 — 상태·vendor cursor·진행량 | Admin 비동기 대사 접수·재개·실패 범위 확인 ([Admin](08-bcm-admin.md)) |
| `bcm_vlt_rcnc_item_l` | Vault 대사 실행별 계정·vendor 결과 snapshot | 완료/부분 완료 결과의 고정 정렬·cursor 조회 ([Admin](08-bcm-admin.md)) |

## ERD

```erd
entity: bcm_acnt_crtn_l @1,1 :: vault 생성 의도 | acnt_id PK :: 선기록하는 계정 id | acnt_typ_dvcd UK :: ref와 함께 멱등 키 | ref UK :: 백엔드 참조 키 | idmp_key UK :: Fireblocks 40자 이하 현재 키 | idmp_key_reg_dttm :: 현재 키 세대 시작 | last_vndr_call_dttm :: 마지막 POST 준비 상한 | crtn_stcd :: PENDING/SUBMITTING/COMPLETED
entity: bcm_addr_m @1,1 :: 주소 매핑 — (계정, 네트워크, 토큰)당 입금 주소 하나 | acnt_id PK,FK :: 계정 | ntwk_cd PK :: 네트워크 코드 | tkn_smbl PK :: 토큰 심볼 | dpst_addr :: 발급된 입금 주소
entity: bcm_addr_crtn_l @1,1 :: vault wallet·주소 생성 의도 | acnt_id PK,FK :: 계정 | ntwk_cd PK :: 네트워크 | tkn_smbl PK :: 토큰 | vndr_ast_id :: 생성 시점 assetId snapshot | idmp_key UK :: Fireblocks 40자 이하 현재 키 | idmp_key_reg_dttm :: 현재 키 세대 시작 | last_vndr_call_dttm :: 마지막 POST 준비 상한 | crtn_stcd :: PENDING/SUBMITTING/COMPLETED
entity: bcm_whk_l @2,1 :: 수신 웹훅 알림 원본 — 인박스 (처리 후 N일 정리) | noti_id PK :: 웹훅 알림 id (벤더 UUID) — 중복 수신 방어 | vndr_tx_id :: 벤더 tx id — 이 알림이 가리키는 거래 | prcs_stcd :: 판단 처리 상태 P/S/F — F 는 격리 | vndr_cmpl_yn :: 성공 처리한 원문의 vendor COMPLETED 표식
entity: bcm_outbox_l @3,1 :: 발행 대기 이벤트 — 워커가 상태 변경과 한 트랜잭션에 적재 | evnt_id PK :: 이벤트 id (UUID v7) · 컨슈머 dedup 키 | evt_typ_dvcd :: 이벤트유형 TXCK/TXCF/TXFL | evnt_stcd :: 발행상태 P/D/F/S
entity: bcm_evnt_cmpl_l @4,1 :: 소비자 처리 완료 확인 | evnt_id PK,FK :: BCM 발행 이벤트 | cnsmr_dvcd PK :: DAW_CORE | cmpl_dttm :: 최초 완료 시각
entity: bcm_sbmt_l @4,1 :: 제출 원장 — 우리가 벤더에 낸 건 (출금·내부이체·sweep) | ext_tx_id PK :: 우리 요청 키 = 멱등 키 | req_hash :: 요청 내용 SHA-256 — 같은 키 다른 내용 판별 | tx_dvcd :: WITHDRAWAL/INTERNAL/SWEEP_APPROVE/SWEEP_BATCH | vndr_tx_id UK :: 벤더 응답·웹훅으로 채운다 (NULL=미확인)
entity: bcm_acnt_m @1,2 :: 계정 매핑 — ref ↔ vault | acnt_id PK :: 매니저가 발급하는 계정 매핑 id | acnt_typ_dvcd UK :: 계정유형 CU 고객 / SY 시스템 | ref UK :: 백엔드 참조 키 = 코어 계정 ID · 유형과 함께 유일 | vndr_vlt_id :: 벤더 vault id (백엔드 비노출)
entity: bcm_tx_l @2,2 :: 거래 운영 상태 — 감지·발행 추적 | vndr_tx_id PK :: 최초 벤더 tx id = 논리 거래 id | actv_tx_id UK :: 현재 물리 벤더 tx id | vndr_crt_dttm :: 벤더 createdAt — 대사 시간축 | last_pub_stcd :: 마지막으로 발행한 TxStatus
entity: bcm_boost_l @3,2 :: boost intent·이력 — 호출 전 선기록 | orig_tx_id PK :: root 논리 거래 id | try_seq PK :: 시도 순번 | ext_tx_id UK :: RBF 제출 멱등 키 | new_tx_id UK :: 대체 벤더 tx
entity: bcm_swp_req_l @1,3 :: DAW-CORE sweep 요청 | swp_req_id PK :: BCM id | ext_swp_req_id UK :: DAW 멱등 키 | req_hash :: canonical body hash | swp_req_stcd :: ACCEPTED/BLOCKED/PROCESSING/COMPLETED/PARTIAL/FAILED
entity: bcm_swp_req_item_l @2,3 :: sweep 요청 계정 항목 | swp_req_item_id PK :: BCM id | swp_req_id FK :: 요청 | acnt_id :: 고객 계정 | swp_req_item_stcd :: PENDING/PROCESSING/COMPLETED/FAILED
entity: bcm_swp_req_src_l @3,3 :: 요청 근거 입금 이벤트 | evnt_id PK,FK :: 완료된 DEPOSIT/FINALIZED 이벤트 | swp_req_item_id FK :: 이 이벤트를 소비한 요청 항목
entity: bcm_swp_trgt @4,3 :: sweep 실행 후보 projection | acnt_id PK,FK :: 고객 계정 | ntwk_cd PK :: 네트워크 코드 | tkn_smbl PK :: 토큰 심볼 | actv_swp_exec_id FK :: 현재 claim한 실행 (NULL=선정 가능)
entity: bcm_swp_auth_m @2,3 :: sweep 승인 관찰 상태 | acnt_id PK,FK :: 고객 계정 | ntwk_cd PK :: 네트워크 코드 | tkn_smbl PK :: 토큰 심볼 | swp_ctrt_addr PK :: 승인 대상 sweep 컨트랙트 | alwnc_cap :: 승인 상한 | obs_alwnc :: 마지막 온체인 관찰 allowance
entity: bcm_swp_exec_l @3,3 :: sweep 실행 — 최상위 batch tx | swp_exec_id PK :: 실행 id | ext_tx_id UK :: batch 제출 멱등 키 | vndr_tx_id UK :: Fireblocks 최상위 tx | swp_exec_stcd :: 실행 상태
entity: bcm_swp_item_l @4,3 :: sweep 실행 항목 | swp_exec_id PK,FK :: 실행 | item_seq PK :: 실행 안 순번 | acnt_id FK :: 원천 고객 계정 | req_amt :: 요청금액 | actl_amt :: 실제 이동금액 | swp_item_stcd :: 항목 상태
entity: bcm_raw_tx_l @2,4 :: finalize 원본 — 일 배치 장기 보관 | base_dt PK :: 적재 기준일 = 파티션 키 | vndr_tx_id PK :: 벤더 tx id | payload_hash :: 원문 SHA-256 — 무결성
entity: bcm_job_m @3,4 :: 주기 작업 상태 — heartbeat · 대사 커서 | job_nm PK :: 작업명 | last_scs_dttm :: 마지막 성공 — tx 대사의 안정화된 createdAt 창 끝
entity: bcm_fee_qt_l @4,4 :: 자산별 네트워크 수수료 견적 시계열 | ntwk_cd PK :: 네트워크 코드 | tkn_smbl PK :: 토큰 심볼 | obs_dttm PK :: 관측 시각 | fee_lvl PK :: LOW/MEDIUM/HIGH
entity: bcm_vndr_ast_chng_l @1,5 :: 자산 매핑 변경 원장 — 변경 전후 snapshot (추가 전용) | chng_id PK :: 변경 id | ntwk_cd FK :: 네트워크 코드 | tkn_smbl FK :: 토큰 심볼 | actn_dvcd :: REGISTER/DEACTIVATE/REACTIVATE/REPLACE
entity: bcm_vlt_rcnc_l @2,5 :: Vault 전체 대사 실행 원장 | vlt_rcnc_id PK :: 실행 id | vlt_rcnc_stcd :: ACCEPTED/RUNNING/COMPLETED/PARTIAL/FAILED | vndr_crsr :: 재개용 vendor cursor | vndr_done_yn :: 마지막 page 기록 여부 | vndr_page_cnt :: 완료 page 수
entity: bcm_vlt_rcnc_item_l @3,5 :: Vault 대사 결과 snapshot | vlt_rcnc_id PK,FK :: 실행 id | item_key PK :: 계정 또는 vendor 기준 키 | rcnc_stcd :: PENDING/MANAGED/UNMANAGED/MISSING_IN_FIREBLOCKS | item_seq :: 결과 cursor 순서
rel: bcm_acnt_m | bcm_addr_m | 계정당 주소 | one-many
rel: bcm_acnt_crtn_l | bcm_acnt_m | 의도 완료 시 같은 acnt_id | one-one | dashed
rel: bcm_acnt_m | bcm_addr_crtn_l | 계정당 주소 생성 의도 | one-many
rel: bcm_addr_crtn_l | bcm_addr_m | 의도 완료 시 같은 복합 키 | one-one | dashed
rel: bcm_acnt_m | bcm_tx_l | 계정 귀속 | one-many
rel: bcm_acnt_m | bcm_swp_trgt | sweep 대상 | one-many
rel: bcm_acnt_m | bcm_swp_auth_m | sweep 승인 | one-many
rel: bcm_swp_exec_l | bcm_swp_item_l | 실행 항목 | one-many
rel: bcm_acnt_m | bcm_swp_item_l | 원천 vault | one-many
rel: bcm_swp_exec_l | bcm_swp_trgt | 활성 claim | one-many
rel: bcm_whk_l | bcm_tx_l | 워커가 옮김 | one-many | dashed
rel: bcm_sbmt_l | bcm_tx_l | 제출한 건이 웹훅으로 돌아옴 | one-one | dashed
rel: bcm_tx_l | bcm_outbox_l | 같은 트랜잭션 발행 예약 | one-many
rel: bcm_outbox_l | bcm_evnt_cmpl_l | 소비 완료 | one-many
rel: bcm_swp_req_l | bcm_swp_req_item_l | 요청 항목 | one-many
rel: bcm_swp_req_item_l | bcm_swp_req_src_l | 근거 이벤트 | one-many
rel: bcm_outbox_l | bcm_swp_req_src_l | sweep 요청에 소비 | one-one
rel: bcm_tx_l | bcm_boost_l | root 거래의 boost 시도 | one-many
rel: bcm_tx_l | bcm_raw_tx_l | 확정 원본 | one-many | dashed
rel: bcm_vndr_ast_m | bcm_fee_qt_l | 관측 당시 자산 매핑 | one-many | dashed
rel: bcm_vndr_ast_m | bcm_vndr_ast_chng_l | 현재 매핑의 변경 snapshot | one-many
rel: bcm_fee_qt_l | bcm_sbmt_l | 제출 시각 직전 견적 대응 | one-many | dashed
rel: bcm_vlt_rcnc_l | bcm_vlt_rcnc_item_l | 대사 실행 결과 | one-many
rel: bcm_acnt_m | bcm_vlt_rcnc_item_l | 실행 시작 시점 계정 snapshot | one-many | dashed
```

실선 = FK 로 이어지는 관계, 점선 = 값으로 잇는 논리 관계(payload 이동·원본 보관 — DB 제약으로 묶지 않는다, 수명이 다르다). 배지 PK·UK·FK. `bcm_job_m` 은 다른 테이블과 관계가 없는 독립 작업 상태 테이블이다.

## 시나리오로 보는 테이블 흐름

한 건이 어느 테이블을 언제 건드리는지 — 단계를 넘겨 보라. 초록 행 = 그 단계에 새로 들어온 행, 노랑 칸 = 바뀐 값, 취소선 = 삭제. 상단 테두리가 켜진 것이 그 단계에 건드려지는 것이고, **청록 = DB 테이블 · 노랑 = 메시지 큐**다.

### 입금

```anim
db
table: bcm_whk_l | noti_id | vndr_tx_id | prcs_stcd
table: bcm_tx_l | vndr_tx_id | last_pub_stcd | cnfm_cnt
table: bcm_outbox_l | evnt_id | evt_typ_dvcd | evnt_stcd
queue: deposit-events | 이벤트 | txId
table: bcm_evnt_cmpl_l | evnt_id | cnsmr_dvcd | cmpl_dttm
table: bcm_swp_req_l | swp_req_id | ext_swp_req_id | swp_req_stcd
table: bcm_swp_req_item_l | swp_req_item_id | acnt_id | swp_req_item_stcd
table: bcm_swp_req_src_l | evnt_id | swp_req_item_id
table: bcm_swp_trgt | acnt_id | ntwk_cd | tkn_smbl | actv_swp_exec_id
table: bcm_swp_auth_m | acnt_id | alwnc_cap | obs_alwnc | auth_stcd
table: bcm_swp_exec_l | swp_exec_id | ext_tx_id | swp_exec_stcd
table: bcm_swp_item_l | swp_exec_id | item_seq | acnt_id | swp_item_stcd
step: 웹훅 도착 (CONFIRMING) | 수신부가 알림 원본만 적재하고 200 을 돌려준다 — 판단은 아직
ins: bcm_whk_l | n-8f3a | tx-91c | N
step: 워커 — 한 트랜잭션 | 판단 워커가 알림을 집어 tx 행 생성 + outbox 에 감지 이벤트 적재(P) + 알림 처리 완료 — 한 커밋
ins: bcm_tx_l | tx-91c | CONFIRMED | 1
ins: bcm_outbox_l | ev-01 | TXCK | P
upd: bcm_whk_l | 1 | prcs_stcd=S
step: relay 발행 — 감지 | relay 가 미발송(P)을 큐로 보내고 S 표시 — 컨슈머는 evnt_id 로 중복을 접는다
ins: deposit-events | 입금 감지 | tx-91c
upd: bcm_outbox_l | 1 | evnt_stcd=S
step: 컨펌 누적 | 다음 알림마다 tx 행의 컨펌 수만 오른다 — 전이가 아니라 outbox 적재 없음 (기록만)
ins: bcm_whk_l | n-b2e | tx-91c | Y
upd: bcm_tx_l | 1 | cnfm_cnt=8
step: 벤더 COMPLETED 웹훅 — 한 트랜잭션 | 임계 도달 — tx 행을 확정으로 갱신하고 outbox 에 확정 이벤트 적재(P)
ins: bcm_whk_l | n-c7d | tx-91c | Y
upd: bcm_tx_l | 1 | last_pub_stcd=FINALIZED | cnfm_cnt=12
ins: bcm_outbox_l | ev-02 | TXCF | P
step: relay 발행 — 확정 | 확정 이벤트가 큐로 나간다
ins: deposit-events | 입금 확정 | tx-91c
upd: bcm_outbox_l | 2 | evnt_stcd=S
step: DAW-CORE 업무 완료 | DAW-CORE가 eventId로 입금 업무를 멱등 반영한 뒤 완료 확인한다
ins: bcm_evnt_cmpl_l | ev-02 | DAW_CORE | 2026082709300000
step: DAW-CORE sweep batch 요청 | 완료된 FINALIZED eventId와 계정으로 요청 원장·항목·근거를 함께 기록하고 실행 후보를 만든다
ins: bcm_swp_req_l | swr-01 | DAW-SWEEP-01 | ACCEPTED
ins: bcm_swp_req_item_l | swi-01 | acct_01H8X | PENDING
ins: bcm_swp_req_src_l | ev-02 | swi-01
ins: bcm_swp_trgt | acct_01H8X | ETHEREUM | USDC | 
step: allowance 준비 | 온체인 allowance 가 부족하면 approve를 제출하고, 확정 뒤 다시 조회한 관찰값을 갱신한다
ins: bcm_swp_auth_m | acct_01H8X | 1000 | 1000 | ACTIVE
step: 주기 배치 — 실행·항목 선기록 | 같은 네트워크·토큰의 대상을 묶어 실행 1건과 항목 N건을 먼저 기록하고 대상을 claim한다
ins: bcm_swp_exec_l | swx-01 | swp-01 | READY
ins: bcm_swp_item_l | swx-01 | 1 | acct_01H8X | READY
upd: bcm_swp_trgt | 1 | actv_swp_exec_id=swx-01
step: 배치 제출 | 운영 계정의 batchSweep 1건을 제출하고 실행 상태를 갱신한다
upd: bcm_swp_exec_l | 1 | swp_exec_stcd=SUBMITTED
step: 항목별 확정 · 대상 정리 | network records와 receipt 이벤트를 요청 항목과 대조한다 — 성공 후 잔액이 비었으면 대상 삭제, 실패·잔액 잔존이면 claim 해제
upd: bcm_swp_item_l | 1 | swp_item_stcd=SUCCEEDED
del: bcm_swp_trgt | 1
```

위 입금 그림은 outbox·relay 단계까지 그대로 그렸다. 아래 출금·boost·대사 그림은 발행을 효과만 축약한다 — 실제로는 같은 outbox 경로(워커 한 트랜잭션 → relay 발행)를 지난다(크래시 세이프 상세는 [감지 상세](99-detection-detail.md)). 또 `bcm_whk_l` 은 처리 후 N일 뒤 정리되고 확정 원본은 `bcm_raw_tx_l` 로 옮겨지지만, 입금 그림은 감지~sweep 경로만 보여주려고 그 단계는 생략했다.

### 출금

이벤트에는 벤더 tx id(`txId`)가 늘 실리고, 출금은 여기에 `externalTxId`(백엔드 요청 키)가 더해져 상태 전이 내내 그대로 따라간다 — DAW-CORE 가 자기 출금 지시와 대응한다. (입금은 외부 요청 키가 없어 `txId` 만.)

```anim
db
table: bcm_tx_l | vndr_tx_id | ext_tx_id | last_pub_stcd
queue: withdrawal-events | 이벤트 | txId | externalTxId
step: 제출 접수 | DAW-CORE 가 externalTxId 로 제출 — 매니저가 기록을 등록하고 SUBMITTED 를 발행한다
ins: bcm_tx_l | tx-w1 | wd-42 | SUBMITTED
ins: withdrawal-events | SUBMITTED | tx-w1 | wd-42
step: 전파 — CONFIRMED | 체인에 올라 컨펌이 쌓인다
upd: bcm_tx_l | 1 | last_pub_stcd=CONFIRMED
ins: withdrawal-events | CONFIRMED | tx-w1 | wd-42
step: 확정 — FINALIZED | 임계 도달 — 확정을 발행한다. externalTxId 로 백엔드가 출금 건을 닫는다
upd: bcm_tx_l | 1 | last_pub_stcd=FINALIZED
ins: withdrawal-events | FINALIZED | tx-w1 | wd-42
```

### 막힘 → 자동 boost

오래 미확정인 출금은 벤더 단건 조회로 `CONFIRMING`·tx hash 있음·0 confirmation을 재확인한 뒤 fee를 올린 대체 거래로 재전송한다(RBF). `bcm_tx_l`은 최초 tx 한 행을 논리 거래로 유지하고, 물리 대체 거래와 호출 intent는 `bcm_boost_l`에 남긴다.

```anim
db
table: bcm_tx_l | vndr_tx_id | actv_tx_id | tx_hash | last_pub_stcd
table: bcm_boost_l | orig_tx_id | try_seq | bst_stcd | ext_tx_id | new_tx_id
queue: withdrawal-events | 이벤트 | txId | externalTxId
step: 출금 체인 등장 | 출금 tx가 mempool에 등장해 CONFIRMING·0 confirmation·txHash 상태다
ins: bcm_tx_l | tx-w1 | tx-w1 | 0xold | CONFIRMED
step: 막힘 후보 재검증 | 주기 작업이 후보를 고르고 벤더 단건 조회로 RBF 가능 상태를 다시 확인한다
step: boost intent 선기록 | bst- UUID v7 externalTxId와 교체 대상 tx/hash를 먼저 커밋한다 — 호출 전에 죽어도 회수할 근거가 남는다
ins: bcm_boost_l | tx-w1 | 1 | REQUESTED | bst-01 |
step: boost 제출·마감 | replaceTxByHash=0xold로 대체 거래를 제출하고 tx-w2를 기록한다
upd: bcm_boost_l | 1 | bst_stcd=SUBMITTED, new_tx_id=tx-w2
upd: bcm_tx_l | 1 | actv_tx_id=tx-w2, tx_hash=NULL
step: 확정 — root 원 tx 로 접어 발행 | 대체 거래가 채굴·확정 — root 행의 hash·상태를 갱신하고 백엔드에는 tx-w1 기준으로 발행한다
upd: bcm_tx_l | 1 | tx_hash=0xnew, last_pub_stcd=FINALIZED
ins: withdrawal-events | 확정 | tx-w1 | wd-42
```

### 웹훅 유실 → tx 대사 복구

확정 웹훅을 놓쳐 tx 가 CONFIRMED 에 멈춰도, 10분 주기 tx 대사가 벤더 목록의 **종결된 건**과 대조해 복구한다(진행 중은 웹훅 몫). 실행 시각에서 안정화 지연을 뺀 벤더 createdAt 창을 만들고, `bcm_job_m.last_scs_dttm`이 마지막으로 끝낸 창의 끝을 이어 붙인다. 양쪽 모두 `vndr_crt_dttm`의 같은 시간축으로 비교한다.

```anim
db
table: bcm_tx_l | vndr_tx_id | vndr_crt_dttm | last_pub_stcd
table: bcm_job_m | job_nm | last_scs_dttm
queue: deposit-events | 이벤트 | txId
step: 확정 웹훅 유실 | 확정 알림이 오지 않아 tx 가 CONFIRMED 에 멈춰 있다
ins: bcm_tx_l | tx-91c | 11:52 | CONFIRMED
ins: bcm_job_m | tx-recon | 11:50
step: tx 대사 실행 | 12:05 실행이면 안정화된 11:50~12:00 createdAt 구간을 양쪽에서 대조 — tx 가 실제 COMPLETED 임을 발견
step: 복구 — 확정 발행 | 놓친 확정을 deposit-events 에 발행하고 tx 행을 갱신한다
upd: bcm_tx_l | 1 | last_pub_stcd=FINALIZED
ins: deposit-events | 입금 확정 | tx-91c
step: 커서 전진 | 대사가 last_scs_dttm 을 안정화된 createdAt 창 끝으로 전진 — 실제 실행 시각 12:05와 구분한다
upd: bcm_job_m | 1 | last_scs_dttm=12:00
```

## 테이블 상세

모든 테이블은 코어 규약의 감사 4컬럼(`frst_reg_empno`·`frst_reg_brcd`·`last_chng_empno`·`last_chng_brcd`)을 끝에 둔다 — 아래 스키마에서는 반복을 줄여 **감사 4컬럼**으로 줄여 적고, 자동 처리 행은 시스템 센티넬로 채운다.

### bcm_acnt_crtn_l — vault 생성 의도·회수 원장

Fireblocks를 부르기 전에 `(accountType, ref)`당 한 행을 먼저 남긴다. `acnt_id`·`vndr_vlt_nm`은 최초 접수 때 고정한다.
Fireblocks `Idempotency-Key`는 최대 40자이므로 현재 키도 40자 이하로 보관하고, 키 세대 시작과 마지막 POST 준비 시각을 함께 남긴다.
준비 시각은 POST 직전 DB에 먼저 커밋하므로 실제 호출 증거가 아니라 “이 시각 이후 POST가 발생했을 수 있다”는 보수적 상한이다.

```sql
CREATE TABLE bcm_acnt_crtn_l (
  acnt_id         VARCHAR(64)  PRIMARY KEY,
  acnt_typ_dvcd   VARCHAR(2)   NOT NULL,
  ref             VARCHAR(64)  NOT NULL,
  vndr_vlt_nm     VARCHAR(128) NOT NULL,
  idmp_key            VARCHAR(40)  NOT NULL UNIQUE,
  idmp_key_reg_dttm   VARCHAR(16)  NOT NULL,
  last_vndr_call_dttm VARCHAR(16)  NULL,
  crtn_stcd           VARCHAR(16)  NOT NULL, -- PENDING/SUBMITTING/COMPLETED
  try_cnt             INT          NOT NULL,
  vndr_vlt_id         VARCHAR(64)  NULL,
  reg_dttm            VARCHAR(16)  NOT NULL,
  last_chng_dttm      VARCHAR(16)  NOT NULL,
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)   NOT NULL,
  frst_reg_brcd   VARCHAR(4)   NOT NULL,
  last_chng_empno VARCHAR(6)   NOT NULL,
  last_chng_brcd  VARCHAR(4)   NOT NULL,
  UNIQUE (acnt_typ_dvcd, ref),
  CHECK (crtn_stcd IN ('PENDING', 'SUBMITTING', 'COMPLETED')),
  CHECK (try_cnt >= 0),
  CHECK (last_vndr_call_dttm IS NULL OR last_vndr_call_dttm >= idmp_key_reg_dttm),
  CHECK ((crtn_stcd = 'COMPLETED') = (vndr_vlt_id IS NOT NULL))
);
```

`try_cnt=0`은 아직 벤더에 제출하지 않은 의도다. 실제 호출 직전 `SUBMITTING`으로 바꾸고 횟수를 늘린다. 재시도가
`try_cnt>1`이면 먼저 `vndr_vlt_nm`의 exact match를 끝 cursor까지 조회한다. 정확히 하나만 있으면 그 `vndr_vlt_id`로
완료한다. 후보가 없고 현재 키의 남은 24시간 창이 설정된 벤더 최장 호출시간 전체를 수용할 때만 같은 `idmp_key`를 쓴다.
호출 도중 만료될 수 있으면 현재 키를 재사용하지 않는다. `last_vndr_call_dttm` 뒤
**설정된 벤더 최장 호출시간 + 24시간**이 지나기 전에는 새 키 POST를 보류하고 계정 `503 CREATION_RETRY_LATER`·`Retry-After`로
재시도 시점을 알린다. 벤더 최장 호출시간은 5분 이하여야 하고 초 단위 준비 시각에는 1초 정밀도 여유를 더한다. 이 안전시각이
지난 때만 새 키로 회전한다. 최신 `try_cnt`만 키 세대와
`last_vndr_call_dttm`을 CAS 갱신할 수 있어 만료 경계의 옛 시도가 새 POST를 만들지 못한다. 둘 이상이면 후보를 추측하지 않고 충돌로 종료한다.
`bcm_acnt_m` insert와 이 행의 `COMPLETED`·`vndr_vlt_id` 갱신은 한 트랜잭션이다. 완료 트랜잭션은 원장 행을 잠근 뒤 호출에 사용한
`idmp_key`·`idmp_key_reg_dttm`이 현재 세대와 같은지 다시 검사한다. 옛 세대의 늦은 응답은 공개 매핑을 만들지 못하고 재시도로 돌린다.
완료된 의도도 물리 삭제하지 않는다.

### bcm_acnt_m — 계정 매핑

(계정유형, ref) 당 vault 하나. 이 **복합 UNIQUE** 가 계정 생성 멱등의 최종 방어다 — 경합해도 이긴 값을 반환한다.

```sql
CREATE TABLE bcm_acnt_m (
  acnt_id       VARCHAR(64)  PRIMARY KEY,     -- 매니저가 발급하는 계정 매핑 id — 백엔드가 이후 모든 호출에 쓴다
  acnt_typ_dvcd VARCHAR(2)   NOT NULL,        -- 계정유형 CU:고객 / SY:시스템(운영) — ref 가 어느 ID 공간의 값인지 가린다
  ref           VARCHAR(64)  NOT NULL,        -- 백엔드 참조 키 = DAW-CORE 계정 ID 그대로 (접두사 없음)
  vndr_vlt_id   VARCHAR(64)  NOT NULL,        -- 벤더 vault id — 백엔드에 노출하지 않는다
  reg_dttm      VARCHAR(16)  NOT NULL,        -- 생성 일시
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  UNIQUE (acnt_typ_dvcd, ref)                 -- 멱등의 물리 근거 — ref 단독으로는 유일하지 않다
);
```

| 컬럼 | 자료형 | 역할 |
|---|---|---|
| `acnt_typ_dvcd` | VARCHAR(2) | 계정유형 — `CU`(고객) · `SY`(시스템·운영). API 는 읽기 쉬운 `CUSTOMER`·`SYSTEM` 으로 받고 매니저가 이 코드로 번역해 저장한다 |
| `ref` | VARCHAR(64) | DAW-CORE 계정 ID 를 그대로 담는다. 고객은 `daw_acnt_m.acnt_id`, 시스템은 `daw_sys_acnt_m.sys_acnt_id` |

**왜 유형을 따로 받나 (2026-08-05 확정)** — 고객 계정과 시스템 계정은 DAW-CORE 의 **서로 다른 테이블**이 발급하고 **접두사를 붙이지 않는다.** 그래서 두 ID 의 값이 겹칠 수 있고, `ref` 만으로는 어느 쪽 계정인지 가릴 수 없다. 유형을 함께 받아 `(acnt_typ_dvcd, ref)` 로 유일성을 잡는다. `ref` 자체는 불투명 문자열로만 다루고 내용을 파싱해 분기하지 않는다.

### bcm_vlt_rcnc_l · bcm_vlt_rcnc_item_l — Vault 전체 대사 실행

Fireblocks workspace 전체 조회를 HTTP 요청 수명과 분리하는 읽기 전용 Admin 실행 원장이다. 실행 행은 현재 vendor cursor와 완료한 page/vault
수를 저장하고, 항목 행은 실행 시작 시점의 BCM 계정 snapshot과 page마다 확인한 vendor vault를 합친다. vendor page 기록과 다음 cursor
갱신은 한 트랜잭션이며 같은 page를 다시 읽어도 항목 key upsert로 중복되지 않는다. 활성 실행은 하나만 허용한다. 실행기는 만료 시각이
있는 worker claim을 원자 획득·갱신하며, 현재 claim 소유자만 page 기록·완료·실패 종결을 수행한다. 만료 claim은 다른 인스턴스가 인계한다.

```sql
CREATE TABLE bcm_vlt_rcnc_l (
  vlt_rcnc_id      VARCHAR(36)  PRIMARY KEY,
  qry_vl           VARCHAR(128) NULL,
  vlt_rcnc_stcd    VARCHAR(16)  NOT NULL, -- ACCEPTED/RUNNING/COMPLETED/PARTIAL/FAILED
  vndr_crsr        TEXT         NULL,     -- 재개 전용, API 비노출
  vndr_done_yn     VARCHAR(1)   NOT NULL, -- 마지막 page 기록 여부, cursor NULL과 최초 상태를 구분
  wrkr_clm_id      VARCHAR(36)  NULL,     -- 현재 실행기 소유권 token, API 비노출
  clm_expires_dttm VARCHAR(16)  NULL,     -- claim 만료 UTC 일시
  vndr_page_cnt    INT          NOT NULL,
  vndr_vlt_cnt     BIGINT       NOT NULL,
  rslt_cnt         BIGINT       NOT NULL,
  fail_cd          VARCHAR(64)  NULL,     -- 안전한 분류 코드만 저장
  strt_dttm        VARCHAR(16)  NULL,
  fnsh_dttm        VARCHAR(16)  NULL,
  reg_dttm         VARCHAR(16)  NOT NULL,
  last_chng_dttm   VARCHAR(16)  NOT NULL,
  -- 감사 4컬럼
  frst_reg_empno   VARCHAR(6)   NOT NULL,
  frst_reg_brcd    VARCHAR(4)   NOT NULL,
  last_chng_empno  VARCHAR(6)   NOT NULL,
  last_chng_brcd   VARCHAR(4)   NOT NULL
);
CREATE UNIQUE INDEX ux_bcm_vlt_rcnc_active ON bcm_vlt_rcnc_l ((1))
  WHERE vlt_rcnc_stcd IN ('ACCEPTED', 'RUNNING');

CREATE TABLE bcm_vlt_rcnc_item_l (
  vlt_rcnc_id      VARCHAR(36)  NOT NULL,
  item_key         VARCHAR(129) NOT NULL,
  item_seq         BIGINT       NULL,
  rcnc_stcd        VARCHAR(32)  NOT NULL, -- PENDING/MANAGED/UNMANAGED/MISSING_IN_FIREBLOCKS
  acnt_id          VARCHAR(64)  NULL,
  acnt_typ_dvcd    VARCHAR(2)   NULL,
  ref              VARCHAR(64)  NULL,
  vndr_vlt_id      VARCHAR(64)  NOT NULL,
  vndr_vlt_nm      TEXT         NULL,
  wllt_cnt         INT          NULL,
  acnt_reg_dttm    VARCHAR(16)  NULL,
  reg_dttm         VARCHAR(16)  NOT NULL,
  last_chng_dttm   VARCHAR(16)  NOT NULL,
  -- 감사 4컬럼
  frst_reg_empno   VARCHAR(6)   NOT NULL,
  frst_reg_brcd    VARCHAR(4)   NOT NULL,
  last_chng_empno  VARCHAR(6)   NOT NULL,
  last_chng_brcd   VARCHAR(4)   NOT NULL,
  PRIMARY KEY (vlt_rcnc_id, item_key),
  FOREIGN KEY (vlt_rcnc_id) REFERENCES bcm_vlt_rcnc_l (vlt_rcnc_id)
);
CREATE UNIQUE INDEX ux_bcm_vlt_rcnc_item_seq ON bcm_vlt_rcnc_item_l (vlt_rcnc_id, item_seq)
  WHERE item_seq IS NOT NULL;
CREATE INDEX idx_bcm_vlt_rcnc_item_vault ON bcm_vlt_rcnc_item_l (vlt_rcnc_id, vndr_vlt_id);
```

실행 시작 때 계정은 `PENDING`으로 snapshot한다. Fireblocks page에서 확인한 계정만 `MANAGED`로 바꾸고 vendor에만 있는 vault는
`UNMANAGED`로 추가한다. 마지막 cursor까지 완주한 뒤에만 남은 `PENDING`을 `MISSING_IN_FIREBLOCKS`로 바꾼다. 실패 시 `PENDING`은
응답 대상이 아니며 확인이 끝난 항목에만 고정 `item_seq`를 부여한다. 따라서 중간 실패가 아직 읽지 않은 vault를 누락으로 오판하지 않는다.

### bcm_addr_crtn_l — vault wallet·주소 생성 의도·회수 원장

`(accountId, network, symbol)`당 한 행을 Fireblocks 호출 전에 남긴다. 접수 시점의 벤더 `assetId`를 snapshot으로 고정해,
응답 유실 뒤 자산 매핑이 바뀌어도 이미 시작한 생성의 대상을 바꾸지 않는다.

```sql
CREATE TABLE bcm_addr_crtn_l (
  acnt_id         VARCHAR(64)  NOT NULL,
  ntwk_cd         VARCHAR(20)  NOT NULL,
  tkn_smbl        VARCHAR(16)  NOT NULL,
  vndr_ast_id     VARCHAR(64)  NOT NULL,
  idmp_key            VARCHAR(40)  NOT NULL UNIQUE,
  idmp_key_reg_dttm   VARCHAR(16)  NOT NULL,
  last_vndr_call_dttm VARCHAR(16)  NULL,
  crtn_stcd           VARCHAR(16)  NOT NULL, -- PENDING/SUBMITTING/COMPLETED
  try_cnt             INT          NOT NULL,
  dpst_addr           VARCHAR(128) NULL,
  reg_dttm            VARCHAR(16)  NOT NULL,
  last_chng_dttm      VARCHAR(16)  NOT NULL,
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)   NOT NULL,
  frst_reg_brcd   VARCHAR(4)   NOT NULL,
  last_chng_empno VARCHAR(6)   NOT NULL,
  last_chng_brcd  VARCHAR(4)   NOT NULL,
  PRIMARY KEY (acnt_id, ntwk_cd, tkn_smbl),
  FOREIGN KEY (acnt_id) REFERENCES bcm_acnt_m (acnt_id),
  CHECK (crtn_stcd IN ('PENDING', 'SUBMITTING', 'COMPLETED')),
  CHECK (try_cnt >= 0),
  CHECK (last_vndr_call_dttm IS NULL OR last_vndr_call_dttm >= idmp_key_reg_dttm),
  CHECK ((crtn_stcd = 'COMPLETED') = (dpst_addr IS NOT NULL))
);
```

`try_cnt=0`의 첫 제출은 조회 없이 생성하고, 이후 미완료 재시도는 해당 vault·`vndr_ast_id`의 주소 목록을 cursor 끝까지
먼저 조회한다. 주소가 정확히 하나면 회수한다. 후보가 없을 때의 키 유지·최장 호출시간을 더한 24시간 cooldown·새 키 회전,
최신 `try_cnt` CAS와 완료 시점의 키 세대 검사는 계정 생성 원장과 같다. 주소 cooldown은 HTTP 200 항목의
`CREATION_RETRY_LATER`·`retryAfterSeconds`로 표현한다. 둘 이상은 이
서비스의 “계정·자산당 주소 하나” 계약으로 자동 선택할 수 없으므로 conflict로 격리한다.
`bcm_addr_m` insert와 의도 `COMPLETED`·주소
갱신은 한 트랜잭션이다. Tag/Memo는 `bcm_addr_m`에 저장하지 않는 기존 PLAN #21 경계를 그대로 유지한다.

### bcm_addr_m — 주소 매핑

(계정, 자산)당 주소 하나 — UNIQUE 가 주소 발급 멱등의 물리 근거다.

```sql
CREATE TABLE bcm_addr_m (
  acnt_id     VARCHAR(64)  NOT NULL,       -- 계정
  ntwk_cd     VARCHAR(20)  NOT NULL,       -- 네트워크 코드
  tkn_smbl    VARCHAR(16)  NOT NULL,       -- 토큰 심볼
  dpst_addr   VARCHAR(128) NOT NULL,       -- 발급된 입금 주소
  reg_dttm    VARCHAR(16)  NOT NULL,       -- 발급 일시
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (acnt_id, ntwk_cd, tkn_smbl)
);
CREATE INDEX idx_bcm_addr_lookup ON bcm_addr_m (dpst_addr, ntwk_cd);
```

| 컬럼 | 뜻 |
|---|---|
| `PRIMARY KEY (acnt_id, ntwk_cd, tkn_smbl)` | (네트워크, 토큰)당 주소 하나 — 같은 자산의 주소를 더 두려면 계정을 더 만든다. 네트워크를 키에 넣어 같은 토큰의 여러 네트워크가 공존한다 |
| `idx_bcm_addr_lookup` | 역방향 조회 — 입금 감지가 "이 주소가 어느 계정인가"를 여기서 푼다 |

### bcm_whk_l — 수신 알림 원본

수신부가 웹훅 알림을 받은 그대로 적재하는 **수신 인박스(transactional inbox)** — 수신은 서명 검증·이 적재·200 응답까지만 하고(요청당 3단계), 판단은 워커가 분리해서 미처리분을 집어 간다([흐름](02-bcm-flow.md) 감지). finalize 원본 일 배치가 여기서 payload 를 뽑는다.

큐가 아니라 테이블로 두는 근거 — ① `noti_id` PK 로 중복 알림을 물리적으로 걸러낸다 ② 원문 payload 를 보관해 재처리·`bcm_raw_tx_l` 원본의 출처가 된다 ③ 미처리 적체를 조회로 들여다본다 ④ `SELECT … FOR UPDATE SKIP LOCKED` 로 tx 단위 락 분배가 된다. 발행 쪽에는 이미 큐(3토픽)가 있다.

```sql
CREATE TABLE bcm_whk_l (
  noti_id       VARCHAR(64)   PRIMARY KEY,   -- 웹훅 알림 id — 벤더가 알림마다 붙이는 v2 UUID. unique 가 중복 수신 방어
  evnt_typ      VARCHAR(64)   NOT NULL,      -- 벤더 eventType (transaction.status.updated 등) — 벤더 값 그대로
  vndr_tx_id    VARCHAR(64)   NULL,          -- 벤더 tx id — 이 알림이 가리키는 거래
  payload       TEXT          NOT NULL,      -- 수신 바이트 그대로 — 파싱·재직렬화 전의 원문. 판단·원본 보관의 입력
  payload_hash  CHAR(64)      NOT NULL,      -- 수신 바이트의 SHA-256 (소문자 hex) — 수신부가 계산
  sign_vl       TEXT          NOT NULL,      -- 수신 서명 헤더 원문 — 벤더가 보낸 것임을 나중에 다시 증명하는 근거
  rcv_dttm      VARCHAR(16)   NOT NULL,      -- 수신 일시
  prcs_stcd     VARCHAR(1)    NOT NULL,      -- 판단 처리 상태 P:미처리 / S:처리완료 / F:격리(poison)
  rtry_cnt      INT           NOT NULL,      -- 판단 시도 횟수 — 상한 초과 시 F 로 격리
  err_msg       VARCHAR(1000) NULL,          -- 마지막 실패 요약 (격리 사유)
  prcs_dttm     VARCHAR(16)   NULL,          -- 처리 일시
  vndr_cmpl_yn  VARCHAR(1)    NOT NULL DEFAULT 'N', -- 성공 처리한 원문의 data.status=COMPLETED 여부
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  CHECK (vndr_cmpl_yn IN ('Y', 'N')),
  CHECK (vndr_cmpl_yn IS NOT NULL)       -- 온라인 NOT NULL 검증 증명 — V18 재실행 안전을 위해 유지
);
CREATE INDEX idx_bcm_whk_pick ON bcm_whk_l (prcs_stcd, rcv_dttm);  -- 판단 워커의 집기 — 미처리(P) 오래된 순
CREATE INDEX idx_bcm_whk_completed_archive
  ON bcm_whk_l (vndr_tx_id, rcv_dttm DESC, noti_id DESC)
  WHERE prcs_stcd = 'S' AND vndr_cmpl_yn = 'Y' AND vndr_tx_id IS NOT NULL; -- 미보관 COMPLETED 선별
```

| 컬럼 | 뜻 |
|---|---|
| `noti_id` | insert 충돌 = 같은 알림의 중복 전달 — 무시하고 200 을 돌려준다. 중복 방어가 물리 제약으로 끝난다 |
| `payload` | 검증·파싱 전의 원본을 **받은 바이트 그대로** 둔다. 판단 버그가 있어도 원본으로 재처리할 수 있고, finalize 원본 보관이 그 tx 의 마지막 COMPLETED 알림의 이 값을 옮겨 간다. JSONB 가 아니라 TEXT 인 이유 — JSONB 는 저장 시 정규화(키 재정렬·공백)돼 **꺼낸 값이 원문 바이트가 아니다**. 저장했다 꺼낸 본문에 원래 서명을 붙이면 벤더 검증이 401 로 떨어진다 (2026-08 PoC 실측). 판단할 때만 JSON으로 읽고, 반복 운영 조회는 아래 표식을 사용해 원문을 다시 파싱하지 않는다 |
| `payload_hash` | 무결성 증명의 기준값. **HTTP 본문을 문자열로 바꾸기 전 `byte[]` 로 계산한다** — 수신부는 바이트를 한 번 읽어 서명 검증·저장·해시 세 곳에 같은 배열을 쓴다. `bcm_raw_tx_l` 로 옮길 때 다시 계산하지 않고 이 값을 복사한다 |
| `sign_vl` | 해시는 "우리가 저장한 바이트가 우리가 해시한 바이트와 같다"까지만 증명한다. 벤더가 보낸 것임을 나중에 다시 증명하려면 서명이 함께 있어야 하고, 서명은 수신 시점에만 존재한다. 서명 검증을 통과한 알림만 적재되므로 NOT NULL |
| `vndr_cmpl_yn` | 판단 워커가 지원 transaction event를 성공 처리해 `P→S`로 바꾸는 같은 UPDATE에서 `data.status=COMPLETED`이면 `Y`로 남긴다. 실패·격리·미지원 event는 `N`이며, 보관·미보관 메트릭은 이 표식과 partial index로 원문 JSON 반복 파싱을 피한다 |
| 보존 | 처리 후 N일(운영 설정값) 뒤 정리 — 장기 보존은 `bcm_raw_tx_l` 몫 |

이 표식은 application이 이미 파싱한 vendor status를 persistence port에 boolean으로 전달한 결과가 정본이다. 롤링 전환은 V17의
짧은 expand(기존 행만 NULL 허용)와 구버전 writer 호환 trigger, V18의 1,000건 단위 commit backfill·제약 검증·NOT NULL 전환,
transaction 밖 `CREATE INDEX CONCURRENTLY` 순서로 수행한다. 호환 trigger는 구버전 worker가 `P→S`로 바꾸면서 표식을 쓰지 않은
현재 4개 지원 event만 보완하고, 신버전 worker가 명시한 `Y`는 덮어쓰지 않는다.

기존 `bcm_whk_l` 행이 있는 DB는 V17 직후·V18 직전에 DBA가
`db/operations/prepare_v18_completed_webhook_backfill_index.sql`을 transaction 밖에서 실행한다. 이 SQL은
`vndr_cmpl_yn IS NULL`인 `noti_id`만 담는 임시 partial index를 concurrent 생성한다. V18의 각 batch가 처리한 행은 index에서 즉시
빠지므로 이미 처리한 PK prefix를 매번 다시 읽지 않는다. V19는 V18 성공 뒤 임시 index를 concurrent 제거하며 재실행 가능하다.
빈 DB는 준비 SQL을 생략해도 backfill 대상이 없고, manifest의 V19 cleanup은 index 부재를 정상으로 취급한다.

### bcm_tx_l — 거래 운영 상태

판단 워커가 알림에서 만들어 추적하는 **논리 거래 행** — 상태 변화를 가려 이벤트를 발행하고, 막힘 점검이 오래 미확정인 후보를 여기서 골라낸다. boost가 생겨도 최초 `vndr_tx_id` 행을 유지하며 대체 물리 거래는 `bcm_boost_l`에서 root로 접는다.

```sql
CREATE TABLE bcm_tx_l (
  vndr_tx_id      VARCHAR(64)  PRIMARY KEY,   -- 최초 벤더 tx id = 고객에게 보이는 논리 거래 id
  actv_tx_id      VARCHAR(64)  NOT NULL UNIQUE, -- 현재 RBF head 또는 먼저 채굴된 승자 tx — 최초에는 vndr_tx_id
  ext_tx_id       VARCHAR(128) NULL UNIQUE,   -- 제출 건의 백엔드 요청 키 — 재제출 중복 차단, 입금 감지 건은 NULL
  acnt_id         VARCHAR(64)  NOT NULL,      -- 귀속 계정 — 이벤트 파티션 키
  ntwk_cd         VARCHAR(20)  NOT NULL,      -- 네트워크 코드
  tkn_smbl        VARCHAR(16)  NOT NULL,      -- 토큰 심볼
  tx_hash         VARCHAR(128) NULL,          -- actv_tx_id의 온체인 hash — boost 접수 시 NULL, 새 거래 웹훅에서 채움
  last_pub_stcd   VARCHAR(16)  NOT NULL,      -- 마지막으로 발행한 TxStatus — 이 값과 다를 때만 새 이벤트를 낸다
  cnfm_cnt        INT          NOT NULL,      -- 마지막으로 본 confirmation 수 — 큰 값으로만 갱신(감소 금지)
                                              -- 늦게 온 알림은 낮은 값을 담고 있어, 그대로 쓰면 기록이 역행한다
  vndr_sub_stcd   VARCHAR(64)  NULL,          -- 마지막 알림의 벤더 subStatus 원어 — 운영 조사용, 이벤트 미탑재
  vndr_ntwk_stcd  VARCHAR(64)  NULL,          -- 마지막 알림의 벤더 networkStatus 원어 — 운영 조사용, 이벤트 미탑재
  stall_alrt_dttm VARCHAR(16)  NULL,          -- 막힘 경보 올린 일시 — 있으면 다음 주기 건너뜀 · 해소 전이 시 NULL
  vndr_crt_dttm   VARCHAR(16)  NOT NULL,      -- 벤더 createdAt을 UTC 초 단위로 변환 — 대사 시간축, set-once
  rcnc_chck_dttm  VARCHAR(16)  NULL,          -- 창 밖 미결 거래의 마지막 단건 조회 claim/확인 일시
  rcnc_chck_cnt   INT          NOT NULL DEFAULT 0, -- 단건 조회 횟수 — 영속 백오프 단계
  rcnc_stop_dttm  VARCHAR(16)  NULL,          -- 최대 추적 나이 도달 시각 — 이후 자동 단건 조회 중단
  frst_dtct_dttm  VARCHAR(16)  NOT NULL,      -- 처음 감지한 일시
  last_chng_dttm  VARCHAR(16)  NOT NULL,      -- 마지막 갱신 일시 — 막힘 점검의 기준
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
CREATE INDEX idx_bcm_tx_stall ON bcm_tx_l (last_pub_stcd, last_chng_dttm)
  WHERE stall_alrt_dttm IS NULL AND last_pub_stcd IN ('SUBMITTED', 'CONFIRMED');
CREATE INDEX idx_bcm_tx_rcnc ON bcm_tx_l (last_pub_stcd, rcnc_stop_dttm, rcnc_chck_dttm, frst_dtct_dttm)
  WHERE last_pub_stcd IN ('SUBMITTED', 'CONFIRMED');
```

| 컬럼 | 뜻 |
|---|---|
| `vndr_tx_id` | 최초 벤더 tx id이자 root 논리 거래 id. boost 뒤에도 바뀌지 않고 고객 이벤트·조회 응답의 `txId`가 된다 |
| `actv_tx_id` | 현재 RBF head. 최초에는 `vndr_tx_id`, 대체 접수가 확인되면 `new_tx_id`로 바꾼다. 단 RBF 접수와 원 거래 채굴은 경합하므로, root 계열의 어느 물리 거래든 confirmation이 생기거나 COMPLETED가 먼저 오면 그 거래를 승자로 다시 active에 놓고 root를 진행·확정한다 |
| `ext_tx_id` | 최초 제출 요청 키. boost 뒤에도 그대로 고객 이벤트에 싣는다. **멱등 판정은 여기가 아니라 `bcm_sbmt_l`이 한다** — 이 행은 웹훅이 와야 생기고 멱등은 그보다 앞선 제출 시점에 끝나야 한다 |
| `tx_hash` | `actv_tx_id`의 현재 hash. RBF 접수가 확인돼 active 거래를 바꿀 때 일단 NULL로 비우고 대체 거래의 조회·웹훅으로 채운다. 후보 선별에 쓸 수 있지만 **RBF 직전에는 반드시 벤더 단건 조회로 다시 확인**한다 |
| `last_pub_stcd` | 새 알림의 상태와 이 값을 [허용 전이 표](02-bcm-flow.md)에 대조해 발행 여부를 가린다. 발행은 `bcm_outbox_l` 에 같은 트랜잭션으로 적재한다 |
| `cnfm_cnt`·`last_chng_dttm` | **줄지 않는다** — 큰 값(늦은 시각)으로만 갱신한다. 막힘 점검의 입력이다 |
| `vndr_sub_stcd`·`vndr_ntwk_stcd` | 마지막 알림의 벤더 원어 — 운영 조사(FAILED 사유 구분·대사 불일치 분석)용. whk_l 은 보존 기간 후 정리되므로 장기 조회처는 여기다. **이벤트에는 싣지 않는다** |
| `vndr_crt_dttm` | Fireblocks `createdAt`을 저장 직전 UTC `yyyyMMddHHmmss`로 변환한 값. 최초 관찰 때만 기록하고 이후 웹훅 수신 시각으로 덮지 않는다. tx 대사는 벤더 목록과 이 컬럼의 같은 닫힌 구간을 비교한다 |
| `rcnc_chck_dttm`·`rcnc_chck_cnt` | createdAt 창 밖에 남은 미결 거래의 단건 조회 체크포인트. 후보를 `FOR UPDATE SKIP LOCKED`로 원자 claim하면서 벤더 호출 전에 갱신해 다중 인스턴스 중복 조회를 막고, 30초·1분·5분·15분·1시간 백오프의 다음 due를 계산한다 |
| `rcnc_stop_dttm` | 기본 7일의 최대 추적 나이를 넘긴 시각. 값이 있으면 자동 단건 조회에서 제외하고 리포트·경보로 넘긴다. 웹훅·대사에서 더 최신 벤더 관찰이 실제 적용되면 세 reconciliation 컬럼을 초기화한다 |

### bcm_sbmt_l — 제출 원장

우리가 벤더에 낸 건(출금·내부이체·sweep·밴드S)을 **벤더에 보내기 전에** 먼저 적는 원장이다. 두 가지 일을 한다 — ① `ext_tx_id` 멱등 판정(같은 키 + 같은 내용이면 처음 `txId` 반환, 내용이 다르면 거절) ② 우리 vault 에서 나간 웹훅이 어느 계열인지 가르는 기준.

`bcm_tx_l` 에 흡수하지 않는 이유는 **키가 다르기 때문**이다. `bcm_tx_l` 의 PK 는 벤더 tx id 인데 제출 시점에는 그 값을 아직 모른다. 반대로 멱등 판정은 벤더를 부르기 전에 끝나야 한다 — 부른 뒤에 적으면 그 사이에 죽었을 때 돈이 나갔는지 알 방법이 없다. 그래서 우리 요청 키를 PK 로 갖는 원장을 따로 둔다.

```sql
CREATE TABLE bcm_sbmt_l (
  ext_tx_id     VARCHAR(128) PRIMARY KEY,   -- 우리 요청 키 = 멱등 키. 승인된 출금 지시 1건과 1:1
  req_hash      CHAR(64)     NOT NULL,      -- 요청 내용의 SHA-256 (소문자 hex) — 아래 canonical 규칙
  hash_vrsn     VARCHAR(8)   NOT NULL,      -- canonical 규칙 판 (v1 · cc-v1) — 판이 다르면 아래 필드로 재계산해 비교
  sbmt_stcd     VARCHAR(16)  NOT NULL,      -- 제출 상태 REQUESTED · SUBMITTED · FAILED
  claim_id      VARCHAR(36)  NULL,          -- 진행 중 소유권 토큰(UUID) — 이 값을 쥔 호출자만 벤더에 제출한다
  claim_exp_dttm VARCHAR(16) NULL,          -- 소유권 만료 일시 — 지나면 다른 호출자가 뺏는다 (죽은 소유자 방치 방지)
  tx_dvcd       VARCHAR(16)  NOT NULL,      -- WITHDRAWAL · INTERNAL · SWEEP_APPROVE · SWEEP_BATCH · BAND_S
  vndr_tx_id    VARCHAR(64)  NULL,          -- 벤더 응답(또는 먼저 온 웹훅)으로 채운다. NULL = 벤더에 닿았는지 미확인
  swp_exec_id   VARCHAR(36)  NULL,          -- SWEEP_BATCH일 때 bcm_swp_exec_l 연결
  snd_acnt_id   VARCHAR(64)  NOT NULL,      -- 보내는 계정 — 이벤트 파티션 키이기도 하다
  rcv_dvcd      VARCHAR(16)  NOT NULL,      -- 목적지 유형 ADDRESS · ACCOUNT · WHITELISTED
  rcv_vl        VARCHAR(128) NOT NULL,      -- 목적지 식별값 — 유형에 따라 주소 · 계정 · 등록지갑 id 중 하나
  ntwk_cd       VARCHAR(20)  NOT NULL,      -- 네트워크 코드
  tkn_smbl      VARCHAR(16)  NOT NULL,      -- 토큰 심볼
  trsf_amt      NUMERIC(36,18) NOT NULL,    -- 정규화한 금액
  call_data     TEXT         NULL,          -- cc-v1 CONTRACT_CALL calldata 소문자 hex. 일반 전송은 NULL
  req_dttm      VARCHAR(16)  NOT NULL,      -- 접수 일시 — 미결 제출 점검의 기준
  rsp_dttm      VARCHAR(16)  NULL,          -- 벤더 응답 일시
  last_chck_dttm VARCHAR(16) NULL,           -- 미결 점검이 마지막으로 벤더 조회한 일시
  chck_cnt      INTEGER      NOT NULL DEFAULT 0, -- 미결 조회 횟수 — 백오프·경보 기준
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  CHECK ((tx_dvcd IN ('SWEEP_APPROVE', 'SWEEP_BATCH')) = (call_data IS NOT NULL)),
  CHECK (call_data IS NULL OR call_data ~ '^0x([0-9a-f][0-9a-f])+$')
);
CREATE UNIQUE INDEX ux_bcm_sbmt_vndr_tx ON bcm_sbmt_l (vndr_tx_id) WHERE vndr_tx_id IS NOT NULL;
CREATE INDEX idx_bcm_sbmt_open ON bcm_sbmt_l (sbmt_stcd, last_chck_dttm, req_dttm); -- 미결 점검 후보
```

| 컬럼 | 뜻 |
|---|---|
| `ext_tx_id` | PK 가 멱등의 물리 근거다. 제출 요청이 오면 **벤더를 부르기 전에** 이 행을 먼저 넣는다 — 충돌하면 이미 받은 키다 |
| `req_hash` | "같은 내용인가"의 판정값. 아래 canonical 규칙으로 만든 문자열의 SHA-256. **요청 원문은 저장하지 않는다** — `travelRule` 이 트래블룰 게이트가 만든 암호화 산출물(IVMS101 계열 개인정보)이라, 원문을 남기면 매니저가 그 보관 주체가 된다. 매니저는 운반만 한다([흐름](02-bcm-flow.md)) |
| `hash_vrsn` | canonical 규칙을 나중에 바꿔도 옛 행을 되살릴 수 있게 판을 함께 적는다. 판이 다르면 해시를 믿지 않고 아래 개별 컬럼으로 그 판의 규칙을 다시 적용해 비교한다 |
| `call_data` | `cc-v1` 재계산에 쓰는 CONTRACT_CALL 입력. `SWEEP_APPROVE`·`SWEEP_BATCH`에 필수이며 canonical과 같은 소문자 `0x` hex로 저장한다. 일반 출금·내부이체에는 NULL이다. calldata에는 주소·금액이 ABI 인코딩돼 있으므로 원문 payload·시크릿은 저장하지 않는다 |
| `tx_dvcd` | **웹훅 분류의 유일한 기준.** `SWEEP_APPROVE`는 고객 vault의 allowance 설정, `SWEEP_BATCH`는 운영 계정의 최상위 batch 호출, `BAND_S`는 승인된 핫·콜드 이동 item이다. 셋 모두 고객 토픽으로 발행하지 않는다 |
| `swp_exec_id` | `SWEEP_BATCH`에 필수이고 그 밖에는 NULL이다. 최상위 벤더 tx를 실행 1건과 연결하고, 원천 이동은 `bcm_swp_item_l`에서 펼친다 |
| `vndr_tx_id` | 벤더 응답으로 채우는 게 정상이지만, 응답을 못 받고 웹훅이 먼저 와도 그때 채운다. 부분 UNIQUE 인덱스가 벤더 tx 와 1:1 을 보장한다 |
| `sbmt_stcd` | `REQUESTED` = 벤더에 닿았는지 모름 · `SUBMITTED` = 벤더가 tx id 를 줌 · `FAILED` = 벤더가 검증으로 확정 거절. 복구 규칙은 [흐름](02-bcm-flow.md) 출금 절 |
| `claim_id`·`claim_exp_dttm` | **같은 키가 동시에 들어와도 벤더 제출은 한 번만** 하게 하는 소유권이다. 행을 넣거나 뺏을 때 토큰과 만료를 함께 적고, 그 토큰을 쥔 호출자만 벤더를 부른다. **상태값(`SUBMITTING` 같은)으로 하지 않는 이유** — 소유자 프로세스가 죽으면 그 상태에서 영원히 멈춘다. 만료 시각이 붙어 있으면 스스로 풀린다. 만료는 벤더 제출 호출 타임아웃보다 길게 잡는다(운영 설정값) |
| `last_chck_dttm`·`chck_cnt` | 재시도 요청이 오지 않는 오래된 `REQUESTED` 를 미결 점검이 확인한 흔적. 성공적으로 찾았을 때뿐 아니라 미발견·조회 실패에도 갱신해 같은 건을 매 주기마다 두드리지 않고 백오프·경보한다. **점검기는 조회만 하고 재제출하지 않는다** — 일반 출금 원문을 저장하지 않고, 원 API 요청과 경합하면 중복 전송이 될 수 있기 때문이다 |
| `snd_acnt_id` | 출금은 출금 풀 vault 계정, 내부이체는 출발 계정. 이벤트 파티션 키가 이 값이라 따로 `acnt_id` 를 두지 않는다 |
| sweep·밴드S 제출의 공통 컬럼 | `SWEEP_APPROVE`는 고객 계정·토큰 컨트랙트·승인 cap, `SWEEP_BATCH`는 운영 계정·sweep 컨트랙트·요청 총액을 `snd_acnt_id`·`rcv_vl`·`trsf_amt`에 저장한다. `BAND_S`는 proposal item의 출발 vault·목적지·네트워크·자산·수량을 저장하며 `bcm_bnds_exec_evt_l.ext_tx_id`가 실행 item과 잇는다. 항목별 이동안의 정본은 proposal item이다 |
| 보존 | 종결 뒤에도 남긴다 — `ext_tx_id` 재사용 탐지가 영구적이어야 한다(벤더도 `externalTxId` 를 영구 보관한다) |

#### sbmt_stcd 전이 — 어느 경로로 왔는지가 함께 판단 기준이다

같은 전이라도 **누가 가져온 사실인지**에 따라 허용이 갈린다. 제출 응답은 우리가 부른 결과이고, 웹훅은 벤더가 서명해 보낸 사실이라 무게가 다르다.

| 현재 | 다음 | 허용 경로 | 조건 |
|---|---|---|---|
| (없음) | `REQUESTED` | 제출 접수 | 행을 넣으면서 소유권을 함께 잡는다 |
| `REQUESTED` | `SUBMITTED` | 제출 응답 | **소유권을 쥔 호출자만** |
| `REQUESTED` | `SUBMITTED` | 웹훅 · 미결 점검 | 소유권과 무관 — 벤더 tx id 를 회수한다. 미결 점검은 조회만 하고 거래를 새로 제출하지 않는다 |
| `REQUESTED` | `FAILED` | 제출 응답 | **확정 거절로 분류된 응답만**(아래 [흐름](02-bcm-flow.md) 4xx 표). 소유권을 쥔 호출자만 |
| `FAILED` | `SUBMITTED` | **웹훅 · 거래 대사만** | 벤더에서 실재가 뒤늦게 확인된 거래를 회수 — 아래. `REQUESTED` 전용 미결 점검 대상은 아니다 |
| `FAILED` | `REQUESTED` | 제출 응답(재제출 접수) | **같은 요청**의 재제출만. 새 소유권을 조건부 갱신 한 번으로 잡으면서 함께 전이한다 — 내용이 다르면 `409` 라 여기까지 오지 않는다 |
| `SUBMITTED` | — | | 종착. 다른 `vndr_tx_id` 가 오면 충돌로 보고 격리한다 |

★ **`FAILED` → `SUBMITTED` 회수를 허용한다.** 우리가 벤더 응답을 거절로 읽어 `FAILED` 로 적어 놓았는데 나중에 그 거래의 웹훅이 서명 검증을 통과해 도착하는 경우가 있다. 웹훅은 **벤더가 거래를 실제로 만들었다는 최종 근거**라 우리 판단보다 나중이고 더 정확하다. 이걸 막으면 실재하는 거래가 원장에서 실패로 남고 그 알림이 격리 처리돼, 돈은 나갔는데 아무도 모르는 상태가 된다.

- **제출 응답 경로로는 이 전이를 하지 않는다.** 우리가 부른 결과로 `FAILED` 를 뒤집으면 거절 판정 자체가 무의미해진다. 되살리는 건 서명 검증을 통과한 웹훅이나 거래 대사가 벤더에서 다시 확인한 사실뿐이다.
- 이미 다른 `vndr_tx_id` 가 적혀 있으면 회수하지 않고 충돌로 격리한다 — 한 요청 키에 거래가 둘 붙은 상황이라 사람이 봐야 한다. **격리는 즉시다** — 재시도로 풀릴 성질이 아니라 재시도 예산을 태우면 격리만 늦어진다.
- ★ **`FAILED` → `REQUESTED`(재제출)와 `FAILED` → `SUBMITTED`(뒤집기)는 다른 일이다.** 앞은 "다시 한 번 보내 본다"라 결과를 벤더에게 새로 묻는 것이고, 뒤는 "거절 판정이 틀렸다"를 우리 판단만으로 선언하는 것이다. 그래서 앞은 제출 응답 경로에서 허용하고 뒤는 금지한다. 재제출도 벤더가 다시 거절하면 그대로 `FAILED` 로 돌아간다.

#### canonical 요청 — 무엇이 같아야 "같은 요청"인가

**자금이 어디서 어디로 얼마나 움직이는지를 규정하는 값만** 넣는다. 고정 순서 7줄을 줄바꿈으로 이어 SHA-256 한다.

```
1  from.type          (ACCOUNT 고정)
2  from.accountId
3  to.type            (ADDRESS · ACCOUNT · WHITELISTED)
4  to 의 식별값        (유형에 따라 address · accountId · walletId 중 채워진 하나)
5  network
6  symbol
7  정규화한 amount
```

- **JSON 정규화는 쓰지 않는다** — 키 정렬·공백 처리가 구현체마다 흔들려 같은 요청이 다른 해시가 될 수 있다. 위 7개 값은 전부 길이 제한을 통과한 한 줄짜리라 줄바꿈이 들어갈 수 없고, 그래서 줄바꿈 결합이 안전하다.
- **`amount` 만 정규화한다** — 십진수로 파싱한 뒤 뒤따르는 0 을 떼고 지수 없는 평문으로 쓴다(`1.50` → `1.5`). 같은 금액을 다른 문자열로 적은 재시도를 거절하면 정당한 재시도를 막는 셈이 된다.
- **나머지는 원문 그대로 비교한다 — 대소문자를 바꾸지 않는다.** 주소는 체인마다 대소문자가 의미를 갖는다(EVM 체크섬은 무시해도 되지만 base58 계열은 대소문자가 다르면 다른 주소다). 소문자로 눕히면 서로 다른 주소가 같아질 수 있어, 안전한 쪽은 원문 비교다. `network`·`symbol` 도 마찬가지 — 등록되지 않은 표기는 [자산 매핑](07-asset-master.md)이 이미 400 으로 거른다.
- **`note` 와 `travelRule` 은 넣지 않는다.** `note` 는 벤더 거래 메모라 자금 이동을 바꾸지 않는다. `travelRule` 은 게이트가 다시 만들면 같은 출금 지시라도 암호문이 달라질 수 있어, 넣으면 정당한 재시도가 거절된다.

##### CONTRACT_CALL canonical `cc-v1`

Sweep approve와 최상위 batch 호출은 일반 전송 7값 대신 아래 7줄을 LF로 이어 SHA-256 한다. 마지막 LF는 붙이지 않는다.

```
1  CONTRACT_CALL       (고정 문자열)
2  snd_acnt_id
3  rcv_vl 소문자       (호출 대상 컨트랙트 주소)
4  ntwk_cd
5  tkn_smbl
6  정규화한 trsf_amt
7  call_data 소문자
```

- `hash_vrsn='cc-v1'`이다. `call_data`까지 원장에 보존하므로 위 개별 컬럼만으로 옛 행의 canonical hash를 다시 계산할 수 있다.
- `SWEEP_APPROVE`의 `call_data`는 token contract의 `approve(sweep contract, amount)`, `SWEEP_BATCH`는 sweep contract의 `batchSweep` 호출이다. 호출 대상은 `rcv_vl`, 사람 단위 의미 금액은 `trsf_amt`에 별도로 보존한다.
- calldata와 EVM 컨트랙트 주소는 hex 표기의 대소문자만 다른 재시도를 같은 요청으로 보도록 소문자로 정규화한다. `ntwk_cd`·`tkn_smbl`·`snd_acnt_id`는 일반 canonical과 같이 원문을 유지한다.

### bcm_outbox_l — 발행 아웃박스

워커가 상태를 바꾸는 **같은 트랜잭션**에 발행할 이벤트를 여기 적재한다(상태 갱신 + 발행 예약 = 한 커밋). 별도 relay 가 미발송(`P`) 행을 오래된 순으로 집어 큐로 보내고 `S` 로 표시한다. 컨슈머는 `evnt_id` 로 중복을 접는다(코어 ADR-002 Outbox 와 같은 패턴).

```sql
CREATE TABLE bcm_outbox_l (
  evnt_id         VARCHAR(36)   PRIMARY KEY,  -- 이벤트ID (time-ordered UUID v7) · 컨슈머 dedup 키
  evnt_dt         VARCHAR(8)    NOT NULL,     -- 이벤트일자 — 조회·파티셔닝
  vndr_tx_id      VARCHAR(64)   NOT NULL,     -- 집합체ID(코어 agg_id 대응) — 벤더 tx id
  agg_typ_dvcd    VARCHAR(2)    NOT NULL,     -- 집합체유형 TX:거래 / DL:델타
  evt_typ_dvcd    VARCHAR(4)    NOT NULL,     -- 이벤트유형 — 코어 정합(TXCK/TXCF/TXFL)
  topic           VARCHAR(32)   NOT NULL,     -- 발행 큐: deposit / withdrawal / internal-events
  payload         JSONB         NOT NULL,     -- 이벤트 본문
  evnt_stcd       VARCHAR(1)    NOT NULL,     -- 발행상태 P:PENDING / D:DISPATCHED / F:FAILED / S:SUCCESS
  rtry_cnt        INT           NOT NULL,     -- 재시도횟수
  max_rtry_cnt    INT           NOT NULL,     -- 최대재시도횟수
  orgn_id         VARCHAR(36)   NULL,         -- 원본이벤트ID — 재발행·파생 추적
  trace_id        VARCHAR(64)   NULL,         -- 분산추적 — BC→코어 상관관계
  pub_dttm        VARCHAR(16)   NULL,         -- 최초 DISPATCHED 시각
  last_rtry_dttm  VARCHAR(16)   NULL,         -- 최종재시도일시
  err_msg         VARCHAR(1000) NULL,         -- 오류메시지 (마지막 실패 요약)
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
CREATE INDEX idx_bcm_outbox_send ON bcm_outbox_l (evnt_stcd, evnt_id);  -- 미발행(P) 오래된 순 = 시간정렬 UUID v7
CREATE INDEX idx_bcm_outbox_sweep_operations ON bcm_outbox_l (topic, evnt_stcd, evnt_id); -- sweep 운영 신호·완료 anti-join
```

| 컬럼 | 뜻 |
|---|---|
| `evnt_id` | time-ordered UUID v7 — PK 이자 컨슈머 dedup 키. relay 가 같은 행을 두 번 보내도 컨슈머가 이 값으로 접는다. 시간정렬이라 별도 생성시각 없이 발송 순서로 쓴다 |
| `bcm_tx_l` 갱신 | **행을 잠그고 판정한다** (2026-08-06 확정) — 전이 허용 여부는 직전 상태를 읽어야 정해지므로(02 허용 전이 표) 읽고 쓰는 사이에 다른 알림이 끼면 판정이 어긋난다. 그 tx 행을 `SELECT … FOR UPDATE` 로 잠근 뒤 판정·갱신한다. 같은 tx 의 알림만 경합하므로 잠금 범위가 좁다. `cnfm_cnt` 는 추가로 `GREATEST` 로 감싸 **줄지 않게** 한다(02) |
| set-once 컬럼 | **갱신문에서 제외한다** — `vndr_crt_dttm`·`frst_dtct_dttm` 과 감사의 `frst_reg_empno`·`frst_reg_brcd` 는 최초 흔적이라 다시 쓰지 않는다. 갱신은 `last_chng_*` 만 건드린다 |
| UNIQUE 충돌 | 도메인 예외로 바꾸고 **재조회해 이긴 값을 돌려준다** — 경합해도 결과는 하나다 |
| `evt_typ_dvcd` | 코어 이벤트 어휘와 통일 — TXCK(Checking)·TXCF(Confirmed)·TXFL(Failed)·**TXRJ(Rejected — 2026-08-06 신설 제안, 코어 확정 대기)**. BC→코어 계약이 한 어휘로 흐른다 |
| `evnt_stcd` | 워커 적재 시 `P`, relay 발송 성공 시 `S`, 실패 누적 시 `F`. relay 는 `P` 를 `evnt_id` 순으로 집는다 |

### bcm_evnt_cmpl_l — DAW-CORE 이벤트 처리 완료

outbox의 발행 상태와 소비자의 업무 반영 완료를 분리한다. 완료 행은 최초 한 번만 추가하고 이벤트 상태나 payload를
덮어쓰지 않는다.

```sql
CREATE TABLE bcm_evnt_cmpl_l (
  evnt_id         VARCHAR(36)  NOT NULL REFERENCES bcm_outbox_l (evnt_id),
  cnsmr_dvcd      VARCHAR(16)  NOT NULL,       -- 1차: DAW_CORE
  cmpl_dttm       VARCHAR(16)  NOT NULL,       -- 서버가 기록한 최초 처리 완료 시각
  frst_reg_empno  VARCHAR(6)   NOT NULL,
  frst_reg_brcd   VARCHAR(4)   NOT NULL,
  last_chng_empno VARCHAR(6)   NOT NULL,
  last_chng_brcd  VARCHAR(4)   NOT NULL,
  PRIMARY KEY (evnt_id, cnsmr_dvcd),
  CHECK (cnsmr_dvcd IN ('DAW_CORE'))
);

CREATE INDEX idx_bcm_evnt_cmpl_consumer_time
  ON bcm_evnt_cmpl_l (cnsmr_dvcd, cmpl_dttm, evnt_id);
```

- `PUT /events/{eventId}/completion`은 outbox 행을 잠근 뒤 `evnt_stcd='S'`이고 DAW-CORE 대상 topic인지 검사한다.
- 같은 `(evnt_id, cnsmr_dvcd)` 재요청은 갱신하지 않고 최초 `cmpl_dttm`을 돌려준다.
- 완료되지 않은 필수 소비자 이벤트는 정리하지 않는다. outbox를 아카이브할 때 완료 원장도 같은 보존 단위로 옮긴다.

### bcm_swp_req_l · bcm_swp_req_item_l · bcm_swp_req_src_l — DAW-CORE sweep 요청

DAW-CORE가 보낸 batch 요청과 각 계정 항목, 판단 근거인 입금 `FINALIZED eventId`를 불변으로 보존한다. 요청은
온체인 실행보다 상위 원장이며 하나의 요청이 정책 최대 M에 따라 여러 `bcm_swp_exec_l`로 나뉠 수 있다.

```sql
CREATE TABLE bcm_swp_req_l (
  swp_req_id       VARCHAR(36)  PRIMARY KEY,
  ext_swp_req_id   VARCHAR(128) NOT NULL UNIQUE,
  req_hash         CHAR(64)     NOT NULL,
  ntwk_cd          VARCHAR(20)  NOT NULL,
  tkn_smbl         VARCHAR(16)  NOT NULL,
  swp_req_stcd     VARCHAR(16)  NOT NULL, -- ACCEPTED/BLOCKED/PROCESSING/COMPLETED/PARTIAL/FAILED
  item_cnt         INT          NOT NULL CHECK (item_cnt > 0),
  req_dttm         VARCHAR(16)  NOT NULL,
  fnsh_dttm        VARCHAR(16)  NULL,
  frst_reg_empno   VARCHAR(6)   NOT NULL,
  frst_reg_brcd    VARCHAR(4)   NOT NULL,
  last_chng_empno  VARCHAR(6)   NOT NULL,
  last_chng_brcd   VARCHAR(4)   NOT NULL
);

CREATE TABLE bcm_swp_req_item_l (
  swp_req_item_id  VARCHAR(36)  PRIMARY KEY,
  swp_req_id       VARCHAR(36)  NOT NULL REFERENCES bcm_swp_req_l (swp_req_id),
  item_seq         INT          NOT NULL,
  acnt_id          VARCHAR(64)  NOT NULL REFERENCES bcm_acnt_m (acnt_id),
  swp_req_item_stcd VARCHAR(16) NOT NULL, -- PENDING/PROCESSING/COMPLETED/FAILED
  last_fail_cd     VARCHAR(64)  NULL,
  frst_reg_empno   VARCHAR(6)   NOT NULL,
  frst_reg_brcd    VARCHAR(4)   NOT NULL,
  last_chng_empno  VARCHAR(6)   NOT NULL,
  last_chng_brcd   VARCHAR(4)   NOT NULL,
  UNIQUE (swp_req_id, item_seq),
  UNIQUE (swp_req_id, acnt_id)
);

CREATE TABLE bcm_swp_req_src_l (
  evnt_id          VARCHAR(36) PRIMARY KEY REFERENCES bcm_outbox_l (evnt_id),
  swp_req_item_id  VARCHAR(36) NOT NULL REFERENCES bcm_swp_req_item_l (swp_req_item_id),
  frst_reg_empno   VARCHAR(6)  NOT NULL,
  frst_reg_brcd    VARCHAR(4)  NOT NULL,
  last_chng_empno  VARCHAR(6)  NOT NULL,
  last_chng_brcd   VARCHAR(4)  NOT NULL
);

CREATE INDEX idx_bcm_swp_req_src_item
  ON bcm_swp_req_src_l (swp_req_item_id, evnt_id);
```

- `req_hash`는 `sweep-request-v1`·network·symbol과 `accountId` 오름차순, 각 계정의 `sourceEventId` 오름차순을 LF로
  이은 UTF-8 바이트의 SHA-256 소문자 hex다. 배열 순서만 바꾼 재시도는 같은 요청이다.
- 접수 트랜잭션에서 source eventId 오름차순으로 트랜잭션 advisory lock을 잡은 뒤 source outbox 행을 잠그고
  `DEPOSIT/FINALIZED`, 계정·network·symbol 일치, `DAW_CORE` 완료 확인, 다른 요청 미소비를 모두 검사한 뒤 세
  테이블과 실행 후보를 함께 기록한다. 서로 다른 외부 요청이 같은 source event를 동시에 제시해도 뒤 요청은 앞 요청 커밋
  뒤 새 statement snapshot에서 소비 여부를 다시 읽어 `409`로 종결하며 PK 예외를 API 500으로 노출하지 않는다.
- 실행 게이트가 중지됐으면 `BLOCKED`, 아니면 `ACCEPTED`로 접수한다. `BLOCKED`는 자금 실행 의도가 아니며 재개 뒤
  BAT가 최신 정책·컨트랙트·증적·게이트를 다시 검사해야 `PROCESSING`으로 바뀐다.

### bcm_swp_trgt — sweep 대상

접수된 sweep 요청 항목이 만들고, 주기 작업이 같은 네트워크·토큰의 대상을 묶는다. 입금 확정 관찰만으로는 만들지 않는다.
PK가 (계정, 자산)이라 같은 계정의 후속 요청은 기존 실행이 종결된 뒤 순서대로 합류한다. 실행과 항목을 먼저 만든 뒤
`actv_swp_exec_id + actv_item_seq`로 claim한다.

```sql
CREATE TABLE bcm_swp_trgt (
  acnt_id       VARCHAR(64)  NOT NULL,       -- 고객 계정
  ntwk_cd       VARCHAR(20)  NOT NULL,       -- 네트워크 코드
  tkn_smbl      VARCHAR(16)  NOT NULL,       -- 토큰 심볼
  reg_dttm      VARCHAR(16)  NOT NULL,       -- 처음 마킹된 일시
  actv_swp_exec_id VARCHAR(36) NULL,          -- 현재 claim한 sweep 실행 · NULL=선정 가능
  actv_item_seq INT          NULL,           -- 현재 실행 안의 항목 순번
  try_cnt       INT          NOT NULL,       -- 제출 시도 횟수 — 반복 실패 경보 기준
  last_try_dttm VARCHAR(16)  NULL,           -- 마지막 시도 일시
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (acnt_id, ntwk_cd, tkn_smbl),
  CHECK ((actv_swp_exec_id IS NULL) = (actv_item_seq IS NULL))
);
```

| 컬럼 | 뜻 |
|---|---|
| 삭제 기준 | batch 제출 성공이 아니라 **항목 성공 뒤 vault 잔액이 최소 미만임을 확인**했을 때 삭제한다. 가용 잔액이 정확히 0이면 가장 오래된 미완료 요청 항목 하나를 온체인 실행 없이 완료한 뒤 나머지 요청을 다시 평가한다. vault 잔액이 진실이고 이 테이블은 작업 큐다 |
| 행 생성·삭제 | 요청 접수 → insert(있으면 유지) · 실행/항목 선기록과 같은 트랜잭션에서 claim · 해당 계정의 미완료 요청 item과 잔액이 모두 없으면 삭제 · 실패 또는 양의 잔액 잔존이면 claim을 NULL로 풀어 재선정 |
| `actv_swp_exec_id`·`actv_item_seq` | 한 최상위 batch tx 아래 어느 원천 이동으로 처리 중인지 가리키는 1:N 링크. 둘 다 NULL이거나 둘 다 값이어야 한다 |
| `try_cnt` | 최초 target claim을 첫 제출 시도 1회로 세고, 같은 `SUBMITTING` 실행의 실패 submission을 같은 externalTxId로 다시 획득할 때마다 target 행 잠금 아래 1씩 증가시켜 `last_try_dttm`도 갱신한다. 반복 실패가 임계(운영 설정값)를 넘으면 별도 gauge와 경보를 올린다. 재시도는 주기 작업 간격으로 제한되고 externalTxId 멱등으로 같은 실행의 중복 제출을 막는다. 자동 `FAILED` 종결은 자금 이동 필요성을 없애지 못하므로 하지 않으며 운영자는 실행 게이트 중지 뒤 원인을 조치한다 |

### bcm_swp_auth_m — sweep 승인 관찰 상태

토큰 컨트랙트의 allowance가 정본이고 이 테이블은 마지막 관찰과 승인 진행 상태를 보관한다. 배치 편입 직전에는 반드시 온체인을 다시 읽으며, `ACTIVE` 상태만 믿고 `transferFrom`을 제출하지 않는다.

```sql
CREATE TABLE bcm_swp_auth_m (
  acnt_id         VARCHAR(64)    NOT NULL,
  ntwk_cd         VARCHAR(20)    NOT NULL,
  tkn_smbl        VARCHAR(16)    NOT NULL,
  swp_ctrt_addr   VARCHAR(128)   NOT NULL,    -- 승인 대상 sweep 컨트랙트
  alwnc_cap       NUMERIC(36,18) NOT NULL,    -- 승인된 운영 상한 · 무제한 금지
  obs_alwnc       NUMERIC(36,18) NOT NULL,    -- 마지막 온체인 관찰 allowance
  auth_stcd       VARCHAR(16)    NOT NULL,    -- UNAPPROVED/APPROVING/ACTIVE/REVOKING/REVOKED/FAILED
  aprv_ext_tx_id  VARCHAR(128)   NULL,        -- 현재 approve 또는 approve(0) 제출 키
  aprv_vndr_tx_id VARCHAR(64)    NULL,
  last_chck_dttm  VARCHAR(16)    NOT NULL,
  frst_reg_empno  VARCHAR(6)     NOT NULL,
  frst_reg_brcd   VARCHAR(4)     NOT NULL,
  last_chng_empno VARCHAR(6)     NOT NULL,
  last_chng_brcd  VARCHAR(4)     NOT NULL,
  PRIMARY KEY (acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr)
);
```

- allowance가 예정 sweep 금액보다 작을 때만 approve를 준비한다. 제출 완료가 아니라 온체인 재조회로 `obs_alwnc`가 확인돼야 `ACTIVE`다.
- 0이 아닌 allowance를 새 cap으로 바꿀 때는 해당 vault·토큰에 active item이 없는 상태에서 `approve(0)`의 온체인 확정을 먼저 확인한다.
- `swp_ctrt_addr`를 PK에 포함한다. 컨트랙트 교체 중에는 구 컨트랙트 `REVOKING`과 신 컨트랙트 `APPROVING` 행이 함께 존재할 수 있고, 구 allowance가 0으로 관찰되기 전에는 행을 지우지 않는다.
- 활성 컨트랙트 지정과 `alwnc_cap` 변경은 일반 sweep 실행이 아니라 승인된 정책 변경이다. 한 고객·자산에서 batch 편입 가능한 `ACTIVE` 컨트랙트는 하나로 제한한다.
- `pause`는 allowance를 지우지 않는다. 긴급 회수는 `REVOKING` → `approve(sweeper, 0)` → 온체인 0 재확인 → `REVOKED` 순서다.

### bcm_swp_exec_l · bcm_swp_item_l — sweep 실행 1:N

최상위 Fireblocks batch 거래 하나와 원천 vault N개의 이동을 분리한다. 단건 실행도 항목이 하나인 같은 구조를 쓴다.

```sql
CREATE TABLE bcm_swp_exec_l (
  swp_exec_id      VARCHAR(36)    PRIMARY KEY,
  ext_tx_id        VARCHAR(128)   NOT NULL UNIQUE, -- swp- + UUID v7
  req_hash         CHAR(64)       NOT NULL,        -- 정렬된 항목 포함 실행 의도 hash
  ntwk_cd          VARCHAR(20)    NOT NULL,
  tkn_smbl         VARCHAR(16)    NOT NULL,
  opr_acnt_id      VARCHAR(64)    NOT NULL,        -- batch 호출 운영 계정
  swp_ctrt_addr    VARCHAR(128)   NOT NULL,
  plcy_vrsn_id     VARCHAR(36)    NOT NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  plcy_snps_hash   CHAR(64)       NOT NULL,        -- 실행 직전 활성 policy binding snapshot
  ctrt_vrsn_id     VARCHAR(36)    NOT NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  ctrt_evdc_id     VARCHAR(36)    NOT NULL REFERENCES bcm_ctrt_evdc_l(evdc_id),
  swp_exec_stcd    VARCHAR(16)    NOT NULL,        -- READY/SUBMITTING/SUBMITTED/RECONCILING/COMPLETED/PARTIAL/FAILED
  item_cnt         INT            NOT NULL,
  req_tot_amt      NUMERIC(36,18) NOT NULL,
  actl_tot_amt     NUMERIC(36,18) NULL,
  gasless_yn       VARCHAR(1)     NOT NULL,
  vndr_tx_id       VARCHAR(64)    NULL UNIQUE,
  tx_hash          VARCHAR(128)   NULL,
  req_dttm         VARCHAR(16)    NOT NULL,
  fnsh_dttm        VARCHAR(16)    NULL,
  frst_reg_empno   VARCHAR(6)     NOT NULL,
  frst_reg_brcd    VARCHAR(4)     NOT NULL,
  last_chng_empno  VARCHAR(6)     NOT NULL,
  last_chng_brcd   VARCHAR(4)     NOT NULL
);

CREATE UNIQUE INDEX uk_bcm_swp_exec_operator_pending
  ON bcm_swp_exec_l (opr_acnt_id)
  WHERE swp_exec_stcd IN ('READY', 'SUBMITTING');

CREATE TABLE bcm_swp_item_l (
  swp_exec_id      VARCHAR(36)    NOT NULL,
  item_seq         INT            NOT NULL,
  swp_req_item_id  VARCHAR(36)    NOT NULL REFERENCES bcm_swp_req_item_l (swp_req_item_id),
  acnt_id          VARCHAR(64)    NOT NULL,        -- 원천 고객 계정
  src_addr         VARCHAR(128)   NOT NULL,        -- 실행 의도 시점 주소 snapshot
  req_amt          NUMERIC(36,18) NOT NULL,
  actl_amt         NUMERIC(36,18) NULL,
  swp_item_stcd    VARCHAR(16)    NOT NULL,        -- READY/SUCCEEDED/FAILED/RETRY
  fail_cd          VARCHAR(64)    NULL,
  log_idx          INT            NULL,            -- SweepLeg 이벤트 위치
  frst_reg_empno   VARCHAR(6)     NOT NULL,
  frst_reg_brcd    VARCHAR(4)     NOT NULL,
  last_chng_empno  VARCHAR(6)     NOT NULL,
  last_chng_brcd   VARCHAR(4)     NOT NULL,
  PRIMARY KEY (swp_exec_id, item_seq),
  UNIQUE (swp_exec_id, acnt_id),
  FOREIGN KEY (swp_exec_id) REFERENCES bcm_swp_exec_l (swp_exec_id)
);

ALTER TABLE bcm_swp_trgt
  ADD CONSTRAINT fk_bcm_swp_trgt_item
  FOREIGN KEY (actv_swp_exec_id, actv_item_seq)
  REFERENCES bcm_swp_item_l (swp_exec_id, item_seq);
```

- 실행과 모든 항목, `bcm_swp_trgt` claim은 한 DB 트랜잭션으로 선기록한다. calldata는 이 레코드에서만 만들고 Callback도 같은 실행 의도와 대조한다.
- `swp_req_item_id`는 DAW 요청 항목과 실제 실행 시도를 잇는다. 실패 후 재시도하면 같은 요청 항목을 가리키는 새 실행 항목이
  생기며 과거 실패 항목은 덮어쓰지 않는다.
- 같은 운영 계정의 `READY`·`SUBMITTING` 실행은 부분 UNIQUE 인덱스로 하나만 허용한다. 새 후보를 claim하기 전에 이 실행을 먼저 회수하며, 벤더 접수를 기록해 `SUBMITTED`가 된 뒤에만 다음 실행을 만들 수 있다.
- 항목은 EVM 원천 주소 20바이트 값 오름차순으로 정렬하고 `item_seq`를 1부터 부여한다. 같은 네트워크의 주소 중복은 허용하지 않는다.
- `req_hash`의 첫 판은 `batch-v1`이다. LF로 `BATCH_V1` · network · symbol · token contract 소문자 · sweep contract 소문자 · 표준 36자 UUID executionId 소문자를 한 줄씩 잇고, 이어서 각 항목을 `item_seq|원천주소 소문자|정규화 십진금액` 한 줄로 붙인 UTF-8 바이트의 SHA-256 소문자 hex다. 마지막 LF는 붙이지 않는다. 재시도에서 내용이 달라지면 같은 `ext_tx_id`를 쓰지 못한다.
- calldata는 [sweep 운영 ABI](06-sweep.md#운영-컨트랙트-abi)의 같은 순서를 쓰되 executionId는 UUID 원문 16바이트, 금액은 token decimals를 적용한 최소 단위 정수다. canonical hash의 금액은 DB와 같은 사람 단위 십진 정규화 값이라 ABI 단위와 섞지 않는다.
- 최상위 거래가 `COMPLETED`여도 곧바로 실행을 완료하지 않는다. `RECONCILING`에서 요청 N개와 network records·receipt의 `SweepLeg` 이벤트를 대조한 뒤 `COMPLETED` 또는 `PARTIAL`로 종결한다.
- 되돌려진 항목은 network records에 없을 수 있으므로 성공 레코드의 부재만으로 실패 사유를 추측하지 않는다. 컨트랙트 실패 이벤트와 실행 후 잔액을 함께 본다.
- 항목 상태가 바뀔 때 `sweep-events`용 outbox를 같은 트랜잭션에 적재한다. payload에는 `eventId`, `sweepRequestId`,
  `sweepItemId`, `executionId`, `txId`, `vendorTxId`, `txHash`, `accountId`, `network`, `symbol`, 요청·실제 금액,
  `chainStatus`, `itemOutcome`, 실패 코드를 싣는다. 아직 결정되지 않은 값은 `null`이며 `chainStatus=FINALIZED`와
  `itemOutcome=FAILED` 조합을 허용한다. 앞 실행이 잔액을 이미 모두 옮긴 후속 요청은 새 실행 항목을 만들지 않고 요청
  항목과 요청 상태를 같은 트랜잭션에서 완료하며 `chainStatus=NOT_SUBMITTED`,
  `itemOutcome=NO_SWEEP_REQUIRED`, 요청·실제 금액 `0`, 물리 거래 식별자 `null`인 이벤트를 적재한다. 이 경우 기존
  outbox의 non-null 내부 정렬 키에는 `sweepItemId`를 사용하지만 고객 payload에 가짜 거래 식별자를 만들지 않는다.

### bcm_boost_l — boost 이력

자동 boost의 **호출 전 intent와 결과**를 함께 저장한다. Admin 조회용 이력인 동시에, 벤더 호출 뒤 응답을 적기 전에 프로세스가 죽어도 externalTxId 조회로 회수하게 하는 correctness 원장이다.

```sql
CREATE TABLE bcm_boost_l (
  orig_tx_id      VARCHAR(64)  NOT NULL,      -- root 논리 거래 id = bcm_tx_l.vndr_tx_id
  try_seq         INT          NOT NULL,      -- root 안의 시도 순번
  ext_tx_id       VARCHAR(128) NOT NULL UNIQUE, -- RBF 제출 멱등 키 = bst- + UUID v7
  bst_stcd        VARCHAR(16)  NOT NULL,      -- REQUESTED / SUBMITTED / FAILED
  claim_id        VARCHAR(36)  NULL,          -- 제출 소유권 토큰
  claim_exp_dttm  VARCHAR(16)  NULL,          -- 만료 뒤 다른 실행자가 externalTxId 조회부터 수행
  rplc_tx_id      VARCHAR(64)  NOT NULL,      -- 이번 시도가 교체하는 물리 벤더 tx
  rplc_tx_hash    VARCHAR(128) NOT NULL,      -- replaceTxByHash에 넣은 값
  fee_lvl         VARCHAR(16)  NOT NULL,      -- 재현 가능한 fee 정책값(초기 범위 HIGH)
  gasless_yn      VARCHAR(1)   NOT NULL,      -- RBF 요청에 useGasless를 실었는지 Y/N
  new_tx_id       VARCHAR(64)  NULL UNIQUE,   -- 대체 벤더 tx — 응답·조회·웹훅으로 set-once
  req_dttm        VARCHAR(16)  NOT NULL,      -- intent 선기록 시각
  rsp_dttm        VARCHAR(16)  NULL,          -- 결과 확인 시각
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (orig_tx_id, try_seq),
  FOREIGN KEY (orig_tx_id) REFERENCES bcm_tx_l(vndr_tx_id)
);
CREATE INDEX idx_bcm_boost_open ON bcm_boost_l (bst_stcd, req_dttm);
```

| 규칙 | 내용 |
|---|---|
| 선기록 | `REQUESTED` 행과 claim을 먼저 커밋한 실행자만 DB 트랜잭션 밖에서 RBF를 호출한다 |
| 응답 유실 회수 | claim이 만료되면 같은 `ext_tx_id`로 벤더 조회부터 한다. 있으면 `SUBMITTED`와 `new_tx_id`를 기록하고, 없을 때만 저장된 교체 hash·fee·gasless 값과 원 제출 원장의 자금 이동 필드로 같은 요청을 재구성해 재제출한다 |
| 웹훅 선도착 | `ext_tx_id`로 boost 행을 찾아 빈 `new_tx_id`를 채운 뒤 root 거래에 접는다. 일반 `bcm_sbmt_l` 미등록 전송 경보로 보내지 않는다 |
| active 교체 | `new_tx_id` 확인과 같은 짧은 DB 트랜잭션에서 root `actv_tx_id`를 바꾸고 `tx_hash`를 NULL로 비운다. 그 뒤 active가 아닌 옛 거래의 지연·drop은 root를 움직이지 않는다. 다만 옛 거래가 먼저 채굴·COMPLETED됐다는 성공 증거는 승자로 채택한다 — 무시하면 실제 이체 성공을 놓친다 |
| 다단 boost | 다음 시도는 직전 active 거래를 `rplc_tx_id`로, 그 거래의 최신 hash를 `rplc_tx_hash`로 기록한다. 모든 행의 `orig_tx_id`는 최초 root로 같다 |
| 실패 | RBF 요청 자체의 실패는 고객 거래 실패가 아니다. intent를 `FAILED`로 닫고 active 물리 거래를 재조회한다. root는 현재 active 거래가 실제 종결됐고 살릴 대체 거래도 없을 때만 실패 전이를 낸다 |

### bcm_job_m — 주기 작업 상태

주기 작업(막힘 점검 · sweep 배치 · tx 대사 · 수수료 관측)별 한 행. 밖의 모니터링이 읽기 전용 계정으로 읽는 heartbeat 와, tx 대사의 대조 범위 이어붙임에 쓴다. `last_run_dttm`은 실제 실행 heartbeat지만 tx 대사의 `last_scs_dttm`은 마지막으로 완주한 안정화된 createdAt 창의 끝이므로 두 값이 다를 수 있다. 첫 실행은 현재 안정화 창 끝에서 운영 설정의 초기 lookback만큼 이전부터 시작한다.

```sql
CREATE TABLE bcm_job_m (
  job_nm         VARCHAR(64)  PRIMARY KEY,   -- 작업명
  last_run_dttm  VARCHAR(16)  NOT NULL,      -- 마지막 실행 일시 — heartbeat
  last_scs_dttm  VARCHAR(16)  NULL,          -- 마지막 성공 경계 — tx 대사는 안정화된 createdAt 창 끝
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
```

새 sweep 실행은 활성 policy binding과 활성 sweep contract binding을 같은 실행 문맥에서 재조회한다. 정책이 현재 배포 hard
ceiling 안이고 실행 설정과 같으며, contract의 최신 `VALID` evidence가 만료되지 않고 출시 게이트를 통과한 경우에만 위 네 snapshot
필드를 선기록한다. 제출 재시도는 저장한 version·snapshot·evidence가 현재 활성 문맥과 같은지 다시 확인하되, 이미 벤더에 접수된
실행의 상태 관찰과 대사는 정책 변경 뒤에도 계속한다.

### bcm_fee_qt_l — 수수료 견적 시계열

등록된 `(ntwk_cd, tkn_smbl)`의 Fireblocks assetId로 `GET /v1/estimate_network_fee`를 호출해 LOW·MEDIUM·HIGH를 한 행씩 저장한다. 응답 필드는 자산 유형에 따라 일부만 오므로 nullable이고, 적어도 하나는 있어야 한다. `vndr_ast_id`는 관측 당시 호출 대상을 남기는 이력값이라 자산 매핑을 FK로 묶지 않는다.

```sql
CREATE TABLE bcm_fee_qt_l (
  ntwk_cd          VARCHAR(20)    NOT NULL,
  tkn_smbl         VARCHAR(16)    NOT NULL,
  obs_dttm         VARCHAR(16)    NOT NULL,   -- 견적 관측 일시, UTC yyyyMMddHHmmss
  fee_lvl          VARCHAR(16)    NOT NULL,   -- LOW / MEDIUM / HIGH
  vndr_ast_id      VARCHAR(64)    NOT NULL,   -- 관측 당시 Fireblocks assetId
  fee_per_byte     NUMERIC(36,18) NULL,       -- UTXO 등 자산 유형별 응답
  gas_price        NUMERIC(36,18) NULL,       -- EVM gas price
  ntwk_fee         NUMERIC(36,18) NULL,       -- 자산 단위 network fee
  base_fee         NUMERIC(36,18) NULL,       -- EIP-1559 base fee
  priority_fee     NUMERIC(36,18) NULL,       -- EIP-1559 priority fee
  -- 감사 4컬럼
  frst_reg_empno   VARCHAR(6)     NOT NULL,
  frst_reg_brcd    VARCHAR(4)     NOT NULL,
  last_chng_empno  VARCHAR(6)     NOT NULL,
  last_chng_brcd   VARCHAR(4)     NOT NULL,
  PRIMARY KEY (ntwk_cd, tkn_smbl, obs_dttm, fee_lvl),
  CHECK (fee_lvl IN ('LOW', 'MEDIUM', 'HIGH')),
  CHECK (fee_per_byte IS NOT NULL OR gas_price IS NOT NULL OR ntwk_fee IS NOT NULL OR
         base_fee IS NOT NULL OR priority_fee IS NOT NULL)
);
CREATE INDEX idx_bcm_fee_qt_lookup
  ON bcm_fee_qt_l (ntwk_cd, tkn_smbl, fee_lvl, obs_dttm DESC);
```

일반 제출은 Fireblocks 기본값인 MEDIUM, boost는 `bcm_boost_l.fee_lvl`을 사용한다. 각각의 `req_dttm` 이하에서 가장 큰 `obs_dttm` 한 건을 찾고, 선행 관측이 없으면 대응하지 않는다. 이 관계는 조회 시점의 논리 대응이며 제출 행에 FK를 저장하지 않는다. 견적은 네트워크 가격 지표이므로 특정 거래의 예상 gas limit·총비용이나 COMPLETED 뒤의 실비를 대신하지 않는다.

### bcm_raw_tx_l — finalize 트랜잭션 원본

finalize 된 tx 의 벤더 원문을 일 배치로 장기 보관한다. 원본은 `bcm_whk_l` 에서 그 tx 의 마지막 COMPLETED 알림의 `payload`·`payload_hash`·`sign_vl` 세 값을 **그대로 옮긴다** — 벤더 재조회도, 해시 재계산도 없다.

```sql
CREATE TABLE bcm_raw_tx_l (
  base_dt        VARCHAR(8)   NOT NULL,   -- UTC 적재 기준일 = 파티션 키 (YYYYMMDD)
  vndr_tx_id     VARCHAR(64)  NOT NULL,   -- 벤더 tx id
  ext_tx_id      VARCHAR(128) NULL,       -- 출금 건 식별자 — 입금은 NULL
  tx_hash        VARCHAR(128) NULL,       -- 온체인 거래 해시
  addr           VARCHAR(128) NOT NULL,   -- 지갑(주소) 기준 조회 키 — 입금은 수취 주소, 출금은 출발 주소
  ntwk_cd        VARCHAR(20)  NOT NULL,   -- 네트워크 코드
  tkn_smbl       VARCHAR(16)  NOT NULL,   -- 토큰 심볼
  final_stcd     VARCHAR(16)  NOT NULL,   -- 도달한 최종 상태
  payload        TEXT         NOT NULL,   -- 벤더 응답 원문 — 받은 바이트 그대로, 가공 금지
  payload_hash   CHAR(64)     NOT NULL,   -- 원문 바이트의 SHA-256 — 무결성 증명. bcm_whk_l 의 값을 복사한다(재계산 금지 —
                                          --  수신 시점의 와이어 바이트로 계산된 값이어야 한다)
  sign_vl        TEXT         NOT NULL,   -- 수신 서명 헤더 원문 — bcm_whk_l 에서 함께 옮긴다. 벤더 발신 증명의 나머지 절반
  rcv_dttm       VARCHAR(16)  NOT NULL,   -- 원문을 받은 일시
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (base_dt, vndr_tx_id)
) PARTITION BY RANGE (base_dt);           -- 월 단위 파티션 — 대상 월 전에 배포 역할이 선생성
CREATE INDEX idx_bcm_raw_tx_hash ON bcm_raw_tx_l (tx_hash);        -- 분쟁·역추적 — 이 온체인 tx 의 원본
CREATE INDEX idx_bcm_raw_tx_addr ON bcm_raw_tx_l (addr, base_dt);  -- 지갑(주소) 기준 기간 조회 — 선두가 주소라 균등 분산
CREATE INDEX idx_bcm_raw_tx_vendor ON bcm_raw_tx_l (vndr_tx_id, rcv_dttm); -- 같은 벤더 tx 의 더 최신 COMPLETED 원본 판별
```

`payload` 는 바이트 그대로 보존해야 해 JSONB 가 아니라 TEXT 다(무결성 해시가 원문 바이트 기준). 이 표의 세 값은 모두 수신 시점에만 만들 수 있어 `bcm_whk_l` 이 정리되기 전에 옮겨야 한다 — 지나가면 소급해서 만들 수 없다.

월별 파티션은 **대상 월이 시작되기 전에 배포 역할이 선생성**한다. 애플리케이션 실행 역할은 DDL 권한 없이 이미 생성된 파티션에만 적재한다. 보관 후보는 성공 커서의 하한 없이 실행 경계 이전의 `S`·`vndr_cmpl_yn='Y'` 인박스 중 root 거래가 FINALIZED이고, `bcm_raw_tx_l`에 같은 tx의 동일하거나 더 최신 `rcv_dttm` 원문이 없는 행이다. 기본 500건씩 실행당 최대 20배치를 처리하고 각 배치를 별도 트랜잭션으로 커밋한다. 파티션 누락이나 중간 적재 실패가 나도 앞서 커밋한 보관분은 유지되며, 다음 실행이 같은 미보관 조건으로 멱등하게 이어받는다.

적격 후보가 더 없음을 확인한 뒤에만 별도 마지막 트랜잭션에서 보존 기간이 지난 처리 완료(`S`) 인박스를 정리하고 성공 heartbeat를 기록한다. `vndr_cmpl_yn='Y'`인 인박스는 root가 아직 FINALIZED가 아니어도 동일하거나 더 최신 `rcv_dttm` 원문이 보관되기 전까지 정리 대상에서 제외한다. 최대 배치에 도달해 적체를 완전히 비우지 못했거나 어느 배치든 실패하면 인박스 정리와 성공 heartbeat는 하지 않는다. 따라서 `last_scs_dttm`은 보관 완전성 커서가 아니라 성공 heartbeat일 뿐이다. 보존 연한·자체 RPC 로 체인 원문까지 보관할지는 미확정이다(아래 미확정 절).

## Phase 10 Admin 원장 물리 설계 (2026-08-17 확정)

[Admin](08-bcm-admin.md)의 변경·승인·활성화는 **불변 원장 + 현재 binding projection**으로 구현한다. version·evidence·요청·판단·action은
추가 전용이고, 현재 binding만 scope별 단일 행을 잠가 교체한다. binding 변경 전후와 외부 관찰은 action 원장에 남으므로 과거 상태를 재현할 수 있다.
밴드S input snapshot·proposal·item·execution·event도 추가 전용으로 보존하고 현재 상태는 사실 원장에서 파생한다.

### 밴드S input snapshot·proposal·실행 원장

밴드S 금액과 비율은 DAW-CORE가 계산한다. BCM은 DAW-CORE가 보낸 canonical payload의 hash, 정책 version, 기준·만료 시각,
입력 완전성, proposal-item 합계와 실행 경계만 검증한다. BCM이 환율·NAV·총자산·이동량을 다시 계산하거나 누락값을 보정하지 않는다.

```sql
CREATE TABLE bcm_bnds_snps_l (
  snps_id             VARCHAR(36)   PRIMARY KEY,
  src_req_id          VARCHAR(128)  NOT NULL UNIQUE,
  plcy_vrsn_id        VARCHAR(36)   NOT NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  snps_hash           VARCHAR(64)   NOT NULL UNIQUE,
  input_hash          VARCHAR(64)   NOT NULL,
  base_dttm           VARCHAR(16)   NOT NULL,
  expr_dttm           VARCHAR(16)   NOT NULL,
  input_cmplt_yn      VARCHAR(1)    NOT NULL,
  total_ast_krw_amt   NUMERIC       NOT NULL,
  obs_hot_krw_amt     NUMERIC       NOT NULL,
  obs_cold_krw_amt    NUMERIC       NOT NULL,
  efct_hot_krw_amt    NUMERIC       NOT NULL,
  hot_ratio           NUMERIC       NOT NULL,
  low_ratio           NUMERIC       NOT NULL,
  trgt_ratio          NUMERIC       NOT NULL,
  up_ratio            NUMERIC       NOT NULL,
  input_payload       JSONB         NOT NULL,
  issue_payload       JSONB         NOT NULL,
  reg_dttm            VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ck_bcm_bnds_snps_yn CHECK (input_cmplt_yn IN ('Y','N')),
  CONSTRAINT ck_bcm_bnds_snps_hash CHECK (snps_hash ~ '^[0-9a-f]{64}$' AND input_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bcm_bnds_snps_time CHECK (base_dttm < expr_dttm),
  CONSTRAINT ck_bcm_bnds_snps_amount CHECK (
    total_ast_krw_amt >= 0 AND obs_hot_krw_amt >= 0 AND obs_cold_krw_amt >= 0 AND efct_hot_krw_amt >= 0
  ),
  CONSTRAINT ck_bcm_bnds_snps_ratio CHECK (
    low_ratio >= 0 AND low_ratio < trgt_ratio AND trgt_ratio < up_ratio AND up_ratio <= 100 AND hot_ratio >= 0
  )
);

CREATE TABLE bcm_bnds_prop_l (
  prop_id             VARCHAR(36)   PRIMARY KEY,
  src_prop_id         VARCHAR(128)  NOT NULL UNIQUE,
  snps_id             VARCHAR(36)   NOT NULL REFERENCES bcm_bnds_snps_l(snps_id),
  plcy_vrsn_id        VARCHAR(36)   NOT NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  drct_dvcd           VARCHAR(16)   NOT NULL,
  prop_hash           VARCHAR(64)   NOT NULL UNIQUE,
  input_hash          VARCHAR(64)   NOT NULL,
  item_cnt            INTEGER       NOT NULL,
  total_krw_amt       NUMERIC       NOT NULL,
  aft_hot_ratio       NUMERIC       NOT NULL,
  exec_able_yn        VARCHAR(1)    NOT NULL,
  prop_payload        JSONB         NOT NULL,
  block_payload       JSONB         NOT NULL,
  reg_dttm            VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ck_bcm_bnds_prop_direction CHECK (drct_dvcd IN ('HOT_TO_COLD','COLD_TO_HOT')),
  CONSTRAINT ck_bcm_bnds_prop_hash CHECK (prop_hash ~ '^[0-9a-f]{64}$' AND input_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bcm_bnds_prop_yn CHECK (exec_able_yn IN ('Y','N')),
  CONSTRAINT ck_bcm_bnds_prop_amount CHECK (item_cnt > 0 AND total_krw_amt > 0 AND aft_hot_ratio >= 0)
);

CREATE TABLE bcm_bnds_prop_item_l (
  prop_id             VARCHAR(36)   NOT NULL REFERENCES bcm_bnds_prop_l(prop_id),
  item_seq            INTEGER       NOT NULL,
  dep_item_seq        INTEGER       NULL,
  leg_dvcd            VARCHAR(24)   NOT NULL,
  ntwk_cd             VARCHAR(16)   NOT NULL,
  tkn_smbl            VARCHAR(16)   NOT NULL,
  src_vlt_id          VARCHAR(64)   NULL,
  dst_vlt_id          VARCHAR(64)   NULL,
  dst_addr            VARCHAR(128)  NULL,
  amt                  NUMERIC       NOT NULL,
  krw_amt              NUMERIC       NOT NULL,
  exp_fee_amt          NUMERIC       NOT NULL,
  item_hash            VARCHAR(64)   NOT NULL,
  exec_able_yn         VARCHAR(1)    NOT NULL,
  block_rsn_cd         VARCHAR(64)   NULL,
  frst_reg_empno       VARCHAR(6)    NOT NULL,
  frst_reg_brcd        VARCHAR(4)    NOT NULL,
  last_chng_empno      VARCHAR(6)    NOT NULL,
  last_chng_brcd       VARCHAR(4)    NOT NULL,
  PRIMARY KEY (prop_id, item_seq),
  CONSTRAINT fk_bcm_bnds_prop_item_dep FOREIGN KEY (prop_id, dep_item_seq)
    REFERENCES bcm_bnds_prop_item_l(prop_id, item_seq),
  CONSTRAINT ux_bcm_bnds_prop_item_hash UNIQUE (prop_id, item_hash),
  CONSTRAINT ck_bcm_bnds_prop_item_leg CHECK (
    (leg_dvcd = 'INTERNAL_TO_EGRESS' AND src_vlt_id IS NOT NULL AND dst_vlt_id IS NOT NULL AND dst_addr IS NULL) OR
    (leg_dvcd = 'EXTERNAL_COLD' AND src_vlt_id IS NOT NULL AND dst_vlt_id IS NULL AND dst_addr IS NOT NULL) OR
    (leg_dvcd = 'COLD_DEPOSIT' AND src_vlt_id IS NULL AND dst_vlt_id IS NOT NULL AND dst_addr IS NULL) OR
    (leg_dvcd = 'HOT_REDISTRIBUTE' AND src_vlt_id IS NOT NULL AND dst_vlt_id IS NOT NULL AND dst_addr IS NULL)
  ),
  CONSTRAINT ck_bcm_bnds_prop_item_hash CHECK (item_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bcm_bnds_prop_item_yn CHECK (exec_able_yn IN ('Y','N')),
  CONSTRAINT ck_bcm_bnds_prop_item_amount CHECK (item_seq > 0 AND amt > 0 AND krw_amt > 0 AND exp_fee_amt >= 0)
);

CREATE TABLE bcm_bnds_exec_l (
  exec_id             VARCHAR(36)   PRIMARY KEY,
  req_id              VARCHAR(36)   NOT NULL UNIQUE REFERENCES bcm_chng_req_l(req_id),
  prop_id             VARCHAR(36)   NOT NULL UNIQUE REFERENCES bcm_bnds_prop_l(prop_id),
  snps_id             VARCHAR(36)   NOT NULL REFERENCES bcm_bnds_snps_l(snps_id),
  plcy_vrsn_id        VARCHAR(36)   NOT NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  prop_hash           VARCHAR(64)   NOT NULL,
  input_hash          VARCHAR(64)   NOT NULL,
  idmp_key            VARCHAR(128)  NOT NULL,
  exec_hash           VARCHAR(64)   NOT NULL,
  rsv_dttm            VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_bnds_exec_idmp UNIQUE (req_id, idmp_key),
  CONSTRAINT ck_bcm_bnds_exec_hash CHECK (
    prop_hash ~ '^[0-9a-f]{64}$' AND input_hash ~ '^[0-9a-f]{64}$' AND exec_hash ~ '^[0-9a-f]{64}$'
  )
);

CREATE TABLE bcm_bnds_exec_item_key (
  exec_id             VARCHAR(36)  NOT NULL REFERENCES bcm_bnds_exec_l(exec_id),
  item_seq            INTEGER      NOT NULL,
  prop_id             VARCHAR(36)  NOT NULL,
  frst_reg_empno      VARCHAR(6)   NOT NULL,
  frst_reg_brcd       VARCHAR(4)   NOT NULL,
  last_chng_empno     VARCHAR(6)   NOT NULL,
  last_chng_brcd      VARCHAR(4)   NOT NULL,
  PRIMARY KEY (exec_id, item_seq),
  CONSTRAINT fk_bcm_bnds_exec_item_prop FOREIGN KEY (prop_id, item_seq)
    REFERENCES bcm_bnds_prop_item_l(prop_id, item_seq),
  CONSTRAINT ux_bcm_bnds_exec_item_prop UNIQUE (exec_id, prop_id, item_seq)
);

CREATE TABLE bcm_bnds_exec_evt_l (
  exec_id             VARCHAR(36)   NOT NULL REFERENCES bcm_bnds_exec_l(exec_id),
  item_seq            INTEGER       NOT NULL,
  evt_seq             INTEGER       NOT NULL,
  exec_stcd           VARCHAR(24)   NOT NULL,
  ext_tx_id           VARCHAR(128)  NULL,
  vndr_tx_id          VARCHAR(64)   NULL,
  obs_payload         JSONB         NOT NULL,
  obs_hash            VARCHAR(64)   NOT NULL,
  occr_dttm           VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  PRIMARY KEY (exec_id, item_seq, evt_seq),
  CONSTRAINT fk_bcm_bnds_exec_evt_item FOREIGN KEY (exec_id, item_seq)
    REFERENCES bcm_bnds_exec_item_key(exec_id, item_seq),
  CONSTRAINT ck_bcm_bnds_exec_evt_state CHECK (
    exec_stcd IN ('RESERVED','SUBMIT_INTENT','SUBMITTED','FINALIZED','FAILED','RECONCILED','RELEASED')
  ),
  CONSTRAINT ck_bcm_bnds_exec_evt_hash CHECK (obs_hash ~ '^[0-9a-f]{64}$')
);
CREATE UNIQUE INDEX ux_bcm_bnds_exec_evt_ext ON bcm_bnds_exec_evt_l(ext_tx_id) WHERE ext_tx_id IS NOT NULL;
CREATE UNIQUE INDEX ux_bcm_bnds_exec_evt_vndr ON bcm_bnds_exec_evt_l(vndr_tx_id) WHERE vndr_tx_id IS NOT NULL;
```

`bcm_bnds_exec_evt_l`의 item FK를 고정하기 위해 마이그레이션은 `bcm_bnds_exec_l`과 proposal item을 결합한
`bcm_bnds_exec_item_key(exec_id, item_seq)` 불변 키 테이블을 함께 만든다. 실행 transaction은 승인된 proposal의 모든 item key와
`RESERVED` event를 한 번에 추가한다. reservation은 최신 event가 `RESERVED/SUBMIT_INTENT/SUBMITTED/FINALIZED`인 동안 유효하고,
`RECONCILED/FAILED/RELEASED`에서 끝난다. DAW-CORE의 다음 snapshot은 이 활성 reservation을 입력에 한 번만 포함한다.

snapshot·proposal·item·execution·item key·event에는 공통 append-only trigger를 건다. proposal 요청은 `bcm_chng_req_l.tgt_dvcd='BAND_S'`,
`risk_dvcd='FUND'`, `aft_bnds_prop_id`로 연결하고 요청자 외 독립 승인자 1명을 요구한다. 실행 직전에는 snapshot 만료·완전성,
정책/binding version, proposal/input hash, 목적지 allowlist와 외부 cold 출구(옴니버스), item dependency를 다시 확인한다.
`COLD_TO_HOT`의 `COLD_DEPOSIT`은 Admin이 제출하지 않고 외부 입금 `FINALIZED` 관찰만 기록하며 이후 `HOT_REDISTRIBUTE`를 연다.

현재 상태는 event의 `(exec_id,item_seq)`별 가장 큰 `evt_seq`로 파생한다. 전 item이 `RECONCILED`면 `COMPLETED`, 일부가
`RECONCILED`이고 일부가 `FAILED/RELEASED`면 `PARTIAL`, 전부 `FAILED/RELEASED`면 `FAILED`, 그 외에는 `EXECUTING`이다.
최상위 벤더 거래 `FINALIZED`만으로 완료 처리하지 않고 완료 후 DAW-CORE 잔액 대사 증적을 받은 `RECONCILED`에서 예약을 해제한다.

### 상태코드와 현재 상태 파생

원장 행의 내용을 상태 변경으로 덮어쓰지 않는다. 화면·API 상태는 아래 사실로 파생한다.

| 대상 | API 상태 | 파생 근거 |
|---|---|---|
| 컨트랙트 | `CANDIDATE` | version만 있고 유효 evidence가 없음 |
| 컨트랙트 | `VERIFIED` | 만료되지 않은 `VALID` evidence가 있고 현재 binding은 아님 |
| 컨트랙트 | `ACTIVE` | `bcm_ctrt_bind_m.actv_ctrt_vrsn_id`가 해당 version을 가리킴 |
| 컨트랙트 | `PAUSED`·`RETIRED` | T10.6의 성공 action과 최신 온체인 재조회로 파생. `PAUSED`는 binding 해제를 뜻하지 않음 |
| 정책 | `DRAFT` | version만 있고 변경 요청이 없음 |
| 정책 | `IN_REVIEW` | 유효한 요청이 있고 정족수 미충족 |
| 정책 | `APPROVED` | 같은 snapshot의 승인 정족수 충족, 아직 binding 전 |
| 정책 | `ACTIVE` | `bcm_plcy_bind_m.actv_plcy_vrsn_id`가 해당 version을 가리킴 |
| 정책 | `SUPERSEDED` | 과거 성공 activation이 있으나 현재 binding이 다른 version을 가리킴 |
| 변경 요청 | `PENDING`·`APPROVED`·`REJECTED`·`EXPIRED`·`CANCELLED`·`ACTIVATED` | 판단·만료시각·성공 action에서 우선순위로 파생 |
| 실행 게이트 | `OPEN`·`STOPPED` | `(network, gate type)`별 최신 `STOPPED/RESUMED` event에서 파생. event가 없거나 최신이 `RESUMED`면 `OPEN` |
| 밴드S proposal | `BLOCKED` | snapshot 누락·만료·불완전, 정책 불일치, 차단 item 중 하나 이상 |
| 밴드S proposal | `PENDING`·`APPROVED`·`REJECTED`·`EXPIRED` | `BAND_S` 변경 요청의 판단·만료시각에서 파생 |
| 밴드S 실행 | `EXECUTING`·`PARTIAL`·`COMPLETED`·`FAILED` | item별 최신 execution event 조합에서 파생 |

저장 상태코드는 `bcm_ctrt_evdc_l.evdc_stcd = VALID/INVALID/STALE/ERROR`,
`bcm_chng_dcsn_l.dcsn_dvcd = APPROVE/REJECT`, `bcm_adm_actn_l.actn_stcd = INTENT/SUCCEEDED/FAILED`로 닫는다.
위험 등급은 `GENERAL/SECURITY/RESUME/FUND`, action은 `ACTIVATE/CANCEL/PAUSE/RESUME/EXECUTE`이다.

### 불변 version·evidence 원장

```sql
CREATE TABLE bcm_ctrt_vrsn_l (
  ctrt_vrsn_id      VARCHAR(36)   PRIMARY KEY,
  ctrt_scope_id     VARCHAR(128)  NOT NULL,
  ntwk_cd           VARCHAR(16)   NOT NULL,
  use_dvcd          VARCHAR(16)   NOT NULL,
  vrsn              VARCHAR(32)   NOT NULL,
  ctrt_addr         VARCHAR(128)  NOT NULL,
  release_cmit      VARCHAR(64)   NOT NULL,
  artifact_hash     VARCHAR(64)   NOT NULL,
  abi_hash          VARCHAR(64)   NOT NULL,
  runtime_code_hash VARCHAR(64)   NOT NULL,
  deploy_tx_hash    VARCHAR(128)  NOT NULL,
  deploy_blck_no    NUMERIC(78,0) NOT NULL,
  immut_payload     JSONB         NOT NULL,
  immut_hash        VARCHAR(64)   NOT NULL,
  ceiling_payload   JSONB         NOT NULL,
  ceiling_hash      VARCHAR(64)   NOT NULL,
  release_uri       VARCHAR(512)  NOT NULL,
  reg_dttm          VARCHAR(16)   NOT NULL,
  frst_reg_empno    VARCHAR(6)    NOT NULL,
  frst_reg_brcd     VARCHAR(4)    NOT NULL,
  last_chng_empno   VARCHAR(6)    NOT NULL,
  last_chng_brcd    VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_ctrt_vrsn_scope UNIQUE (ctrt_scope_id, vrsn),
  CONSTRAINT ck_bcm_ctrt_scope CHECK (ctrt_scope_id = ntwk_cd || ':' || use_dvcd),
  CONSTRAINT ck_bcm_ctrt_hashes CHECK (
    artifact_hash ~ '^[0-9a-f]{64}$' AND abi_hash ~ '^[0-9a-f]{64}$' AND
    runtime_code_hash ~ '^[0-9a-f]{64}$' AND immut_hash ~ '^[0-9a-f]{64}$' AND ceiling_hash ~ '^[0-9a-f]{64}$'
  )
);

CREATE TABLE bcm_ctrt_evdc_l (
  evdc_id             VARCHAR(36)   PRIMARY KEY,
  ctrt_vrsn_id        VARCHAR(36)   NOT NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  snps_hash           VARCHAR(64)   NOT NULL UNIQUE,
  exp_chain_id        NUMERIC(20,0) NOT NULL,
  exp_code_hash       VARCHAR(64)   NOT NULL,
  exp_immut_hash      VARCHAR(64)   NOT NULL,
  pin_blck_no         NUMERIC(78,0) NOT NULL,
  rpc1_id             VARCHAR(64)   NOT NULL,
  rpc1_chain_id       NUMERIC(20,0) NULL,
  rpc1_code_hash      VARCHAR(64)   NULL,
  rpc1_immut_hash     VARCHAR(64)   NULL,
  rpc1_obs_dttm       VARCHAR(16)   NULL,
  rpc2_id             VARCHAR(64)   NOT NULL,
  rpc2_chain_id       NUMERIC(20,0) NULL,
  rpc2_code_hash      VARCHAR(64)   NULL,
  rpc2_immut_hash     VARCHAR(64)   NULL,
  rpc2_obs_dttm       VARCHAR(16)   NULL,
  tap_mtch_yn         VARCHAR(1)    NOT NULL,
  clbk_mtch_yn        VARCHAR(1)    NOT NULL,
  gasless_pass_yn     VARCHAR(1)    NOT NULL,
  audit_pass_yn       VARCHAR(1)    NOT NULL,
  revoke_drill_yn     VARCHAR(1)    NOT NULL,
  launch_gate_yn      VARCHAR(1)    NOT NULL,
  evdc_stcd           VARCHAR(16)   NOT NULL,
  obs_dttm            VARCHAR(16)   NOT NULL,
  vld_until_dttm      VARCHAR(16)   NOT NULL,
  doc_evdc            JSONB         NOT NULL,
  doc_evdc_hash       VARCHAR(64)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ck_bcm_ctrt_evdc_rpc CHECK (rpc1_id <> rpc2_id),
  CONSTRAINT ck_bcm_ctrt_evdc_state CHECK (evdc_stcd IN ('VALID','INVALID','STALE','ERROR')),
  CONSTRAINT ck_bcm_ctrt_evdc_yn CHECK (
    tap_mtch_yn IN ('Y','N') AND clbk_mtch_yn IN ('Y','N') AND gasless_pass_yn IN ('Y','N') AND
    audit_pass_yn IN ('Y','N') AND revoke_drill_yn IN ('Y','N') AND launch_gate_yn IN ('Y','N')
  ),
  CONSTRAINT ck_bcm_ctrt_evdc_valid CHECK (
    evdc_stcd <> 'VALID' OR (
      rpc1_chain_id = exp_chain_id AND rpc2_chain_id = exp_chain_id AND
      rpc1_code_hash = exp_code_hash AND rpc2_code_hash = exp_code_hash AND
      rpc1_immut_hash = exp_immut_hash AND rpc2_immut_hash = exp_immut_hash AND
      rpc1_obs_dttm IS NOT NULL AND rpc2_obs_dttm IS NOT NULL AND
      tap_mtch_yn = 'Y' AND clbk_mtch_yn = 'Y' AND gasless_pass_yn = 'Y' AND
      audit_pass_yn = 'Y' AND revoke_drill_yn = 'Y' AND launch_gate_yn = 'Y'
    )
  )
);

CREATE TABLE bcm_plcy_vrsn_l (
  plcy_vrsn_id      VARCHAR(36)   PRIMARY KEY,
  plcy_scope_id     VARCHAR(128)  NOT NULL,
  vrsn_no           INTEGER       NOT NULL,
  plcy_schm_vrsn    VARCHAR(16)   NOT NULL,
  base_plcy_vrsn_id VARCHAR(36)   NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  ctrt_vrsn_id      VARCHAR(36)   NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  plcy_payload      JSONB         NOT NULL,
  plcy_hash         VARCHAR(64)   NOT NULL,
  ceiling_snps      JSONB         NOT NULL,
  ceiling_hash      VARCHAR(64)   NOT NULL,
  ceiling_pass_yn   VARCHAR(1)    NOT NULL,
  reg_dttm          VARCHAR(16)   NOT NULL,
  frst_reg_empno    VARCHAR(6)    NOT NULL,
  frst_reg_brcd     VARCHAR(4)    NOT NULL,
  last_chng_empno   VARCHAR(6)    NOT NULL,
  last_chng_brcd    VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_plcy_vrsn_scope UNIQUE (plcy_scope_id, vrsn_no),
  CONSTRAINT ux_bcm_plcy_vrsn_hash UNIQUE (plcy_scope_id, plcy_hash),
  CONSTRAINT ck_bcm_plcy_ceiling CHECK (ceiling_pass_yn IN ('Y','N')),
  CONSTRAINT ck_bcm_plcy_hashes CHECK (plcy_hash ~ '^[0-9a-f]{64}$' AND ceiling_hash ~ '^[0-9a-f]{64}$')
);
```

RPC ID는 endpoint의 논리 식별자만 저장하며 URL·credential은 저장하지 않는다. `VALID` evidence는 같은 pinned block에서 서로 다른 두 RPC가
expected chainId·runtime code hash·불변값 hash와 모두 일치하고 TAP·Callback·Gasless·감사·회수 훈련·출시 게이트가 전부 통과한 경우뿐이다.
만료 판단은 `vld_until_dttm`을 현재 UTC와 비교하며 `VALID` 문자열만 믿지 않는다.

### 변경 요청·판단·action 원장

```sql
CREATE TABLE bcm_chng_req_l (
  req_id             VARCHAR(36)   PRIMARY KEY,
  tgt_dvcd           VARCHAR(16)   NOT NULL,
  scope_id           VARCHAR(128)  NOT NULL,
  bfr_ctrt_vrsn_id   VARCHAR(36)   NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  aft_ctrt_vrsn_id   VARCHAR(36)   NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  bfr_plcy_vrsn_id   VARCHAR(36)   NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  aft_plcy_vrsn_id   VARCHAR(36)   NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  aft_bnds_prop_id   VARCHAR(36)   NULL REFERENCES bcm_bnds_prop_l(prop_id),
  evdc_id            VARCHAR(36)   NULL REFERENCES bcm_ctrt_evdc_l(evdc_id),
  risk_dvcd          VARCHAR(16)   NOT NULL,
  base_bind_rvsn     BIGINT        NOT NULL,
  tgt_snps_hash      VARCHAR(64)   NOT NULL,
  diff_payload       JSONB         NOT NULL,
  diff_hash          VARCHAR(64)   NOT NULL,
  impact_payload     JSONB         NOT NULL,
  impact_hash        VARCHAR(64)   NOT NULL,
  req_rsn            VARCHAR(1000) NOT NULL,
  work_tckt          VARCHAR(128)  NOT NULL,
  idmp_key           VARCHAR(128)  NOT NULL,
  req_role_dvcd      VARCHAR(32)   NOT NULL,
  req_dttm           VARCHAR(16)   NOT NULL,
  expr_dttm          VARCHAR(16)   NOT NULL,
  frst_reg_empno     VARCHAR(6)    NOT NULL,
  frst_reg_brcd      VARCHAR(4)    NOT NULL,
  last_chng_empno    VARCHAR(6)    NOT NULL,
  last_chng_brcd     VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_chng_req_idmp UNIQUE (frst_reg_empno, idmp_key),
  CONSTRAINT ck_bcm_chng_req_target CHECK (
    (tgt_dvcd = 'CONTRACT' AND aft_ctrt_vrsn_id IS NOT NULL AND aft_plcy_vrsn_id IS NULL AND aft_bnds_prop_id IS NULL) OR
    (tgt_dvcd = 'POLICY' AND aft_plcy_vrsn_id IS NOT NULL AND aft_ctrt_vrsn_id IS NULL AND aft_bnds_prop_id IS NULL) OR
    (tgt_dvcd = 'BAND_S' AND aft_bnds_prop_id IS NOT NULL AND aft_ctrt_vrsn_id IS NULL AND aft_plcy_vrsn_id IS NULL)
  ),
  CONSTRAINT ck_bcm_chng_req_risk CHECK (risk_dvcd IN ('GENERAL','SECURITY','RESUME','FUND')),
  CONSTRAINT ck_bcm_chng_req_hashes CHECK (
    tgt_snps_hash ~ '^[0-9a-f]{64}$' AND diff_hash ~ '^[0-9a-f]{64}$' AND impact_hash ~ '^[0-9a-f]{64}$'
  )
);

CREATE TABLE bcm_chng_dcsn_l (
  req_id             VARCHAR(36)  NOT NULL REFERENCES bcm_chng_req_l(req_id),
  aprv_empno         VARCHAR(6)   NOT NULL,
  aprv_brcd          VARCHAR(4)   NOT NULL,
  aprv_role_dvcd     VARCHAR(32)  NOT NULL,
  dcsn_dvcd          VARCHAR(16)  NOT NULL,
  dcsn_snps_hash     VARCHAR(64)  NOT NULL,
  dcsn_opin          VARCHAR(1000) NULL,
  dcsn_dttm          VARCHAR(16)  NOT NULL,
  frst_reg_empno     VARCHAR(6)   NOT NULL,
  frst_reg_brcd      VARCHAR(4)   NOT NULL,
  last_chng_empno    VARCHAR(6)   NOT NULL,
  last_chng_brcd     VARCHAR(4)   NOT NULL,
  PRIMARY KEY (req_id, aprv_empno),
  CONSTRAINT ck_bcm_chng_dcsn CHECK (dcsn_dvcd IN ('APPROVE','REJECT')),
  CONSTRAINT ck_bcm_chng_aprv_role CHECK (aprv_role_dvcd IN ('BCM_APPROVER','BCM_SECURITY_APPROVER')),
  CONSTRAINT ck_bcm_chng_dcsn_actor CHECK (aprv_empno = frst_reg_empno AND aprv_brcd = frst_reg_brcd)
);

CREATE TABLE bcm_adm_actn_l (
  actn_id            VARCHAR(36)   PRIMARY KEY,
  corr_id            VARCHAR(36)   NOT NULL,
  req_id             VARCHAR(36)   NOT NULL REFERENCES bcm_chng_req_l(req_id),
  actn_dvcd          VARCHAR(16)   NOT NULL,
  actn_stcd          VARCHAR(16)   NOT NULL,
  try_seq            INTEGER       NOT NULL,
  idmp_key           VARCHAR(128)  NOT NULL,
  req_hash           VARCHAR(64)   NOT NULL,
  rsp_cd             VARCHAR(64)   NULL,
  rsp_hash           VARCHAR(64)   NULL,
  exp_state          JSONB         NOT NULL,
  exp_state_hash     VARCHAR(64)   NOT NULL,
  obs_state          JSONB         NULL,
  obs_state_hash     VARCHAR(64)   NULL,
  occr_dttm          VARCHAR(16)   NOT NULL,
  frst_reg_empno     VARCHAR(6)    NOT NULL,
  frst_reg_brcd      VARCHAR(4)    NOT NULL,
  last_chng_empno    VARCHAR(6)    NOT NULL,
  last_chng_brcd     VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_adm_actn_event UNIQUE (corr_id, try_seq, actn_stcd),
  CONSTRAINT ck_bcm_adm_actn_type CHECK (actn_dvcd IN ('ACTIVATE','CANCEL','PAUSE','RESUME','EXECUTE')),
  CONSTRAINT ck_bcm_adm_actn_state CHECK (actn_stcd IN ('INTENT','SUCCEEDED','FAILED'))
);
CREATE UNIQUE INDEX ux_bcm_adm_actn_idmp ON bcm_adm_actn_l(req_id, actn_dvcd, idmp_key) WHERE actn_stcd = 'INTENT';
```

`bcm_chng_dcsn_l` 입력 trigger는 요청자 자기 판단, 요청 snapshot과 다른 판단, 만료 뒤 승인, 같은 직원의 중복 판단을 거절한다.
거절이 한 건이라도 있으면 정족수와 무관하게 요청은 `REJECTED`다. 일반 변경·자금 실행은 요청자 외 승인자 1명,
보안 변경·재개는 서로 다른 승인자 2명과 그중 `BCM_SECURITY_APPROVER` 1명을 요구한다.

### bcm_exec_gate_evt_l — 신규 실행 중지 원장

네트워크별 출금·sweep·approve 신규 실행 중지는 승인 요청을 기다리지 않는 운영자 1명의 신속 경로다. 기존 승인 요청 FK가
필수인 `bcm_adm_actn_l`에 억지로 넣지 않고, 범위·사유·작업 티켓·작업자·시각을 별도 추가 전용 원장에 남긴다.

```sql
CREATE TABLE bcm_exec_gate_evt_l (
  gate_evt_id         VARCHAR(36)   PRIMARY KEY,
  ntwk_cd             VARCHAR(20)   NOT NULL REFERENCES bcm_blkc_m(ntwk_cd),
  gate_dvcd           VARCHAR(16)   NOT NULL,
  evt_seq             INTEGER       NOT NULL,
  gate_stcd           VARCHAR(16)   NOT NULL,
  req_rsn             VARCHAR(1000) NOT NULL,
  work_tckt           VARCHAR(128)  NOT NULL,
  idmp_key            VARCHAR(128)  NOT NULL,
  rsm_req_id          VARCHAR(36)   NULL REFERENCES bcm_chng_req_l(req_id),
  occr_dttm           VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_exec_gate_seq UNIQUE (ntwk_cd, gate_dvcd, evt_seq),
  CONSTRAINT ux_bcm_exec_gate_idmp UNIQUE (frst_reg_empno, idmp_key),
  CONSTRAINT ck_bcm_exec_gate_type CHECK (gate_dvcd IN ('WITHDRAWAL','SWEEP','APPROVE')),
  CONSTRAINT ck_bcm_exec_gate_state CHECK (gate_stcd IN ('STOPPED','RESUMED')),
  CONSTRAINT ck_bcm_exec_gate_resume CHECK (
    (gate_stcd = 'STOPPED' AND rsm_req_id IS NULL) OR
    (gate_stcd = 'RESUMED' AND rsm_req_id IS NOT NULL)
  ),
  CONSTRAINT ck_bcm_exec_gate_seq CHECK (evt_seq > 0),
  CONSTRAINT ck_bcm_exec_gate_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);
CREATE INDEX idx_bcm_exec_gate_current ON bcm_exec_gate_evt_l(ntwk_cd, gate_dvcd, evt_seq DESC);
```

- 현재 상태는 `(ntwk_cd, gate_dvcd)`별 최신 event로 파생한다. event sequence는 중지와 재개를 합쳐 1부터 연속이어야 한다.
  같은 멱등 키는 같은 event를 반환하고 동시 전이는 `ux_bcm_exec_gate_seq`가 한 행만 허용한다. UPDATE·DELETE는 Admin 추가 전용
  trigger로 거절한다.
- `WITHDRAWAL`은 새 출금 제출만 막고 내부이체·입금 감지·주소·잔액 조회·이미 선기록된 제출의 회수/추적은 유지한다.
  `SWEEP`은 새 batch 실행 생성을, `APPROVE`는 정상 allowance 확대 제출을 막는다. 비상 `approve(0)` 회수는 차단하지 않는다.
- `RESUMED`는 아래 재개 요청·재검사 원장, 최신 중지 event, 완료된 회수, 만료되지 않은 외부 재검사, 강화 정족수와
  `RESUME/INTENT`를 DB에서 모두 검증한 뒤 새 event로만 추가한다. 중지 행을 수정·삭제해 재개하지 않는다.

### bcm_ext_ctrl_evdc_l — 비상 외부 통제 관찰 증적

TAP batch 차단, sweep 컨트랙트 pause, 등록 운영자 제거는 BCM이 직접 변경하거나 서명하지 않는다. 운영자가 외부 경계에서
조치한 뒤 BCM은 TAP 관리면과 pinned block 기준의 독립 RPC 2곳을 새로 조회하고, 한 번의 관찰 시도를 추가 전용 snapshot으로 남긴다.

```sql
CREATE TABLE bcm_ext_ctrl_evdc_l (
  ext_ctrl_evdc_id    VARCHAR(36)   PRIMARY KEY,
  ntwk_cd             VARCHAR(20)   NOT NULL REFERENCES bcm_blkc_m(ntwk_cd),
  ctrt_vrsn_id        VARCHAR(36)   NOT NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  snps_hash           VARCHAR(64)   NOT NULL UNIQUE,
  tap_src_id          VARCHAR(64)   NOT NULL,
  tap_blck_yn         VARCHAR(1)    NULL,
  tap_obs_dttm        VARCHAR(16)   NULL,
  pin_blck_no         NUMERIC(78,0) NOT NULL,
  exp_oprtr_hash      VARCHAR(64)   NOT NULL,
  rpc1_id             VARCHAR(64)   NOT NULL,
  rpc1_blck_no        NUMERIC(78,0) NULL,
  rpc1_pause_yn       VARCHAR(1)    NULL,
  rpc1_oprtr_hash     VARCHAR(64)   NULL,
  rpc1_obs_dttm       VARCHAR(16)   NULL,
  rpc2_id             VARCHAR(64)   NOT NULL,
  rpc2_blck_no        NUMERIC(78,0) NULL,
  rpc2_pause_yn       VARCHAR(1)    NULL,
  rpc2_oprtr_hash     VARCHAR(64)   NULL,
  rpc2_obs_dttm       VARCHAR(16)   NULL,
  evdc_stcd           VARCHAR(16)   NOT NULL,
  issue_payload       JSONB         NOT NULL,
  issue_hash          VARCHAR(64)   NOT NULL,
  obs_dttm            VARCHAR(16)   NOT NULL,
  vld_until_dttm      VARCHAR(16)   NOT NULL,
  req_rsn             VARCHAR(1000) NOT NULL,
  work_tckt           VARCHAR(128)  NOT NULL,
  idmp_key            VARCHAR(128)  NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_ext_ctrl_evdc_idmp UNIQUE (frst_reg_empno, idmp_key),
  CONSTRAINT ck_bcm_ext_ctrl_evdc_rpc CHECK (rpc1_id <> rpc2_id),
  CONSTRAINT ck_bcm_ext_ctrl_evdc_state CHECK (
    evdc_stcd IN ('CONFIRMED','DRIFT','STALE','UNCONFIRMED','ERROR')
  ),
  CONSTRAINT ck_bcm_ext_ctrl_evdc_yn CHECK (
    (tap_blck_yn IS NULL OR tap_blck_yn IN ('Y','N')) AND
    (rpc1_pause_yn IS NULL OR rpc1_pause_yn IN ('Y','N')) AND
    (rpc2_pause_yn IS NULL OR rpc2_pause_yn IN ('Y','N'))
  ),
  CONSTRAINT ck_bcm_ext_ctrl_evdc_hashes CHECK (
    snps_hash ~ '^[0-9a-f]{64}$' AND exp_oprtr_hash ~ '^[0-9a-f]{64}$' AND
    (rpc1_oprtr_hash IS NULL OR rpc1_oprtr_hash ~ '^[0-9a-f]{64}$') AND
    (rpc2_oprtr_hash IS NULL OR rpc2_oprtr_hash ~ '^[0-9a-f]{64}$') AND
    issue_hash ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_bcm_ext_ctrl_evdc_confirmed CHECK (
    evdc_stcd <> 'CONFIRMED' OR (
      tap_blck_yn = 'Y' AND tap_obs_dttm IS NOT NULL AND
      rpc1_blck_no = pin_blck_no AND rpc2_blck_no = pin_blck_no AND
      rpc1_pause_yn = 'Y' AND rpc2_pause_yn = 'Y' AND
      rpc1_oprtr_hash = exp_oprtr_hash AND rpc2_oprtr_hash = exp_oprtr_hash AND
      rpc1_obs_dttm IS NOT NULL AND rpc2_obs_dttm IS NOT NULL
    )
  ),
  CONSTRAINT ck_bcm_ext_ctrl_evdc_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);
CREATE INDEX idx_bcm_ext_ctrl_evdc_current
  ON bcm_ext_ctrl_evdc_l(ntwk_cd, obs_dttm DESC);
```

- `tap_src_id`와 `rpc*_id`는 credential·URL이 아닌 논리 식별자만 저장한다. 운영자 집합도 원문 대신 정렬된 집합의 SHA-256을
  저장하며, 비상 제거의 기대값은 승인된 빈 집합 hash다.
- 상태는 서버가 계산한다. 외부 호출 오류가 있으면 `ERROR`, 응답이 하나라도 없으면 `UNCONFIRMED`, 만료되면 `STALE`,
  TAP이 열려 있거나 pause·pinned block·운영자 hash가 어긋나면 `DRIFT`, 전부 맞을 때만 `CONFIRMED`다.
- 실패·drift·미확인 시도도 삭제하지 않는다. 같은 작업자와 멱등 키의 재요청은 같은 증적을 반환하고, 새로 관찰하려면 새 멱등 키를 쓴다.
- 이 원장은 외부 조치를 요청하거나 서명하지 않는다. Admin HTTP/UI에는 조회만 열고 mutation은 인증·인가 경계가 완성될 때까지 열지 않는다.

### bcm_exec_gate_rsm_l · bcm_exec_gate_rsm_chk_l — 강화 재개 요청·직전 재검사 원장

재개 승인은 “원인이 해소됐다”는 체크박스가 아니다. 현재 중지 event, 활성 sweep contract와 최신 검증 evidence, 완료된 allowance
회수, 원인 분석 문서의 URI·SHA-256, 복구 뒤 기대 운영자 집합 hash를 하나의 불변 요청 snapshot으로 묶는다. 실제 재개 직전에는
TAP과 독립 RPC 2곳을 다시 읽어 별도 check event로 남긴다. 문서 본문·RPC URL·credential·운영자 원문은 저장하지 않는다.

```sql
CREATE TABLE bcm_exec_gate_rsm_l (
  rsm_id              VARCHAR(36)   PRIMARY KEY,
  ntwk_cd             VARCHAR(20)   NOT NULL REFERENCES bcm_blkc_m(ntwk_cd),
  gate_dvcd           VARCHAR(16)   NOT NULL,
  stop_gate_evt_id    VARCHAR(36)   NOT NULL REFERENCES bcm_exec_gate_evt_l(gate_evt_id),
  ctrt_vrsn_id        VARCHAR(36)   NOT NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  ctrt_bind_rvsn      BIGINT        NOT NULL,
  ctrt_evdc_id        VARCHAR(36)   NOT NULL REFERENCES bcm_ctrt_evdc_l(evdc_id),
  rvok_exec_id        VARCHAR(36)   NOT NULL REFERENCES bcm_alwnc_rvok_exec_l(rvok_exec_id),
  exp_oprtr_hash      VARCHAR(64)   NOT NULL,
  cause_evdc_uri      VARCHAR(512)  NOT NULL,
  cause_evdc_hash     VARCHAR(64)   NOT NULL,
  tgt_snps_hash       VARCHAR(64)   NOT NULL UNIQUE,
  reg_dttm            VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ck_bcm_exec_gate_rsm_type CHECK (gate_dvcd IN ('WITHDRAWAL','SWEEP','APPROVE')),
  CONSTRAINT ck_bcm_exec_gate_rsm_hash CHECK (
    exp_oprtr_hash ~ '^[0-9a-f]{64}$' AND cause_evdc_hash ~ '^[0-9a-f]{64}$' AND
    tgt_snps_hash ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_bcm_exec_gate_rsm_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);

CREATE TABLE bcm_exec_gate_rsm_chk_l (
  rsm_chk_id          VARCHAR(36)   PRIMARY KEY,
  rsm_id              VARCHAR(36)   NOT NULL REFERENCES bcm_exec_gate_rsm_l(rsm_id),
  chk_seq             INTEGER       NOT NULL,
  snps_hash           VARCHAR(64)   NOT NULL UNIQUE,
  tap_src_id          VARCHAR(64)   NOT NULL,
  tap_blck_yn         VARCHAR(1)    NULL,
  tap_obs_dttm        VARCHAR(16)   NULL,
  pin_blck_no         NUMERIC(78,0) NOT NULL,
  rpc1_id             VARCHAR(64)   NOT NULL,
  rpc1_blck_no        NUMERIC(78,0) NULL,
  rpc1_pause_yn       VARCHAR(1)    NULL,
  rpc1_oprtr_hash     VARCHAR(64)   NULL,
  rpc1_obs_dttm       VARCHAR(16)   NULL,
  rpc2_id             VARCHAR(64)   NOT NULL,
  rpc2_blck_no        NUMERIC(78,0) NULL,
  rpc2_pause_yn       VARCHAR(1)    NULL,
  rpc2_oprtr_hash     VARCHAR(64)   NULL,
  rpc2_obs_dttm       VARCHAR(16)   NULL,
  chk_stcd            VARCHAR(16)   NOT NULL,
  issue_payload       JSONB         NOT NULL,
  issue_hash          VARCHAR(64)   NOT NULL,
  obs_dttm            VARCHAR(16)   NOT NULL,
  vld_until_dttm      VARCHAR(16)   NOT NULL,
  idmp_key            VARCHAR(128)  NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_exec_gate_rsm_chk_seq UNIQUE (rsm_id, chk_seq),
  CONSTRAINT ux_bcm_exec_gate_rsm_chk_idmp UNIQUE (frst_reg_empno, idmp_key),
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_rpc CHECK (rpc1_id <> rpc2_id),
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_state CHECK (
    chk_stcd IN ('READY','DRIFT','STALE','UNCONFIRMED','ERROR')
  ),
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_ready CHECK (
    chk_stcd <> 'READY' OR (
      tap_blck_yn = 'N' AND tap_obs_dttm IS NOT NULL AND
      rpc1_blck_no = pin_blck_no AND rpc2_blck_no = pin_blck_no AND
      rpc1_pause_yn = 'N' AND rpc2_pause_yn = 'N' AND
      rpc1_obs_dttm IS NOT NULL AND rpc2_obs_dttm IS NOT NULL
    )
  )
);

ALTER TABLE bcm_chng_req_l ADD COLUMN aft_gate_rsm_id VARCHAR(36) NULL
  REFERENCES bcm_exec_gate_rsm_l(rsm_id);
ALTER TABLE bcm_chng_req_l DROP CONSTRAINT ck_bcm_chng_req_target;
ALTER TABLE bcm_chng_req_l ADD CONSTRAINT ck_bcm_chng_req_target CHECK (
  (tgt_dvcd = 'CONTRACT' AND aft_ctrt_vrsn_id IS NOT NULL AND aft_plcy_vrsn_id IS NULL AND
    aft_bnds_prop_id IS NULL AND aft_alwnc_rvok_id IS NULL AND aft_gate_rsm_id IS NULL) OR
  (tgt_dvcd = 'POLICY' AND aft_plcy_vrsn_id IS NOT NULL AND aft_ctrt_vrsn_id IS NULL AND
    aft_bnds_prop_id IS NULL AND aft_alwnc_rvok_id IS NULL AND aft_gate_rsm_id IS NULL) OR
  (tgt_dvcd = 'BAND_S' AND aft_bnds_prop_id IS NOT NULL AND aft_ctrt_vrsn_id IS NULL AND
    aft_plcy_vrsn_id IS NULL AND aft_alwnc_rvok_id IS NULL AND aft_gate_rsm_id IS NULL) OR
  (tgt_dvcd = 'ALLOWANCE_REVOKE' AND aft_alwnc_rvok_id IS NOT NULL AND aft_ctrt_vrsn_id IS NULL AND
    aft_plcy_vrsn_id IS NULL AND aft_bnds_prop_id IS NULL AND aft_gate_rsm_id IS NULL) OR
  (tgt_dvcd = 'EXECUTION_GATE' AND aft_gate_rsm_id IS NOT NULL AND aft_ctrt_vrsn_id IS NULL AND
    aft_plcy_vrsn_id IS NULL AND aft_bnds_prop_id IS NULL AND aft_alwnc_rvok_id IS NULL)
);
CREATE UNIQUE INDEX ux_bcm_chng_req_gate_rsm
  ON bcm_chng_req_l(aft_gate_rsm_id) WHERE aft_gate_rsm_id IS NOT NULL;
```

- 변경 요청은 `tgt_dvcd='EXECUTION_GATE'`, `risk_dvcd='RESUME'`, `scope_id=ntwk_cd || ':' || gate_dvcd`,
  `aft_gate_rsm_id`와 `tgt_snps_hash`가 재개 snapshot과 같아야 한다. 요청자는 `BCM_OPERATOR`, 승인자는 요청자와 다른 2명이며
  그중 1명 이상은 `BCM_SECURITY_APPROVER`여야 한다. 거절·만료·snapshot 변경 뒤 승인은 재사용하지 않는다.
- 재개 snapshot은 요청 시점의 최신 `STOPPED` event를 가리키고 활성 `SWEEP` contract version·binding revision과 그 version의
  최신 `VALID` contract evidence를 고정한다. allowance 회수는 같은 네트워크·contract이고 모든 항목 최신 event가
  `ZERO_CONFIRMED`인 실행만 참조한다. 원인 해소 문서는 승인된 문서 보관소 URI와 SHA-256만 저장한다.
- 실행 직전 check는 기대 운영자 hash를 요청에서 가져오고 TAP batch가 열렸으며 두 RPC가 같은 pinned block에서 pause 해제와
  같은 운영자 hash를 관찰할 때만 `READY`다. source 오류는 `ERROR`, 누락은 `UNCONFIRMED`, 값 불일치는 `DRIFT`, 만료는 `STALE`다.
- `RESUMED` 입력 trigger는 재개 요청이 현재 최신 중지와 같은지, contract binding/evidence와 회수 완료가 여전히 유효한지,
  최신 check가 `READY`이고 만료 전인지, 강화 정족수·`RESUME/INTENT`가 있는지 한 transaction에서 다시 검사한다.
  조건이 하나라도 바뀌면 새 snapshot과 승인을 요구한다. 재개 성공 뒤 action 결과와 event를 조회해 완료를 표시한다.
- 장애 훈련은 최소한 `중지 → 외부 통제 확인 → allowance 전량 회수 → 원인 증적·재개 요청 → 2인 승인(보안 1인 포함) →
  외부 정상화 재조회 → 재개`를 실제 PostgreSQL E2E로 검증한다. stale·drift·회수 미완료·단일 승인·자기 승인·동시 재개는
  모두 fail-closed여야 하며 훈련 통과 결과는 contract 출시 evidence의 `revoke_drill_yn` 근거가 된다.

### bcm_alwnc_rvok_exec_l · bcm_alwnc_rvok_item_l · bcm_alwnc_rvok_evt_l — allowance 전량 회수 원장

비상 회수는 `bcm_swp_auth_m`의 현재 상태를 순회하는 임시 작업이 아니다. 승인 전에 활성 sweep 컨트랙트와 allowance가 0보다 큰
vault 목록, 실제 제출에 필요한 주소·calldata hash를 불변 실행 snapshot으로 고정한다. 변경 요청은 이 snapshot을
`ALLOWANCE_REVOKE` 대상으로 승인하고, 실행 뒤에는 항목별 event만 추가한다.

```sql
CREATE TABLE bcm_alwnc_rvok_exec_l (
  rvok_exec_id        VARCHAR(36)   PRIMARY KEY,
  ctrt_vrsn_id        VARCHAR(36)   NOT NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  ctrt_bind_rvsn      BIGINT        NOT NULL,
  ntwk_cd             VARCHAR(20)   NOT NULL REFERENCES bcm_blkc_m(ntwk_cd),
  swp_ctrt_addr       VARCHAR(128)  NOT NULL,
  tgt_snps_hash       VARCHAR(64)   NOT NULL UNIQUE,
  item_cnt            INTEGER       NOT NULL,
  idmp_key            VARCHAR(128)  NOT NULL,
  reg_dttm            VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_alwnc_rvok_exec_idmp UNIQUE (frst_reg_empno, idmp_key),
  CONSTRAINT ck_bcm_alwnc_rvok_exec_hash CHECK (tgt_snps_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bcm_alwnc_rvok_exec_count CHECK (item_cnt > 0),
  CONSTRAINT ck_bcm_alwnc_rvok_exec_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);

CREATE TABLE bcm_alwnc_rvok_item_l (
  rvok_exec_id        VARCHAR(36)    NOT NULL REFERENCES bcm_alwnc_rvok_exec_l(rvok_exec_id),
  item_seq            INTEGER        NOT NULL,
  acnt_id             VARCHAR(64)     NOT NULL,
  ntwk_cd             VARCHAR(20)     NOT NULL,
  tkn_smbl            VARCHAR(16)     NOT NULL,
  swp_ctrt_addr       VARCHAR(128)    NOT NULL,
  src_vlt_id          VARCHAR(64)     NOT NULL,
  ownr_addr           VARCHAR(128)    NOT NULL,
  tkn_ctrt_addr       VARCHAR(128)    NOT NULL,
  bfr_obs_alwnc       NUMERIC(36,18)  NOT NULL,
  ext_tx_id           VARCHAR(128)    NOT NULL UNIQUE,
  req_hash            VARCHAR(64)     NOT NULL,
  frst_reg_empno      VARCHAR(6)      NOT NULL,
  frst_reg_brcd       VARCHAR(4)      NOT NULL,
  last_chng_empno     VARCHAR(6)      NOT NULL,
  last_chng_brcd      VARCHAR(4)      NOT NULL,
  PRIMARY KEY (rvok_exec_id, item_seq),
  CONSTRAINT fk_bcm_alwnc_rvok_auth FOREIGN KEY (acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr)
    REFERENCES bcm_swp_auth_m(acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr),
  CONSTRAINT ux_bcm_alwnc_rvok_item_target UNIQUE (
    rvok_exec_id, acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr
  ),
  CONSTRAINT ck_bcm_alwnc_rvok_item_seq CHECK (item_seq > 0),
  CONSTRAINT ck_bcm_alwnc_rvok_item_amount CHECK (bfr_obs_alwnc > 0),
  CONSTRAINT ck_bcm_alwnc_rvok_item_hash CHECK (req_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bcm_alwnc_rvok_item_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);

CREATE TABLE bcm_alwnc_rvok_evt_l (
  rvok_exec_id        VARCHAR(36)    NOT NULL,
  item_seq            INTEGER        NOT NULL,
  evt_seq             INTEGER        NOT NULL,
  rvok_stcd           VARCHAR(24)     NOT NULL,
  ext_tx_id           VARCHAR(128)    NULL,
  vndr_tx_id          VARCHAR(64)     NULL,
  obs_alwnc           NUMERIC(36,18)  NULL,
  obs_payload         JSONB           NOT NULL,
  obs_hash            VARCHAR(64)     NOT NULL,
  err_cd              VARCHAR(64)     NULL,
  occr_dttm           VARCHAR(16)     NOT NULL,
  frst_reg_empno      VARCHAR(6)      NOT NULL,
  frst_reg_brcd       VARCHAR(4)      NOT NULL,
  last_chng_empno     VARCHAR(6)      NOT NULL,
  last_chng_brcd      VARCHAR(4)      NOT NULL,
  PRIMARY KEY (rvok_exec_id, item_seq, evt_seq),
  CONSTRAINT fk_bcm_alwnc_rvok_evt_item FOREIGN KEY (rvok_exec_id, item_seq)
    REFERENCES bcm_alwnc_rvok_item_l(rvok_exec_id, item_seq),
  CONSTRAINT ck_bcm_alwnc_rvok_evt_state CHECK (
    rvok_stcd IN ('RESERVED','SUBMIT_INTENT','SUBMITTED','ZERO_CONFIRMED','FAILED')
  ),
  CONSTRAINT ck_bcm_alwnc_rvok_evt_hash CHECK (obs_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bcm_alwnc_rvok_evt_amount CHECK (obs_alwnc IS NULL OR obs_alwnc >= 0),
  CONSTRAINT ck_bcm_alwnc_rvok_evt_fields CHECK (
    (rvok_stcd = 'RESERVED' AND ext_tx_id IS NULL AND vndr_tx_id IS NULL AND obs_alwnc IS NULL AND err_cd IS NULL) OR
    (rvok_stcd = 'SUBMIT_INTENT' AND ext_tx_id IS NOT NULL AND vndr_tx_id IS NULL AND err_cd IS NULL) OR
    (rvok_stcd = 'SUBMITTED' AND ext_tx_id IS NOT NULL AND vndr_tx_id IS NOT NULL AND err_cd IS NULL) OR
    (rvok_stcd = 'ZERO_CONFIRMED' AND ext_tx_id IS NULL AND vndr_tx_id IS NULL AND obs_alwnc = 0 AND err_cd IS NULL) OR
    (rvok_stcd = 'FAILED' AND err_cd IS NOT NULL)
  ),
  CONSTRAINT ck_bcm_alwnc_rvok_evt_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);
CREATE INDEX idx_bcm_alwnc_rvok_evt_current
  ON bcm_alwnc_rvok_evt_l(rvok_exec_id, item_seq, evt_seq DESC);

ALTER TABLE bcm_chng_req_l ADD COLUMN aft_alwnc_rvok_id VARCHAR(36) NULL
  REFERENCES bcm_alwnc_rvok_exec_l(rvok_exec_id);
ALTER TABLE bcm_chng_req_l DROP CONSTRAINT ck_bcm_chng_req_target;
ALTER TABLE bcm_chng_req_l ADD CONSTRAINT ck_bcm_chng_req_target CHECK (
  (tgt_dvcd = 'CONTRACT' AND aft_ctrt_vrsn_id IS NOT NULL AND aft_plcy_vrsn_id IS NULL AND
    aft_bnds_prop_id IS NULL AND aft_alwnc_rvok_id IS NULL) OR
  (tgt_dvcd = 'POLICY' AND aft_plcy_vrsn_id IS NOT NULL AND aft_ctrt_vrsn_id IS NULL AND
    aft_bnds_prop_id IS NULL AND aft_alwnc_rvok_id IS NULL) OR
  (tgt_dvcd = 'BAND_S' AND aft_bnds_prop_id IS NOT NULL AND aft_ctrt_vrsn_id IS NULL AND
    aft_plcy_vrsn_id IS NULL AND aft_alwnc_rvok_id IS NULL) OR
  (tgt_dvcd = 'ALLOWANCE_REVOKE' AND aft_alwnc_rvok_id IS NOT NULL AND
    aft_ctrt_vrsn_id IS NULL AND aft_plcy_vrsn_id IS NULL AND aft_bnds_prop_id IS NULL)
);
CREATE UNIQUE INDEX ux_bcm_chng_req_alwnc_rvok
  ON bcm_chng_req_l(aft_alwnc_rvok_id) WHERE aft_alwnc_rvok_id IS NOT NULL;
```

- 실행 snapshot과 항목은 승인 전 한 transaction에서 추가하고 이후 UPDATE·DELETE를 금지한다. 항목은 `(accountId, network,
  symbol, sweepContract)` 오름차순으로 정렬하며 `item_seq`, `arv-` UUID v7 `ext_tx_id`, `approve(spender, 0)` calldata의
  `cc-v1` 요청 hash를 그때 한 번만 만든다. `item_cnt`와 `tgt_snps_hash`는 전체 항목과 활성 contract binding revision을 포함한다.
- 변경 요청은 `tgt_dvcd='ALLOWANCE_REVOKE'`, `risk_dvcd='FUND'`, `scope_id=ntwk_cd || ':SWEEP'`,
  `tgt_snps_hash=bcm_alwnc_rvok_exec_l.tgt_snps_hash`여야 한다. 요청자 외 독립 승인자 1명의 승인과 `EXECUTE/INTENT` action이
  있어야 첫 `RESERVED` event를 쓸 수 있다.
- 첫 예약과 각 `SUBMIT_INTENT` 직전에 활성 contract version·binding revision과 snapshot hash를 다시 대조하고, 해당 network·contract의
  `READY/SUBMITTING/SUBMITTED/RECONCILING` batch 및 대상 vault의 active item이 하나라도 있으면 진행하지 않는다.
- `SUBMIT_INTENT`와 기존 `bcm_sbmt_l(SWEEP_APPROVE)` REQUESTED를 벤더 호출 전에 같은 transaction으로 선기록한다. 응답 유실·재시도는
  같은 `ext_tx_id`와 저장된 요청 hash로 회수한다. `bcm_swp_auth_m`은 `REVOKING/REVOKED` 현재 projection일 뿐 승인 snapshot과
  부분 진행 이력을 대신하지 않는다.
- 항목 event는 `RESERVED → SUBMIT_INTENT → SUBMITTED → ZERO_CONFIRMED`가 기본이며, 실행 사이에 이미 0이 된 항목은
  `RESERVED → ZERO_CONFIRMED`, 실패 뒤 같은 제출 의도를 회수·재시도할 때는 `FAILED → SUBMIT_INTENT/SUBMITTED/ZERO_CONFIRMED`를
  허용한다. event의 `ext_tx_id`는 대상 행과 같아야 한다.
- 실행 상태는 event 최신값으로 계산한다. event가 없으면 `READY`, 시작했지만 0 확인이 없으면 `IN_PROGRESS`, 일부만 0이면
  `PARTIAL`, 모든 항목 최신 상태가 `ZERO_CONFIRMED`일 때만 `COMPLETED`다. 제출 성공이나 `bcm_swp_auth_m.REVOKED` 문자열만으로
  완료하지 않는다. 실행·항목·event는 공통 Admin append-only trigger로 보호한다.

### bcm_whk_rcvr_req_l · bcm_whk_rcvr_evt_l — 웹훅 복구 요청·호출 결과 원장

웹훅 구독 복구는 외부 호출을 먼저 하고 로그만 남기는 도구가 아니다. 운영 요청을 불변 row로 접수한 뒤 상태 조회·재활성화·
`resend_failed` 각각의 호출 intent와 구조화된 결과를 append-only event로 남긴다. 수신 원문 payload·서명·API credential은
이 원장에 저장하지 않는다.

```sql
CREATE TABLE bcm_whk_rcvr_req_l (
  rcvr_req_id         VARCHAR(36)   PRIMARY KEY,
  whk_id              VARCHAR(64)   NOT NULL,
  scope_dvcd          VARCHAR(24)   NOT NULL,
  req_evt_payload     JSONB         NOT NULL,
  req_evt_hash        VARCHAR(64)   NOT NULL,
  idmp_key            VARCHAR(128)  NOT NULL,
  req_rsn             VARCHAR(1000) NOT NULL,
  work_tckt           VARCHAR(128)  NOT NULL,
  req_dttm            VARCHAR(16)   NOT NULL,
  aprv_empno          VARCHAR(6)    NOT NULL,
  aprv_brcd           VARCHAR(4)    NOT NULL,
  aprv_dttm           VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_whk_rcvr_req_idmp UNIQUE (frst_reg_empno, idmp_key),
  CONSTRAINT ck_bcm_whk_rcvr_req_scope CHECK (scope_dvcd = 'FAILED_LAST_24H'),
  CONSTRAINT ck_bcm_whk_rcvr_req_events CHECK (jsonb_typeof(req_evt_payload) = 'array'),
  CONSTRAINT ck_bcm_whk_rcvr_req_hash CHECK (req_evt_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bcm_whk_rcvr_req_independent_approval CHECK (aprv_empno <> frst_reg_empno),
  CONSTRAINT ck_bcm_whk_rcvr_req_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);

CREATE TABLE bcm_whk_rcvr_evt_l (
  rcvr_req_id         VARCHAR(36)   NOT NULL REFERENCES bcm_whk_rcvr_req_l(rcvr_req_id),
  evt_seq             INTEGER       NOT NULL,
  rcvr_stcd           VARCHAR(24)   NOT NULL,
  call_dvcd           VARCHAR(24)   NOT NULL,
  call_dttm           VARCHAR(16)   NOT NULL,
  rslt_dttm           VARCHAR(16)   NULL,
  whk_stcd            VARCHAR(16)   NULL,
  obs_evt_payload     JSONB         NULL,
  obs_evt_hash        VARCHAR(64)   NULL,
  scope_fr_dttm       VARCHAR(16)   NULL,
  scope_to_dttm       VARCHAR(16)   NULL,
  schd_noti_cnt       INTEGER       NULL,
  rsp_payload         JSONB         NULL,
  rsp_hash            VARCHAR(64)   NULL,
  err_cd              VARCHAR(64)   NULL,
  occr_dttm           VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  PRIMARY KEY (rcvr_req_id, evt_seq),
  CONSTRAINT ck_bcm_whk_rcvr_evt_seq CHECK (evt_seq > 0),
  CONSTRAINT ck_bcm_whk_rcvr_evt_state CHECK (
    rcvr_stcd IN ('STATUS_INTENT','STATUS_OBSERVED','ACTIVATE_INTENT','ACTIVATED',
      'RESEND_INTENT','RESEND_ACCEPTED','FAILED')
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_call CHECK (
    call_dvcd IN ('STATUS_QUERY','ACTIVATE','RESEND_FAILED')
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_whk_state CHECK (
    whk_stcd IS NULL OR whk_stcd IN ('DISABLED','ENABLED','SUSPENDED')
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_hashes CHECK (
    (obs_evt_hash IS NULL OR obs_evt_hash ~ '^[0-9a-f]{64}$') AND
    (rsp_hash IS NULL OR rsp_hash ~ '^[0-9a-f]{64}$')
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_scope CHECK (
    (scope_fr_dttm IS NULL AND scope_to_dttm IS NULL) OR
    (scope_fr_dttm IS NOT NULL AND scope_to_dttm IS NOT NULL AND scope_fr_dttm <= scope_to_dttm)
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_count CHECK (schd_noti_cnt IS NULL OR schd_noti_cnt >= 0),
  CONSTRAINT ck_bcm_whk_rcvr_evt_fields CHECK (
    (rcvr_stcd IN ('STATUS_INTENT','ACTIVATE_INTENT') AND rslt_dttm IS NULL AND whk_stcd IS NULL AND
      obs_evt_payload IS NULL AND obs_evt_hash IS NULL AND scope_fr_dttm IS NULL AND scope_to_dttm IS NULL AND
      schd_noti_cnt IS NULL AND rsp_payload IS NULL AND rsp_hash IS NULL AND err_cd IS NULL) OR
    (rcvr_stcd = 'RESEND_INTENT' AND rslt_dttm IS NULL AND whk_stcd IS NULL AND
      obs_evt_payload IS NULL AND obs_evt_hash IS NULL AND scope_fr_dttm IS NOT NULL AND scope_to_dttm IS NOT NULL AND
      schd_noti_cnt IS NULL AND rsp_payload IS NULL AND rsp_hash IS NULL AND err_cd IS NULL) OR
    (rcvr_stcd IN ('STATUS_OBSERVED','ACTIVATED') AND rslt_dttm IS NOT NULL AND whk_stcd IS NOT NULL AND
      obs_evt_payload IS NOT NULL AND obs_evt_hash IS NOT NULL AND scope_fr_dttm IS NULL AND scope_to_dttm IS NULL AND
      schd_noti_cnt IS NULL AND rsp_payload IS NOT NULL AND rsp_hash IS NOT NULL AND err_cd IS NULL) OR
    (rcvr_stcd = 'RESEND_ACCEPTED' AND rslt_dttm IS NOT NULL AND whk_stcd IS NULL AND
      obs_evt_payload IS NULL AND obs_evt_hash IS NULL AND scope_fr_dttm IS NOT NULL AND scope_to_dttm IS NOT NULL AND
      schd_noti_cnt IS NOT NULL AND rsp_payload IS NOT NULL AND rsp_hash IS NOT NULL AND err_cd IS NULL) OR
    (rcvr_stcd = 'FAILED' AND rslt_dttm IS NOT NULL AND schd_noti_cnt IS NULL AND
      rsp_payload IS NULL AND rsp_hash IS NULL AND err_cd IS NOT NULL)
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);
CREATE INDEX idx_bcm_whk_rcvr_evt_current
  ON bcm_whk_rcvr_evt_l(rcvr_req_id, evt_seq DESC);
```

- 요청은 `BCM_OPERATOR`가 만들고 요청자와 다른 `BCM_APPROVER` 1명의 승인을 받은 뒤 접수한다. 승인자·승인 시각은 요청과 함께
  불변 snapshot으로 남기며 DB도 요청자와 승인자가 같은 행을 거절한다. `(작업자, idmp_key)`가 같은 재요청은 같은 `rcvr_req_id`를
  반환한다. 필수 이벤트 집합은 정렬한
  JSON 배열과 SHA-256으로 snapshot해 뒤의 코드·설정 변경이 과거 요청의 범위를 바꾸지 않게 한다.
- 요청 row만 생긴 상태가 `ACCEPTED`다. 실행기는 `STATUS_INTENT → STATUS_OBSERVED`로 실제 webhook ID·상태·이벤트 집합을
  확인한다. 필수 이벤트가 하나라도 빠지면 `FAILED`로 끝내며 이벤트 범위를 자동 변경하지 않는다.
- 상태가 `DISABLED/SUSPENDED`면 `ACTIVATE_INTENT → ACTIVATED`를 추가하고 응답이 `ENABLED`인지 다시 확인한다. 이미
  `ENABLED`면 활성화 호출을 생략한다. 그 뒤에만 `RESEND_INTENT → RESEND_ACCEPTED`를 허용한다.
- 각 intent는 외부 호출 전에 commit한다. intent 뒤 대응 결과가 없으면 응답 유실 여부를 알 수 없으므로 자동 재호출하지 않고,
  서버가 timeout 경과 뒤 `AMBIGUOUS`로 계산한다. 정상 결과는 벤더 응답에서 필요한 필드만 정렬된 JSON과 SHA-256으로 남기며
  오류 응답 본문·예외 메시지는 저장하지 않고 안전한 `err_cd`만 남긴다.
- `RESEND_INTENT`의 `scope_fr_dttm/scope_to_dttm`은 실제 호출 시각 기준 최근 24시간을 기록한다. 이 값은 벤더 API에 보내는
  검색 조건이 아니라 endpoint가 처리한다고 문서화한 실패 알림 범위의 감사 snapshot이다. `RESEND_ACCEPTED.schd_noti_cnt`는
  응답 `total`이고 실제 재수신 완료 건수가 아니다.
- event sequence는 1부터 연속이며 위 순서만 허용한다. `FAILED`와 `RESEND_ACCEPTED`는 terminal이다. 요청·event는 공통 Admin
  append-only trigger로 UPDATE·DELETE를 거절한다.
- 화면 상태는 event 최신값과 intent timeout으로 계산한다. event 없음 `ACCEPTED`, 진행·결과 event `IN_PROGRESS`, 결과 없는
  오래된 intent `AMBIGUOUS`, `FAILED` event `FAILED`, `RESEND_ACCEPTED` `COMPLETED`다. 여기서 `COMPLETED`는 재전송 배차 접수 완료다.

### 현재 binding projection

```sql
CREATE TABLE bcm_ctrt_bind_m (
  ctrt_scope_id       VARCHAR(128) PRIMARY KEY,
  ntwk_cd             VARCHAR(16)  NOT NULL,
  use_dvcd            VARCHAR(16)  NOT NULL,
  actv_ctrt_vrsn_id   VARCHAR(36)  NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  bind_rvsn           BIGINT       NOT NULL DEFAULT 0,
  last_req_id         VARCHAR(36)  NULL REFERENCES bcm_chng_req_l(req_id),
  last_evdc_id        VARCHAR(36)  NULL REFERENCES bcm_ctrt_evdc_l(evdc_id),
  bind_snps_hash      VARCHAR(64)  NOT NULL,
  reg_dttm            VARCHAR(16)  NOT NULL,
  last_chng_dttm      VARCHAR(16)  NOT NULL,
  frst_reg_empno      VARCHAR(6)   NOT NULL,
  frst_reg_brcd       VARCHAR(4)   NOT NULL,
  last_chng_empno     VARCHAR(6)   NOT NULL,
  last_chng_brcd      VARCHAR(4)   NOT NULL,
  CONSTRAINT ck_bcm_ctrt_bind_scope CHECK (ctrt_scope_id = ntwk_cd || ':' || use_dvcd)
);

CREATE TABLE bcm_plcy_bind_m (
  plcy_scope_id       VARCHAR(128) PRIMARY KEY,
  actv_plcy_vrsn_id   VARCHAR(36)  NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  bind_rvsn           BIGINT       NOT NULL DEFAULT 0,
  last_req_id         VARCHAR(36)  NULL REFERENCES bcm_chng_req_l(req_id),
  bind_snps_hash      VARCHAR(64)  NOT NULL,
  reg_dttm            VARCHAR(16)  NOT NULL,
  last_chng_dttm      VARCHAR(16)  NOT NULL,
  frst_reg_empno      VARCHAR(6)   NOT NULL,
  frst_reg_brcd       VARCHAR(4)   NOT NULL,
  last_chng_empno     VARCHAR(6)   NOT NULL,
  last_chng_brcd      VARCHAR(4)   NOT NULL
);
```

활성화 transaction은 scope binding을 `SELECT ... FOR UPDATE`로 잠그고 `base_bind_rvsn` 일치, 대상 version·snapshot,
거절 부재, 정족수, hard ceiling, contract evidence 만료·drift를 다시 확인한다. 그 뒤 `INTENT` action 추가 → binding revision 1 증가 →
`SUCCEEDED` action 추가를 한 transaction으로 커밋한다. binding trigger도 같은 요청·revision·정족수·대상 version과
`bcm_plcy_vrsn_l.ceiling_pass_yn='Y'`, contract의 만료되지 않은 `VALID` evidence를 재검사해 우회 update를 막는다.
동시 활성화는 같은 scope 행 잠금과 revision 조건으로 하나만 성공한다. 응답 유실 재시도는 partial unique idempotency index와 성공 action을 재조회한다.

version·evidence·요청·판단·action 6개 원장에는 `UPDATE OR DELETE`를 거부하는 공통 trigger를 건다. 현재 binding 두 projection만
승인된 activation transaction에서 갱신할 수 있다. Admin 원장은 기능 테스트 단계부터 물리 삭제하지 않고 영구 보존하며,
향후 외부 감사 보관소 이관이 확정되면 새 마이그레이션과 검증된 archive 절차로만 보존 정책을 바꾼다.

`bcm_swp_exec_l`과 T10.5 밴드S 실행 원장은 적용 당시 `plcy_vrsn_id`와 immutable snapshot hash를 보존한다. `bcm_sbmt_l`·
`bcm_boost_l`의 실행 정책 snapshot은 각 실행 세로줄에서 별도 마이그레이션으로 추가한다.

## 미확정

- **`bcm_tx_l`·`bcm_whk_l`·`bcm_outbox_l` 보존** — 종결·처리 완료·발송 완료 건을 언제까지 두고 언제 정리할지 — `bcm_raw_tx_l` 원본 보관과 역할을 나눈 뒤 확정. 이는 [운영 로그 정책](11-operational-log-policy.md)의 중앙 로그 보존 기간과 별도로 결정한다.

## 확정 이력 (2026-08-31)

- **COMPLETED 원문은 판단 성공과 함께 표식** — application이 파싱한 status를 지원 transaction event의 `P→S` UPDATE에 전달해 `vndr_cmpl_yn`을 남기고 partial index로 미보관 원문을 선별한다. V17 expand·구버전 호환 trigger, 기존 데이터 DB의 NULL 임시 partial index, V18 batch backfill·concurrent archive index, V19 임시 index cleanup으로 롤링 전환하며, 60초 운영 메트릭과 일 보관 배치는 표식을 공유해 보존량에 비례한 payload JSON 반복 파싱을 피한다.

## 확정 이력 (2026-08-14)

- **DB 일시·일자는 UTC로 통일** — `_dttm`은 UTC `yyyyMMddHHmmss`, `_dt`·`base_dt`는 UTC `yyyyMMdd`로 저장한다. API는 ISO 8601 UTC를 쓰고 화면·정산·보고서에서만 필요한 시간대로 변환한다. 2026-08-06의 KST 저장 결정은 이 결정으로 대체한다.
- **tx 대사는 벤더 createdAt 단일 시간축** — 벤더 목록과 `bcm_tx_l.vndr_crt_dttm`을 안정화 지연이 지난 같은 닫힌 구간으로 비교한다. 창 밖 미결 단건 조회는 DB claim·영속 백오프·실행당 상한·최대 추적 나이를 둔다.
- **원본 보관은 커서 없는 미보관 재탐색** — 늦게 적격이 된 원문도 다시 찾고, 배치별로 안전하게 커밋한다. 전체 적체를 비운 실행만 인박스 정리와 성공 heartbeat를 남긴다.

## 확정 이력 (2026-08-13)

- **원본 월별 파티션은 배포 역할이 선생성** — 런타임 애플리케이션에 DDL 권한을 주지 않는다. 누락 시 해당 보관 배치를 실패시키고 인박스 정리와 성공 heartbeat를 남기지 않는다.
- **수수료 시계열은 자산별 network fee 관측** — 등록된 벤더 assetId의 LOW·MEDIUM·HIGH 응답을 `bcm_fee_qt_l`에 정규화한다. 일반 제출은 MEDIUM, boost는 저장된 fee level로 제출 시각 이하의 최근 관측을 논리 대응하며 특정 거래 시뮬레이션과 실비는 분리한다.

## 확정 이력 (2026-08-12)

- **막힘 판정은 벤더 최신 관찰 기준** — DB 공통 상태는 후보 선별에만 쓰고, `CONFIRMING`·tx hash 있음·0 confirmation을 RBF 직전에 단건 조회로 확인한다. `SUBMITTED=미채굴`로 간주하지 않는다.
- **boost도 crash-safe intent를 선기록** — `bcm_boost_l`에 별도 externalTxId·claim·교체 대상을 먼저 남기며, 물리 대체 거래는 최초 `bcm_tx_l` root 행으로 접는다. 원 거래와 대체 거래 중 먼저 채굴된 쪽이 승자다.
- **미결 제출 점검은 조회만 수행** — 오래된 `REQUESTED`의 벤더 거래가 확인되면 회수하고, 미발견·조회 실패는 체크포인트만 갱신한다. 전체 원 요청 문맥 없이 백그라운드에서 재제출하지 않는다.

## 확정 이력 (2026-08-07)

- **제출 원장 `bcm_sbmt_l` 신설** — `ext_tx_id` PK. 벤더를 부르기 전에 먼저 적어 멱등을 판정하고, 우리 vault 발신 웹훅의 계열을 `tx_dvcd` 로 가른다. `bcm_tx_l` 흡수는 키가 달라(PK = 벤더 tx id, 제출 시점 미상) 불가능하다.
- **canonical 요청 = 자금 이동 7값** — from 2 · to 2 · network · symbol · 정규화 amount. `note`·`travelRule` 은 제외(재시도 오탐 방지 + 개인정보 미보관). 요청 원문은 저장하지 않고 해시와 개별 필드만 남긴다.

## 확정 이력 (2026-08-06)

- **일시의 시간대 = KST(`Asia/Seoul`)** — 14자 포맷에 오프셋이 없어 한 시간대로 고정해야 한다는 초기 결정이었다. **2026-08-14에 `_dttm`·`_dt`·`base_dt` 모두 UTC 기준으로 대체했다.**
- **수신 원문은 바이트 그대로** — `bcm_whk_l.payload` 를 JSONB 에서 TEXT 로 바꾸고 `payload_hash`·`sign_vl` 을 수신 시점에 함께 남긴다. JSONB 정규화 때문에 기존 스키마로는 `bcm_raw_tx_l.payload_hash` 의 "수신 시점 와이어 바이트" 요구를 지킬 수 없었다.

## 확정 이력 (2026-08-05)

- **약어** — 이 문서 표기 그대로 확정(`bcm`·`vndr`·`vlt`·`noti`·`swp`·`bnds`(밴드S)·`sbmt`(제출)·`rcv`(받는 쪽)·`snd`(보내는 쪽) = 프로젝트 약어집). DAW-CORE DB 약어집이 나오면 대조해 어긋난 것만 조정한다.
- **감사 컬럼 센티넬** — 자동 처리 행은 `empno='SYSTEM'` · `brcd='9999'`(실존 부점과 충돌 불가한 값). 구현은 단일 상수로 관리해 코어 운영 규약 확인 시 한 곳만 바꾼다.
- **subStatus·networkStatus** — `bcm_tx_l` 에 보관(`vndr_sub_stcd`·`vndr_ntwk_stcd`). 운영 조사용 — 이벤트 미탑재는 유지.
