---
title: 블록체인 매니저 — DB
status: To Do
group: 블록체인 매니저
---

블록체인 매니저 DB(`bcm_`)의 테이블 전체 — 계정·주소 매핑, 거래 운영 상태, 수신 인박스, sweep 대상, 주기 작업, boost 이력, finalize 원본, 발행 아웃박스. 자산 매핑·블록체인 카탈로그 두 표는 [자산 매핑](07-asset-master.md) 에서 정의한다.
회계 진실(고객 원장·귀속·잔액·출금 지시 상태)은 여기 없다 — 그것은 DAW-CORE DB(`daw_`)다.

## 명명 규약

코어 DB(`daw_`) 규약을 그대로 따른다 — BC·컴플라이언스·코어가 한 규약을 쓴다.

- **접두** `bcm_` · **접미** `_m`(마스터) · `_l`(내역/로그) · `_trgt`(작업 대상)
- **컬럼 축약** `_stcd`(상태코드) · `_dvcd`(구분코드) · `_yn`(VARCHAR(1) Y/N) · `_cnt`(횟수) · `_dttm`(일시) · `_dt`(일자) · `_id` · payload(JSONB — 단 수신 바이트를 그대로 보존하는 `bcm_whk_l`·`bcm_raw_tx_l` 은 TEXT)
- **일시는 VARCHAR(16)** — 코어와 동일(TIMESTAMP 안 씀) · 값은 `yyyyMMddHHmmss` 14자 · **일자는 VARCHAR(8)** (YYYYMMDD)
- **일시·일자의 시간대는 KST(`Asia/Seoul`)** (2026-08-06 확정) — 포맷에 오프셋이 없어 값만 보고는 시간대를 알 수 없으니 시스템 전체가 하나로 고정돼야 한다. 일 배치·파티션 경계(`base_dt`)도 KST 로 자른다 — 영업일과 어긋나지 않게. 벤더가 주는 절대시각(epoch ms)은 저장 직전 한 곳에서 KST 로 바꾼다
- **id 길이** — 벤더가 주는 값(벤더 tx·vault·알림 id)은 잘리지 않게 VARCHAR(64) 유지. 코어 자체 id 는 16~36
- **자산은 두 컬럼** — `ntwk_cd`(네트워크코드 20자) + `tkn_smbl`(토큰심볼 16자). 코어 마스터의 컬럼명·크기를 따르되 **값은 우리가 정한다** — 코어 키(`tkn_id` 등)에 의존하지 않는다
- **감사 4컬럼** — 모든 테이블에 `frst_reg_empno`(6)·`frst_reg_brcd`(4)·`last_chng_empno`(6)·`last_chng_brcd`(4). 자동 처리 행은 시스템 센티넬 `empno='SYSTEM'`·`brcd='9999'`(2026-08-05 확정 — 구현은 단일 상수), Admin 수동 개입(수동 boost·동결 해제 등)은 실제 직원/부점. 행 발생·변경 "시각"은 별도 도메인 `_dttm` 이 담당한다(코어와 동일 분리)

## 테이블 한눈에

| 테이블 | 무엇을 저장하나 | 쓰는 곳 |
|---|---|---|
| `bcm_acnt_m` | 계정 매핑 — (계정유형, ref) ↔ vault | 계정 생성 · 모든 오퍼레이션의 계정 해석 |
| `bcm_addr_m` | 주소 매핑 — (계정, 네트워크, 토큰) ↔ 입금 주소 | 주소 발급·조회 · 입금 감지의 주소→계정 대응 |
| `bcm_whk_l` | 수신 웹훅 알림 원본 — 인박스 | 수신부 적재 → 판단 워커 집기 · finalize 원본의 출처 |
| `bcm_tx_l` | 거래 운영 상태 — 감지·발행 추적 | 판단 워커 → 발행 예약 · 막힘 점검 · 제출 중복 차단 |
| `bcm_outbox_l` | 발행 대기 이벤트 — 상태 변경과 원자 기록 | 워커가 같은 트랜잭션에 적재 → relay 가 발송 |
| `bcm_sbmt_l` | 제출 원장 — 우리가 벤더에 낸 건 | 출금·내부이체 제출의 멱등 판정 · sweep 제출 · 웹훅 분류의 기준 |
| `bcm_swp_trgt` | sweep 대상 마킹 | 입금 확정 시 마킹 → 주기 배치가 제출 |
| `bcm_swp_auth_m` | 고객 vault별 sweep 승인 관찰 상태 | allowance 확인 · approve 준비 · 긴급 회수 추적 |
| `bcm_swp_exec_l` | sweep 실행 — 최상위 배치 1건 | 실행 의도 선기록 · batch 제출·종결 |
| `bcm_swp_item_l` | sweep 실행 항목 | 한 배치 아래 원천 vault N개의 요청·실제 결과 |
| `bcm_boost_l` | boost intent·이력 | 자동 boost의 선기록·장애 복구·Admin 조회 |
| `bcm_job_m` | 주기 작업 상태 — heartbeat · 대사 커서 | tx 대사 대조 범위 · 밖에서 읽는 heartbeat |
| `bcm_raw_tx_l` | finalize 트랜잭션 원본 | 일 배치 보관 — 장기 보존 |
| `bcm_blkc_m` | 벤더 블록체인 카탈로그 — 일 1회 동기화 | 네트워크 채택 · 자산 등록 때 고르기 ([자산 매핑](07-asset-master.md)) |
| `bcm_vndr_ast_m` | 자산 매핑 — (네트워크, 토큰) ↔ 벤더 assetId | 벤더 호출 직전 변환 ([자산 매핑](07-asset-master.md)) |

## ERD

```erd
entity: bcm_addr_m @1,1 :: 주소 매핑 — (계정, 네트워크, 토큰)당 입금 주소 하나 | acnt_id PK,FK :: 계정 | ntwk_cd PK :: 네트워크 코드 | tkn_smbl PK :: 토큰 심볼 | dpst_addr :: 발급된 입금 주소
entity: bcm_whk_l @2,1 :: 수신 웹훅 알림 원본 — 인박스 (처리 후 N일 정리) | noti_id PK :: 웹훅 알림 id (벤더 UUID) — 중복 수신 방어 | vndr_tx_id :: 벤더 tx id — 이 알림이 가리키는 거래 | prcs_stcd :: 판단 처리 상태 P/S/F — F 는 격리
entity: bcm_outbox_l @3,1 :: 발행 대기 이벤트 — 워커가 상태 변경과 한 트랜잭션에 적재 | evnt_id PK :: 이벤트 id (UUID v7) · 컨슈머 dedup 키 | evt_typ_dvcd :: 이벤트유형 TXCK/TXCF/TXFL | evnt_stcd :: 발행상태 P/D/F/S
entity: bcm_sbmt_l @4,1 :: 제출 원장 — 우리가 벤더에 낸 건 (출금·내부이체·sweep) | ext_tx_id PK :: 우리 요청 키 = 멱등 키 | req_hash :: 요청 내용 SHA-256 — 같은 키 다른 내용 판별 | tx_dvcd :: WITHDRAWAL/INTERNAL/SWEEP_APPROVE/SWEEP_BATCH | vndr_tx_id UK :: 벤더 응답·웹훅으로 채운다 (NULL=미확인)
entity: bcm_acnt_m @1,2 :: 계정 매핑 — ref ↔ vault | acnt_id PK :: 매니저가 발급하는 계정 매핑 id | acnt_typ_dvcd UK :: 계정유형 CU 고객 / SY 시스템 | ref UK :: 백엔드 참조 키 = 코어 계정 ID · 유형과 함께 유일 | vndr_vlt_id :: 벤더 vault id (백엔드 비노출)
entity: bcm_tx_l @2,2 :: 거래 운영 상태 — 감지·발행 추적 | vndr_tx_id PK :: 최초 벤더 tx id = 논리 거래 id | actv_tx_id UK :: 현재 물리 벤더 tx id | tx_hash :: 현재 물리 거래의 온체인 hash | last_pub_stcd :: 마지막으로 발행한 TxStatus
entity: bcm_boost_l @3,2 :: boost intent·이력 — 호출 전 선기록 | orig_tx_id PK :: root 논리 거래 id | try_seq PK :: 시도 순번 | ext_tx_id UK :: RBF 제출 멱등 키 | new_tx_id UK :: 대체 벤더 tx
entity: bcm_swp_trgt @1,3 :: sweep 대상 마킹 — 작업 큐 | acnt_id PK,FK :: 고객 계정 | ntwk_cd PK :: 네트워크 코드 | tkn_smbl PK :: 토큰 심볼 | actv_swp_exec_id FK :: 현재 claim한 실행 (NULL=선정 가능)
entity: bcm_swp_auth_m @2,3 :: sweep 승인 관찰 상태 | acnt_id PK,FK :: 고객 계정 | ntwk_cd PK :: 네트워크 코드 | tkn_smbl PK :: 토큰 심볼 | swp_ctrt_addr PK :: 승인 대상 sweep 컨트랙트 | alwnc_cap :: 승인 상한 | obs_alwnc :: 마지막 온체인 관찰 allowance
entity: bcm_swp_exec_l @3,3 :: sweep 실행 — 최상위 batch tx | swp_exec_id PK :: 실행 id | ext_tx_id UK :: batch 제출 멱등 키 | vndr_tx_id UK :: Fireblocks 최상위 tx | swp_exec_stcd :: 실행 상태
entity: bcm_swp_item_l @4,3 :: sweep 실행 항목 | swp_exec_id PK,FK :: 실행 | item_seq PK :: 실행 안 순번 | acnt_id FK :: 원천 고객 계정 | req_amt :: 요청금액 | actl_amt :: 실제 이동금액 | swp_item_stcd :: 항목 상태
entity: bcm_raw_tx_l @2,4 :: finalize 원본 — 일 배치 장기 보관 | base_dt PK :: 적재 기준일 = 파티션 키 | vndr_tx_id PK :: 벤더 tx id | payload_hash :: 원문 SHA-256 — 무결성
entity: bcm_job_m @3,4 :: 주기 작업 상태 — heartbeat · 대사 커서 | job_nm PK :: 작업명 | last_scs_dttm :: 마지막 성공 — tx 대사 대조 범위 이어붙임
rel: bcm_acnt_m | bcm_addr_m | 계정당 주소 | one-many
rel: bcm_acnt_m | bcm_tx_l | 계정 귀속 | one-many
rel: bcm_acnt_m | bcm_swp_trgt | sweep 대상 | one-many
rel: bcm_acnt_m | bcm_swp_auth_m | sweep 승인 | one-many
rel: bcm_swp_exec_l | bcm_swp_item_l | 실행 항목 | one-many
rel: bcm_acnt_m | bcm_swp_item_l | 원천 vault | one-many
rel: bcm_swp_exec_l | bcm_swp_trgt | 활성 claim | one-many
rel: bcm_whk_l | bcm_tx_l | 워커가 옮김 | one-many | dashed
rel: bcm_sbmt_l | bcm_tx_l | 제출한 건이 웹훅으로 돌아옴 | one-one | dashed
rel: bcm_tx_l | bcm_outbox_l | 같은 트랜잭션 발행 예약 | one-many
rel: bcm_tx_l | bcm_boost_l | root 거래의 boost 시도 | one-many
rel: bcm_tx_l | bcm_raw_tx_l | 확정 원본 | one-many | dashed
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
step: sweep 대상 마킹 | 확정을 잡으면 그 (계정, 자산)을 sweep 대상으로 마킹한다 — 활성 실행은 비어 있음
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

확정 웹훅을 놓쳐 tx 가 CONFIRMED 에 멈춰도, 10분 주기 tx 대사가 벤더 목록의 **종결된 건**과 대조해 복구한다(진행 중은 웹훅 몫). `bcm_job_m` 이 대조 범위(마지막 성공 시각)를 이어붙인다.

```anim
db
table: bcm_tx_l | vndr_tx_id | last_pub_stcd
table: bcm_job_m | job_nm | last_scs_dttm
queue: deposit-events | 이벤트 | txId
step: 확정 웹훅 유실 | 확정 알림이 오지 않아 tx 가 CONFIRMED 에 멈춰 있다
ins: bcm_tx_l | tx-91c | CONFIRMED
ins: bcm_job_m | tx-recon | 11:50
step: tx 대사 실행 | 대사가 last_scs_dttm 이후 생성분을 벤더 목록으로 대조 — tx 가 실제 COMPLETED 임을 발견
step: 복구 — 확정 발행 | 놓친 확정을 deposit-events 에 발행하고 tx 행을 갱신한다
upd: bcm_tx_l | 1 | last_pub_stcd=FINALIZED
ins: deposit-events | 입금 확정 | tx-91c
step: 커서 전진 | 대사가 last_scs_dttm 을 이번 시각으로 전진 — 다음 대사는 여기서 이어붙인다
upd: bcm_job_m | 1 | last_scs_dttm=12:00
```

## 테이블 상세

모든 테이블은 코어 규약의 감사 4컬럼(`frst_reg_empno`·`frst_reg_brcd`·`last_chng_empno`·`last_chng_brcd`)을 끝에 둔다 — 아래 스키마에서는 반복을 줄여 **감사 4컬럼**으로 줄여 적고, 자동 처리 행은 시스템 센티넬로 채운다.

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
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
CREATE INDEX idx_bcm_whk_pick ON bcm_whk_l (prcs_stcd, rcv_dttm);  -- 판단 워커의 집기 — 미처리(P) 오래된 순
```

| 컬럼 | 뜻 |
|---|---|
| `noti_id` | insert 충돌 = 같은 알림의 중복 전달 — 무시하고 200 을 돌려준다. 중복 방어가 물리 제약으로 끝난다 |
| `payload` | 검증·파싱 전의 원본을 **받은 바이트 그대로** 둔다. 판단 버그가 있어도 원본으로 재처리할 수 있고, finalize 원본 보관이 그 tx 의 마지막 COMPLETED 알림의 이 값을 옮겨 간다. JSONB 가 아니라 TEXT 인 이유 — JSONB 는 저장 시 정규화(키 재정렬·공백)돼 **꺼낸 값이 원문 바이트가 아니다**. 저장했다 꺼낸 본문에 원래 서명을 붙이면 벤더 검증이 401 로 떨어진다 (2026-08 PoC 실측). 운영 조회에서 JSON 항목이 필요하면 `payload::jsonb ->> …` 로 캐스팅한다 |
| `payload_hash` | 무결성 증명의 기준값. **HTTP 본문을 문자열로 바꾸기 전 `byte[]` 로 계산한다** — 수신부는 바이트를 한 번 읽어 서명 검증·저장·해시 세 곳에 같은 배열을 쓴다. `bcm_raw_tx_l` 로 옮길 때 다시 계산하지 않고 이 값을 복사한다 |
| `sign_vl` | 해시는 "우리가 저장한 바이트가 우리가 해시한 바이트와 같다"까지만 증명한다. 벤더가 보낸 것임을 나중에 다시 증명하려면 서명이 함께 있어야 하고, 서명은 수신 시점에만 존재한다. 서명 검증을 통과한 알림만 적재되므로 NOT NULL |
| 보존 | 처리 후 N일(운영 설정값) 뒤 정리 — 장기 보존은 `bcm_raw_tx_l` 몫 |

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

### bcm_sbmt_l — 제출 원장

우리가 벤더에 낸 건(출금·내부이체·sweep)을 **벤더에 보내기 전에** 먼저 적는 원장이다. 두 가지 일을 한다 — ① `ext_tx_id` 멱등 판정(같은 키 + 같은 내용이면 처음 `txId` 반환, 내용이 다르면 거절) ② 우리 vault 에서 나간 웹훅이 어느 계열인지 가르는 기준.

`bcm_tx_l` 에 흡수하지 않는 이유는 **키가 다르기 때문**이다. `bcm_tx_l` 의 PK 는 벤더 tx id 인데 제출 시점에는 그 값을 아직 모른다. 반대로 멱등 판정은 벤더를 부르기 전에 끝나야 한다 — 부른 뒤에 적으면 그 사이에 죽었을 때 돈이 나갔는지 알 방법이 없다. 그래서 우리 요청 키를 PK 로 갖는 원장을 따로 둔다.

```sql
CREATE TABLE bcm_sbmt_l (
  ext_tx_id     VARCHAR(128) PRIMARY KEY,   -- 우리 요청 키 = 멱등 키. 승인된 출금 지시 1건과 1:1
  req_hash      CHAR(64)     NOT NULL,      -- 요청 내용의 SHA-256 (소문자 hex) — 아래 canonical 규칙
  hash_vrsn     VARCHAR(8)   NOT NULL,      -- canonical 규칙 판 (v1) — 판이 다르면 아래 필드로 재계산해 비교
  sbmt_stcd     VARCHAR(16)  NOT NULL,      -- 제출 상태 REQUESTED · SUBMITTED · FAILED
  claim_id      VARCHAR(36)  NULL,          -- 진행 중 소유권 토큰(UUID) — 이 값을 쥔 호출자만 벤더에 제출한다
  claim_exp_dttm VARCHAR(16) NULL,          -- 소유권 만료 일시 — 지나면 다른 호출자가 뺏는다 (죽은 소유자 방치 방지)
  tx_dvcd       VARCHAR(16)  NOT NULL,      -- WITHDRAWAL · INTERNAL · SWEEP_APPROVE · SWEEP_BATCH
  vndr_tx_id    VARCHAR(64)  NULL,          -- 벤더 응답(또는 먼저 온 웹훅)으로 채운다. NULL = 벤더에 닿았는지 미확인
  swp_exec_id   VARCHAR(36)  NULL,          -- SWEEP_BATCH일 때 bcm_swp_exec_l 연결
  snd_acnt_id   VARCHAR(64)  NOT NULL,      -- 보내는 계정 — 이벤트 파티션 키이기도 하다
  rcv_dvcd      VARCHAR(16)  NOT NULL,      -- 목적지 유형 ADDRESS · ACCOUNT · WHITELISTED
  rcv_vl        VARCHAR(128) NOT NULL,      -- 목적지 식별값 — 유형에 따라 주소 · 계정 · 등록지갑 id 중 하나
  ntwk_cd       VARCHAR(20)  NOT NULL,      -- 네트워크 코드
  tkn_smbl      VARCHAR(16)  NOT NULL,      -- 토큰 심볼
  trsf_amt      NUMERIC(36,18) NOT NULL,    -- 정규화한 금액
  req_dttm      VARCHAR(16)  NOT NULL,      -- 접수 일시 — 미결 제출 점검의 기준
  rsp_dttm      VARCHAR(16)  NULL,          -- 벤더 응답 일시
  last_chck_dttm VARCHAR(16) NULL,           -- 미결 점검이 마지막으로 벤더 조회한 일시
  chck_cnt      INTEGER      NOT NULL DEFAULT 0, -- 미결 조회 횟수 — 백오프·경보 기준
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
CREATE UNIQUE INDEX ux_bcm_sbmt_vndr_tx ON bcm_sbmt_l (vndr_tx_id) WHERE vndr_tx_id IS NOT NULL;
CREATE INDEX idx_bcm_sbmt_open ON bcm_sbmt_l (sbmt_stcd, last_chck_dttm, req_dttm); -- 미결 점검 후보
```

| 컬럼 | 뜻 |
|---|---|
| `ext_tx_id` | PK 가 멱등의 물리 근거다. 제출 요청이 오면 **벤더를 부르기 전에** 이 행을 먼저 넣는다 — 충돌하면 이미 받은 키다 |
| `req_hash` | "같은 내용인가"의 판정값. 아래 canonical 규칙으로 만든 문자열의 SHA-256. **요청 원문은 저장하지 않는다** — `travelRule` 이 트래블룰 게이트가 만든 암호화 산출물(IVMS101 계열 개인정보)이라, 원문을 남기면 매니저가 그 보관 주체가 된다. 매니저는 운반만 한다([흐름](02-bcm-flow.md)) |
| `hash_vrsn` | canonical 규칙을 나중에 바꿔도 옛 행을 되살릴 수 있게 판을 함께 적는다. 판이 다르면 해시를 믿지 않고 아래 개별 컬럼으로 그 판의 규칙을 다시 적용해 비교한다 |
| `tx_dvcd` | **웹훅 분류의 유일한 기준.** `SWEEP_APPROVE`는 고객 vault의 allowance 설정, `SWEEP_BATCH`는 운영 계정의 최상위 batch 호출이다. 둘 다 고객 토픽으로 발행하지 않는다 |
| `swp_exec_id` | `SWEEP_BATCH`에 필수이고 그 밖에는 NULL이다. 최상위 벤더 tx를 실행 1건과 연결하고, 원천 이동은 `bcm_swp_item_l`에서 펼친다 |
| `vndr_tx_id` | 벤더 응답으로 채우는 게 정상이지만, 응답을 못 받고 웹훅이 먼저 와도 그때 채운다. 부분 UNIQUE 인덱스가 벤더 tx 와 1:1 을 보장한다 |
| `sbmt_stcd` | `REQUESTED` = 벤더에 닿았는지 모름 · `SUBMITTED` = 벤더가 tx id 를 줌 · `FAILED` = 벤더가 검증으로 확정 거절. 복구 규칙은 [흐름](02-bcm-flow.md) 출금 절 |
| `claim_id`·`claim_exp_dttm` | **같은 키가 동시에 들어와도 벤더 제출은 한 번만** 하게 하는 소유권이다. 행을 넣거나 뺏을 때 토큰과 만료를 함께 적고, 그 토큰을 쥔 호출자만 벤더를 부른다. **상태값(`SUBMITTING` 같은)으로 하지 않는 이유** — 소유자 프로세스가 죽으면 그 상태에서 영원히 멈춘다. 만료 시각이 붙어 있으면 스스로 풀린다. 만료는 벤더 제출 호출 타임아웃보다 길게 잡는다(운영 설정값) |
| `last_chck_dttm`·`chck_cnt` | 재시도 요청이 오지 않는 오래된 `REQUESTED` 를 미결 점검이 확인한 흔적. 성공적으로 찾았을 때뿐 아니라 미발견·조회 실패에도 갱신해 같은 건을 매 주기마다 두드리지 않고 백오프·경보한다. **점검기는 조회만 하고 재제출하지 않는다** — 일반 출금 원문을 저장하지 않고, 원 API 요청과 경합하면 중복 전송이 될 수 있기 때문이다 |
| `snd_acnt_id` | 출금은 출금 풀 vault 계정, 내부이체는 출발 계정. 이벤트 파티션 키가 이 값이라 따로 `acnt_id` 를 두지 않는다 |
| sweep 제출의 공통 컬럼 | `SWEEP_APPROVE`는 고객 계정·토큰 컨트랙트·승인 cap, `SWEEP_BATCH`는 운영 계정·sweep 컨트랙트·요청 총액을 `snd_acnt_id`·`rcv_vl`·`trsf_amt`에 저장한다. 항목별 금액은 실행 항목이 정본이다 |
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
```

| 컬럼 | 뜻 |
|---|---|
| `evnt_id` | time-ordered UUID v7 — PK 이자 컨슈머 dedup 키. relay 가 같은 행을 두 번 보내도 컨슈머가 이 값으로 접는다. 시간정렬이라 별도 생성시각 없이 발송 순서로 쓴다 |
| `bcm_tx_l` 갱신 | **행을 잠그고 판정한다** (2026-08-06 확정) — 전이 허용 여부는 직전 상태를 읽어야 정해지므로(02 허용 전이 표) 읽고 쓰는 사이에 다른 알림이 끼면 판정이 어긋난다. 그 tx 행을 `SELECT … FOR UPDATE` 로 잠근 뒤 판정·갱신한다. 같은 tx 의 알림만 경합하므로 잠금 범위가 좁다. `cnfm_cnt` 는 추가로 `GREATEST` 로 감싸 **줄지 않게** 한다(02) |
| set-once 컬럼 | **갱신문에서 제외한다** — `frst_dtct_dttm` 과 감사의 `frst_reg_empno`·`frst_reg_brcd` 는 최초 흔적이라 다시 쓰지 않는다. 갱신은 `last_chng_*` 만 건드린다 |
| UNIQUE 충돌 | 도메인 예외로 바꾸고 **재조회해 이긴 값을 돌려준다** — 경합해도 결과는 하나다 |
| `evt_typ_dvcd` | 코어 이벤트 어휘와 통일 — TXCK(Checking)·TXCF(Confirmed)·TXFL(Failed)·**TXRJ(Rejected — 2026-08-06 신설 제안, 코어 확정 대기)**. BC→코어 계약이 한 어휘로 흐른다 |
| `evnt_stcd` | 워커 적재 시 `P`, relay 발송 성공 시 `S`, 실패 누적 시 `F`. relay 는 `P` 를 `evnt_id` 순으로 집는다 |

### bcm_swp_trgt — sweep 대상

입금 확정 관찰이 마킹하고, 주기 작업이 같은 네트워크·토큰의 대상을 묶는다. PK 가 (계정, 자산)이라 입금이 여러 번 와도 행 하나다. 실행과 항목을 먼저 만든 뒤 `actv_swp_exec_id + actv_item_seq`로 claim한다.

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
| 삭제 기준 | batch 제출 성공이 아니라 **항목 성공 뒤 vault 잔액이 최소 미만임을 확인**했을 때 삭제한다. vault 잔액이 진실이고 이 테이블은 작업 큐다 |
| 행 생성·삭제 | 확정 관찰 → insert(있으면 무시) · 실행/항목 선기록과 같은 트랜잭션에서 claim · 성공 후 잔액이 최소 미만이면 삭제 · 실패 또는 잔액 잔존이면 claim을 NULL로 풀어 재선정 |
| `actv_swp_exec_id`·`actv_item_seq` | 한 최상위 batch tx 아래 어느 원천 이동으로 처리 중인지 가리키는 1:N 링크. 둘 다 NULL이거나 둘 다 값이어야 한다 |
| `try_cnt` | 반복 실패가 임계(운영 설정값)를 넘으면 경보 — externalTxId 멱등이라 재제출은 안전하다 |

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
- 같은 운영 계정의 `READY`·`SUBMITTING` 실행은 부분 UNIQUE 인덱스로 하나만 허용한다. 새 후보를 claim하기 전에 이 실행을 먼저 회수하며, 벤더 접수를 기록해 `SUBMITTED`가 된 뒤에만 다음 실행을 만들 수 있다.
- 항목은 EVM 원천 주소 20바이트 값 오름차순으로 정렬하고 `item_seq`를 1부터 부여한다. 같은 네트워크의 주소 중복은 허용하지 않는다.
- `req_hash`의 첫 판은 `batch-v1`이다. LF로 `BATCH_V1` · network · symbol · token contract 소문자 · sweep contract 소문자 · 표준 36자 UUID executionId 소문자를 한 줄씩 잇고, 이어서 각 항목을 `item_seq|원천주소 소문자|정규화 십진금액` 한 줄로 붙인 UTF-8 바이트의 SHA-256 소문자 hex다. 마지막 LF는 붙이지 않는다. 재시도에서 내용이 달라지면 같은 `ext_tx_id`를 쓰지 못한다.
- calldata는 [sweep 운영 ABI](06-sweep.md#운영-컨트랙트-abi)의 같은 순서를 쓰되 executionId는 UUID 원문 16바이트, 금액은 token decimals를 적용한 최소 단위 정수다. canonical hash의 금액은 DB와 같은 사람 단위 십진 정규화 값이라 ABI 단위와 섞지 않는다.
- 최상위 거래가 `COMPLETED`여도 곧바로 실행을 완료하지 않는다. `RECONCILING`에서 요청 N개와 network records·receipt의 `SweepLeg` 이벤트를 대조한 뒤 `COMPLETED` 또는 `PARTIAL`로 종결한다.
- 되돌려진 항목은 network records에 없을 수 있으므로 성공 레코드의 부재만으로 실패 사유를 추측하지 않는다. 컨트랙트 실패 이벤트와 실행 후 잔액을 함께 본다.

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

주기 작업(막힘 점검 · sweep 배치 · tx 대사 · 수수료 관측)별 한 행. 밖의 모니터링이 읽기 전용 계정으로 읽는 heartbeat 와, tx 대사의 대조 범위 이어붙임에 쓴다.

```sql
CREATE TABLE bcm_job_m (
  job_nm         VARCHAR(64)  PRIMARY KEY,   -- 작업명
  last_run_dttm  VARCHAR(16)  NOT NULL,      -- 마지막 실행 일시 — heartbeat
  last_scs_dttm  VARCHAR(16)  NULL,          -- 마지막 성공 일시 — tx 대사는 이 값부터 대조 범위를 이어붙인다
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
```

### bcm_raw_tx_l — finalize 트랜잭션 원본

finalize 된 tx 의 벤더 원문을 일 배치로 장기 보관한다. 원본은 `bcm_whk_l` 에서 그 tx 의 마지막 COMPLETED 알림의 `payload`·`payload_hash`·`sign_vl` 세 값을 **그대로 옮긴다** — 벤더 재조회도, 해시 재계산도 없다.

```sql
CREATE TABLE bcm_raw_tx_l (
  base_dt        VARCHAR(8)   NOT NULL,   -- 적재 기준일 = 파티션 키 (YYYYMMDD)
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
) PARTITION BY RANGE (base_dt);           -- 월 단위 파티션
CREATE INDEX idx_bcm_raw_tx_hash ON bcm_raw_tx_l (tx_hash);        -- 분쟁·역추적 — 이 온체인 tx 의 원본
CREATE INDEX idx_bcm_raw_tx_addr ON bcm_raw_tx_l (addr, base_dt);  -- 지갑(주소) 기준 기간 조회 — 선두가 주소라 균등 분산
```

`payload` 는 바이트 그대로 보존해야 해 JSONB 가 아니라 TEXT 다(무결성 해시가 원문 바이트 기준). 이 표의 세 값은 모두 수신 시점에만 만들 수 있어 `bcm_whk_l` 이 정리되기 전에 옮겨야 한다 — 지나가면 소급해서 만들 수 없다. 보존 연한·파티션 주기·자체 RPC 로 체인 원문까지 보관할지는 미확정이다(아래 미확정 절).

## 미확정

- **`bcm_tx_l`·`bcm_whk_l`·`bcm_outbox_l` 보존** — 종결·처리 완료·발송 완료 건을 언제까지 두고 언제 정리할지 — `bcm_raw_tx_l` 원본 보관과 역할을 나눈 뒤 확정.

## 확정 이력 (2026-08-12)

- **막힘 판정은 벤더 최신 관찰 기준** — DB 공통 상태는 후보 선별에만 쓰고, `CONFIRMING`·tx hash 있음·0 confirmation을 RBF 직전에 단건 조회로 확인한다. `SUBMITTED=미채굴`로 간주하지 않는다.
- **boost도 crash-safe intent를 선기록** — `bcm_boost_l`에 별도 externalTxId·claim·교체 대상을 먼저 남기며, 물리 대체 거래는 최초 `bcm_tx_l` root 행으로 접는다. 원 거래와 대체 거래 중 먼저 채굴된 쪽이 승자다.
- **미결 제출 점검은 조회만 수행** — 오래된 `REQUESTED`의 벤더 거래가 확인되면 회수하고, 미발견·조회 실패는 체크포인트만 갱신한다. 전체 원 요청 문맥 없이 백그라운드에서 재제출하지 않는다.

## 확정 이력 (2026-08-07)

- **제출 원장 `bcm_sbmt_l` 신설** — `ext_tx_id` PK. 벤더를 부르기 전에 먼저 적어 멱등을 판정하고, 우리 vault 발신 웹훅의 계열을 `tx_dvcd` 로 가른다. `bcm_tx_l` 흡수는 키가 달라(PK = 벤더 tx id, 제출 시점 미상) 불가능하다.
- **canonical 요청 = 자금 이동 7값** — from 2 · to 2 · network · symbol · 정규화 amount. `note`·`travelRule` 은 제외(재시도 오탐 방지 + 개인정보 미보관). 요청 원문은 저장하지 않고 해시와 개별 필드만 남긴다.

## 확정 이력 (2026-08-06)

- **일시의 시간대 = KST(`Asia/Seoul`)** — 14자 포맷에 오프셋이 없어 한 시간대로 고정해야 하고, `base_dt` 일 경계가 영업일과 맞아야 한다. 코어 스키마 사본에는 시간대를 밝힌 근거가 없어 설계 결정으로 확정했다 — DAW-CORE 회신이 오면 대조한다(어긋나면 Clock 빈 교체로 끝난다).
- **수신 원문은 바이트 그대로** — `bcm_whk_l.payload` 를 JSONB 에서 TEXT 로 바꾸고 `payload_hash`·`sign_vl` 을 수신 시점에 함께 남긴다. JSONB 정규화 때문에 기존 스키마로는 `bcm_raw_tx_l.payload_hash` 의 "수신 시점 와이어 바이트" 요구를 지킬 수 없었다.

## 확정 이력 (2026-08-05)

- **약어** — 이 문서 표기 그대로 확정(`bcm`·`vndr`·`vlt`·`noti`·`swp`·`sbmt`(제출)·`rcv`(받는 쪽)·`snd`(보내는 쪽) = 프로젝트 약어집). DAW-CORE DB 약어집이 나오면 대조해 어긋난 것만 조정한다.
- **감사 컬럼 센티넬** — 자동 처리 행은 `empno='SYSTEM'` · `brcd='9999'`(실존 부점과 충돌 불가한 값). 구현은 단일 상수로 관리해 코어 운영 규약 확인 시 한 곳만 바꾼다.
- **subStatus·networkStatus** — `bcm_tx_l` 에 보관(`vndr_sub_stcd`·`vndr_ntwk_stcd`). 운영 조사용 — 이벤트 미탑재는 유지.
