---
title: 컴플라이언스 게이트 — DB
status: To Do
group: 컴플라이언스 게이트
---

컴플라이언스 게이트 DB(`cmpl_`)의 테이블 넷 — VASP 레지스트리, 출금 확인 상태, 입금 사전 검증 기록, 발행 아웃박스.
정체·거래 허용(고객 화면 노출)은 여기가 아니라 DAW-CORE VASP 마스터(`daw_vasp_m`)에 있다 — 게이트는 솔루션 라우팅과 사전 검증 기록만 쥔다. 흐름은 [게이트 흐름](04-compliance-flow.md).

## 명명 규약

코어 DB(`daw_`) 규약을 그대로 따른다 — BC·컴플라이언스·코어가 한 규약을 쓴다.

- **접두** `cmpl_` · **접미** `_m`(마스터) · `_l`(내역/로그)
- **컬럼 축약** `_dvcd`(구분코드 — 종류 enum) · `_stcd`(상태코드 — 바뀌는 상태) · `_yn`(VARCHAR(1) Y/N) · `_dttm`(일시) · `_dt`(일자 · VARCHAR(8)) · `_cnt`(횟수) · `_id` · payload(JSONB)
- **일시는 VARCHAR(16)** — 코어와 동일(TIMESTAMP 안 씀)
- **금액은 NUMERIC** · **자산 심볼은 `tkn_smbl`** — 코어 규약
- **감사 4컬럼** — 모든 테이블에 `frst_reg_empno`(6)·`frst_reg_brcd`(4)·`last_chng_empno`(6)·`last_chng_brcd`(4). 자동 처리 행은 시스템 센티넬, Admin 행위(VASP 활성화·매핑 등)는 실제 직원/부점. 행 발생·변경 "시각"은 별도 도메인 `_dttm`(`occr_dttm`·`sync_dttm`·`rcv_dttm`)이 담당한다
- **PII 없음** — 어느 테이블에도 이름 등 신원 정보를 두지 않는다. 원문은 Enclave(국내)·벤더(해외)가 보관하고, 여기엔 대조 키만 둔다.

## 테이블 한눈에

| 테이블 | 무엇을 저장하나 | 쓰는 곳 |
|---|---|---|
| `cmpl_vasp_m` | VASP 레지스트리 — 솔루션 목록 + `cmpl_vasp_id`(게이트 발급 안정 id) + 코어 `vasp_id` 매핑 + 활성화 | 목록 동기화 · Admin 활성화 · 출금 확인 라우팅 |
| `cmpl_wdrl_chk_l` | 출금 트래블룰 확인 상태 | Create/Get Withdrawal Check · Report · settled 발행 · PENDING 만료 스캔 |
| `cmpl_outbox_l` | 발행 대기 이벤트 — verdict 확정과 원자 기록 | 결과 확정 시 적재 → relay 가 settled 발행 |
| `cmpl_pre_vrfc_l` | 입금 사전 검증 기록 — 대조 키만 | 사전 검증 수신 적재 · tx hash 갱신 · 입금 도착 대조 |

## ERD

```erd
entity: cmpl_vasp_m @1,1 :: VASP 레지스트리 — 솔루션 목록 + 코어 vasp_id 매핑·활성화 | cmpl_vasp_id PK :: 게이트 발급 안정 id — Admin 이 이 값으로 지목 | soln_dvcd :: 솔루션 구분 (VERIFYVASP·CODE_INTEROP·NOTABENE) | vasp_id :: 매핑된 코어 VASP id — 미매핑이면 NULL | actv_yn :: 활성화(라우팅 켜짐) 여부
entity: cmpl_wdrl_chk_l @2,1 :: 출금 트래블룰 확인 상태 | chk_id PK :: 확인 건 id | ext_tx_id UK :: 출금 멱등 키 — DAW-CORE·매니저와 같은 값 | vasp_id :: 수취 VASP (코어 id) — 라우팅 입력 | vrdt_stcd :: 확인 결과 (TrVerdict)
entity: cmpl_pre_vrfc_l @1,2 :: 입금 사전 검증 기록 — 대조 키만 (PII 없음) | vrfc_ref PK :: 솔루션 발급 검증 참조 | bnfc_addr :: 우리 쪽 수취 주소 | tx_hash :: 전송 후 상대가 보고 | mtch_dttm :: 도착 입금과 대조 성공 일시
entity: cmpl_outbox_l @2,2 :: 발행 대기 이벤트 (Outbox) — verdict 저장과 원자 기록 | evnt_id PK :: 이벤트 id (UUID v7) · 컨슈머 dedup 키 | chk_id :: 어느 확인 건의 이벤트인가 | evnt_stcd :: 발행상태 P/D/F/S
rel: cmpl_vasp_m | cmpl_wdrl_chk_l | vasp_id 로 라우팅 | one-many | dashed
rel: cmpl_wdrl_chk_l | cmpl_outbox_l | 같은 트랜잭션 발행 예약 | one-many
```

점선 = 값으로 잇는 논리 관계 — 출금 확인이 `vasp_id` 로 레지스트리를 조회해 솔루션을 고른다(FK 아님). `cmpl_pre_vrfc_l` 은 입금 대조 전용 독립 테이블이라 다른 테이블과 관계가 없다. 배지 PK·UK.

## 시나리오로 보는 테이블 흐름

한 건이 어느 테이블을 언제 건드리는지 — 단계를 넘겨 보라. 초록 행 = 그 단계에 새로 들어온 행, 노랑 칸 = 바뀐 값. **청록 = DB 테이블 · 노랑 = 메시지 큐 · 보라 = 밖에서 들어오거나 당겨오는 것.**

### 출금 확인

접수는 항상 `PENDING`, 최종 결과는 `compliance` 큐의 `withdrawal-check.settled` 이벤트로만 나간다. 발행은 매니저와 같은 outbox 경로다 — verdict 저장과 이벤트 적재가 한 트랜잭션, relay 가 발행. 제출 tx hash 보고는 사전 검증과 실 거래를 잇는 비차단 후처리다.

```anim
db
table: cmpl_wdrl_chk_l | chk_id | ext_tx_id | vrdt_stcd | rpt_tx_hash
table: cmpl_outbox_l | 이벤트 | 발송
queue: compliance | 이벤트 | externalTxId
step: ① 접수 (PENDING) | DAW-CORE 가 externalTxId 로 확인을 요청 — 행이 생기고 PENDING 으로 접수 응답
ins: cmpl_wdrl_chk_l | chk-01 | wd-42 | PENDING | 
step: ② 솔루션 왕복 | 어댑터가 솔루션과 왕복 — 동기·비동기 차이를 흡수한다
step: ③ 결과 확정 — 한 트랜잭션 | 통과 → APPROVED 로 확정하고 outbox 에 settled 이벤트를 적재(P)한다 — 한 커밋
upd: cmpl_wdrl_chk_l | 1 | vrdt_stcd=APPROVED
ins: cmpl_outbox_l | ev-01 | P
step: ④ relay 발행 | relay 가 미발송(P)을 큐로 보내고 S 로 표시한다 — 소비는 checkId 멱등
ins: compliance | withdrawal-check.settled | wd-42
upd: cmpl_outbox_l | 1 | 발송=S
step: ⑤ 제출 tx hash 보고 | DAW-CORE 가 제출 후 tx hash 를 보고 — 요구하는 솔루션에만 전달(비차단)
upd: cmpl_wdrl_chk_l | 1 | rpt_tx_hash=0x4e1d
```

기한이 지나도록 결과가 없는 `PENDING` 은 만료 배치가 찾아 `REJECTED`(만료)로 확정하고 같은 이벤트를 발행한다 — 만료 verdict 를 만드는 유일한 경로다.

### VASP 온보딩 — 동기화와 활성화

한 행의 두 출처를 나눈다 — **동기화**는 솔루션 목록 컬럼만, **활성화**는 매핑 컬럼(`vasp_id`·`actv_yn`)만 건드린다. 목록에서 빠져도 삭제하지 않고 도달 가능 표시만 내려, 매핑·활성화를 잃지 않는다.

```anim
db
source: 솔루션 목록 | 동기화
table: cmpl_vasp_m | cmpl_vasp_id | vasp_id | actv_yn | rchbl_yn
step: ① 동기화 — 목록 적재 | 솔루션 목록을 UPSERT — 신규 항목에 cmpl_vasp_id 발급, 아직 비활성
ins: 솔루션 목록 | VASP 항목
ins: cmpl_vasp_m | cv-01 |  | N | Y
step: ② Admin 활성화 — 코어가 매핑 | Admin 이 온보딩하면 코어가 vasp_id 를 만들어 게이트에 매핑하고 활성화한다
upd: cmpl_vasp_m | 1 | vasp_id=vasp-7 | actv_yn=Y
step: ③ 출금 확인 라우팅 | 출금 확인의 vaspId 로 이 행을 찾아 솔루션을 정한다
step: ④ 목록에서 사라짐 | 다음 동기화에 빠지면 삭제하지 않고 도달 가능만 내린다 — 매핑·활성화는 그대로
upd: cmpl_vasp_m | 1 | rchbl_yn=N
```

### 입금 사전 검증 — 자금보다 정보가 먼저

입금은 방향이 반대다 — 상대 거래소가 자금을 보내기 전에 사전 검증(예고)을 먼저 보내온다. 게이트는 대조 키만 추려 두었다가, 자금이 도착하면 그 기록과 맞춘다.

```anim
db
hook: 상대 VASP 사전검증 | 도착
table: cmpl_pre_vrfc_l | vrfc_ref | tx_hash | mtch_dttm
queue: deposit-events | 이벤트 | txId
step: ① 사전 검증 적재 | 자금 전에 온 예고를 대조 키만 추려 적재한다 (PII 없음)
ins: 상대 VASP 사전검증 | 입금 예고
ins: cmpl_pre_vrfc_l | vrfc-01 |  | 
step: ② tx hash 보고 | 상대가 온체인 전송 후 거래 해시를 알려오면 같은 행에 채운다
upd: cmpl_pre_vrfc_l | 1 | tx_hash=0x9a2c
step: ③ 자금 도착 | 매니저가 입금을 감지·확정해 deposit-events 를 발행한다
ins: deposit-events | 입금 확정 | tx-91c
step: ④ 대조 | DAW-CORE 확인 요청 → 사전 검증 기록과 대조해 "예고된 입금"으로 답하고 매칭 표시(이중 매칭 방지)
upd: cmpl_pre_vrfc_l | 1 | mtch_dttm=12:00
```

## 테이블 상세

모든 테이블은 코어 규약의 감사 4컬럼(`frst_reg_empno`·`frst_reg_brcd`·`last_chng_empno`·`last_chng_brcd`)을 끝에 둔다 — 아래에서는 반복을 줄여 **감사 4컬럼**으로 적고, 자동 처리 행은 시스템 센티넬, Admin 행위(활성화·매핑)는 실제 직원/부점으로 채운다.

### cmpl_vasp_m — VASP 레지스트리

게이트가 아는 VASP 한 항목이 한 행이다. 동기화가 솔루션 목록을 채우고(신규엔 `cmpl_vasp_id` 발급), 활성화가 코어 `vasp_id` 를 매핑하고 `actv_yn` 을 켠다.

```sql
CREATE TABLE cmpl_vasp_m (
  cmpl_vasp_id   VARCHAR(64)  PRIMARY KEY,   -- 게이트 발급 안정 id — Admin 조회·매핑 대상. 동기화 재적재에도 안 바뀐다
  soln_dvcd      VARCHAR(16)  NOT NULL,      -- 솔루션 구분: VERIFYVASP | CODE_INTEROP | NOTABENE
  soln_vasp_id   VARCHAR(255) NOT NULL,      -- 솔루션 쪽 식별자 (vaspId · DID)
  vasp_nm        VARCHAR(255) NOT NULL,      -- 솔루션이 알려준 표시명
  vasp_id        VARCHAR(64)  NULL,          -- 매핑된 코어 VASP id (daw_vasp_m) — 활성화 때 채운다. 미매핑이면 NULL
  actv_yn        VARCHAR(1)   NOT NULL,      -- 활성화 여부 (Y/N) — 해제해도 매핑(vasp_id)은 남긴다
  rchbl_yn       VARCHAR(1)   NOT NULL,      -- 이 항목으로 확인을 보낼 수 있는가 (Y/N · 마지막 동기화 기준)
  sync_dttm      VARCHAR(16)  NOT NULL,      -- 마지막 동기화 일시
  reg_dttm       VARCHAR(16)  NOT NULL,      -- 최초 등재 일시
  last_chng_dttm VARCHAR(16)  NOT NULL,      -- 매핑·활성화 마지막 변경 일시
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  UNIQUE (soln_dvcd, soln_vasp_id)
);
CREATE INDEX idx_cmpl_vasp_by_core ON cmpl_vasp_m (vasp_id);
```

| 컬럼 | 뜻 |
|---|---|
| `cmpl_vasp_id` | 게이트가 발급하는 안정 id — Admin 이 목록에서 이 값으로 VASP 를 지목해 활성화한다 |
| 동기화 UPSERT | `(soln_dvcd, soln_vasp_id)` 로 대조해 UPSERT — 신규면 `cmpl_vasp_id` 발급(`actv_yn`=N), 기존이면 목록 컬럼만 갱신하고 `vasp_id`·`actv_yn` 은 보존. **목록에서 사라진 항목은 지우지 않고 `rchbl_yn`=N** |
| `vasp_id` 매핑 | 활성화 API(코어 → 게이트)가 코어의 `vasp_id` 를 채운다. 출금 확인의 `vaspId` 를 이 값(인덱스 `idx_cmpl_vasp_by_core`)으로 조회해 솔루션·라우팅을 정한다 |
| 다중 솔루션 | 같은 실물 VASP 가 여러 솔루션에 있으면 항목(행)이 여럿이라 `vasp_id` 가 여러 행에 붙는다 — 라우팅 규칙으로 하나를 고른다(미확정) |

### cmpl_wdrl_chk_l — 출금 확인 상태

출금 한 건의 트래블룰 확인 한 건이 한 행이다. 요청 접수 → 솔루션 왕복 → 결과 확정 → 제출 tx hash 보고가 이 행에 쌓인다.

```sql
CREATE TABLE cmpl_wdrl_chk_l (
  chk_id          VARCHAR(64)  PRIMARY KEY,      -- 확인 건 식별자 — 게이트 발급
  occr_dttm       VARCHAR(16)  NOT NULL,         -- 발생 일시 — 요청이 접수돼 행이 생긴 시각
  ext_tx_id       VARCHAR(128) NOT NULL UNIQUE,  -- 멱등 키 — DAW-CORE·매니저와 같은 값. UNIQUE 가 멱등의 물리 근거
  acnt_id         VARCHAR(64)  NOT NULL,         -- 고객 계정 id
  vasp_id         VARCHAR(64)  NOT NULL,         -- 수취 VASP — DAW-CORE 가 지목한 코어 VASP 마스터 id
  soln_dvcd       VARCHAR(16)  NOT NULL,         -- 라우팅으로 확정된 솔루션 — 사후 감사·재현용
  rqst_hash       VARCHAR(64)  NOT NULL,         -- 최초 요청 본문 해시 — 같은 키에 다른 내용의 재요청 거절
  vrdt_stcd       VARCHAR(16)  NOT NULL,         -- TrVerdict: NOT_REQUIRED | APPROVED | PENDING | REJECTED
  trvl_rule_msg   TEXT         NULL,             -- 제출에 실어 보낼 암호화 메시지 — 만드는 솔루션(Notabene)만 값
  evdc_dvcd       VARCHAR(32)  NULL,             -- 통과 증적 종류 (enum 미확정)
  evdc_ref        VARCHAR(255) NULL,             -- 증적 참조 (예: 사전 승인 UUID)
  stld_dttm       VARCHAR(16)  NULL,             -- 최종 결과 일시 — 채워지면 더는 안 바뀐다. PENDING 이면 NULL
  pend_expr_dttm  VARCHAR(16)  NULL,             -- PENDING 만료 스캔 기준
  rpt_tx_hash     VARCHAR(128) NULL,             -- DAW-CORE 가 제출 후 보고해 온 거래 해시
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
```

| 컬럼 | 뜻 |
|---|---|
| `ext_tx_id` | 이 확인이 어느 출금 건인가 — DAW-CORE 출금 건 식별자이자 매니저 제출 키와 같은 값. UNIQUE 가 멱등의 물리 근거 — 같은 키 재요청은 이 행을 돌려준다 |
| `vasp_id` | 수취 거래소 — DAW-CORE 가 지목한 코어 마스터 id. 이 값으로 `cmpl_vasp_m` 에서 솔루션 항목을 찾아 라우팅한다 |
| `soln_dvcd` | 실제로 어느 솔루션으로 보냈는지 — 사후 감사·재현용 |
| `rqst_hash` | 같은 키에 다른 내용의 재요청이 오면 이 값과 대조해 거절한다 |
| `vrdt_stcd` | 확인의 현재 결과 — NOT_REQUIRED(대상 아님) · APPROVED(통과) · PENDING(대기) · REJECTED(거절·만료) |
| `trvl_rule_msg` | 통과 시 출금 제출에 실어 보낼 암호화 메시지 — Notabene 경로만 값이 있다 |
| `evdc_dvcd` · `evdc_ref` | 통과를 증명하는 기록의 종류와 참조 값. 종류 목록은 미확정 |
| `stld_dttm` | 최종 결과가 난 일시 — 채워지면 결과가 더는 바뀌지 않는다 |
| `pend_expr_dttm` | 결과 대기의 기한 — 만료 배치가 이 값으로 기한 지난 건을 찾아 거절 확정한다 |
| `rpt_tx_hash` | DAW-CORE 가 온체인 제출 후 보고해 온 거래 해시 — 솔루션에 tx hash 를 알려줄 때 쓴다 |

### cmpl_outbox_l — 발행 아웃박스

verdict 가 확정되는 **같은 트랜잭션**에 발행할 settled 이벤트를 여기 적재한다(verdict 저장 + 발행 예약 = 한 커밋). 별도 relay 가 미발송(`P`)을 오래된 순으로 집어 `compliance` 큐로 보내고 `S` 로 표시한다. 매니저 `bcm_outbox_l`·코어 ADR-002 와 같은 패턴이라 세 서비스가 한 방식으로 발행하고, 저장 후 중단돼도 이벤트가 유실되지 않는다.

```sql
CREATE TABLE cmpl_outbox_l (
  evnt_id         VARCHAR(36)   PRIMARY KEY,  -- 이벤트ID (time-ordered UUID v7) · 컨슈머 dedup 키
  evnt_dt         VARCHAR(8)    NOT NULL,     -- 이벤트일자 — 조회·파티셔닝
  chk_id          VARCHAR(64)   NOT NULL,     -- 어느 확인 건의 이벤트인가 (cmpl_wdrl_chk_l)
  topic           VARCHAR(32)   NOT NULL,     -- 발행 큐: compliance
  payload         JSONB         NOT NULL,     -- 이벤트 본문 (withdrawal-check.settled)
  evnt_stcd       VARCHAR(1)    NOT NULL,     -- 발행상태 P:PENDING / D:DISPATCHED / F:FAILED / S:SUCCESS
  rtry_cnt        INT           NOT NULL,     -- 재시도횟수
  max_rtry_cnt    INT           NOT NULL,     -- 최대재시도횟수
  orgn_id         VARCHAR(36)   NULL,         -- 원본이벤트ID — 재발행·파생 추적
  trace_id        VARCHAR(64)   NULL,         -- 분산추적 — 게이트→코어 상관관계
  pub_dttm        VARCHAR(16)   NULL,         -- 최초 DISPATCHED 시각
  last_rtry_dttm  VARCHAR(16)   NULL,         -- 최종재시도일시
  err_msg         VARCHAR(1000) NULL,         -- 오류메시지 (마지막 실패 요약)
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
CREATE INDEX idx_cmpl_outbox_send ON cmpl_outbox_l (evnt_stcd, evnt_id);  -- 미발송(P) 오래된 순 = 시간정렬 UUID v7
```

| 컬럼 | 뜻 |
|---|---|
| `evnt_id` | time-ordered UUID v7 — PK 이자 컨슈머 dedup 키. relay 가 같은 행을 두 번 보내도 컨슈머가 이 값(및 checkId)으로 접는다 |
| `chk_id` | 이벤트의 출처 확인 건 — verdict 확정과 같은 트랜잭션에 적재된다 |
| `evnt_stcd` | 확정 시 `P`, relay 발송 성공 시 `S`, 실패 누적 시 `F` |
| 이벤트 유형 컬럼 없음 | 게이트가 발행하는 이벤트가 `withdrawal-check.settled` 하나뿐이라 두지 않는다 — 종류가 늘면 코어 어휘(`evt_typ_dvcd`)로 추가 |

### cmpl_pre_vrfc_l — 입금 사전 검증 기록

상대 거래소가 자금을 보내기 전에 먼저 보내오는 사전 검증(입금 예고)을 대조용으로 쌓아 두는 기록이다. 이 시점엔 자금이 아직 없고 정보만 먼저 도착한 상태다.

```sql
CREATE TABLE cmpl_pre_vrfc_l (
  vrfc_ref     VARCHAR(128)  PRIMARY KEY,   -- 솔루션이 발급한 검증 참조 UUID (벤더 값이라 길이 그대로)
  bnfc_addr    VARCHAR(128)  NOT NULL,      -- 우리 쪽 수취 주소
  tkn_smbl     VARCHAR(16)   NOT NULL,      -- 토큰 심볼
  amt          NUMERIC(78,0) NOT NULL,      -- 예고된 금액 — base unit 정수. 단위·대조 규칙은 미확정
  src_addr     VARCHAR(128)  NULL,          -- 보내는 쪽 주소 — 사전 검증 시점엔 비어 있을 수 있다
  tx_hash      VARCHAR(128)  NULL,          -- 상대가 전송 후 보고해 오면 채운다
  mtch_dttm    VARCHAR(16)   NULL,          -- 도착한 입금과 대조 성공한 일시
  rcv_dttm     VARCHAR(16)   NOT NULL,      -- 사전 검증 수신 일시 — 보존 기간 만료 기준
  -- 감사 4컬럼
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
CREATE INDEX idx_cmpl_pre_vrfc_hash ON cmpl_pre_vrfc_l (tx_hash);
CREATE INDEX idx_cmpl_pre_vrfc_addr ON cmpl_pre_vrfc_l (bnfc_addr, tkn_smbl);
```

| 컬럼 | 뜻 |
|---|---|
| `vrfc_ref` | 솔루션이 발급한 검증 참조 — 최초 수신·사후 tx hash 갱신·솔루션 조회가 모두 이 값으로 같은 건임을 잇는다 |
| `bnfc_addr` | 예고된 입금의 우리 쪽 수취 주소 — 도착한 입금과 맞춰 보는 기본 키 중 하나 |
| `amt` | 예고된 금액 — base unit 정수(NUMERIC). 단위·표현·대조 규칙은 미확정 |
| `src_addr` | 보내는 쪽 주소 — 사전 검증 시점엔 상대도 확정 못 할 수 있어 비어 있을 수 있다 |
| `tx_hash` | 상대가 전송 후 알려온 거래 해시로 채워진다 — 있으면 정확 매칭, 없으면 주소·자산·금액 매칭 |
| `mtch_dttm` | 도착한 입금과 대조 성공한 일시 — 채워진 행은 다른 입금과 다시 매칭하지 않는다(이중 매칭 방지) |
| `rcv_dttm` | 사전 검증이 수신된 일시 — 보존 기간 만료의 기준(기간 값 미정) |

PII(이름 등 신원 정보) 컬럼이 없는 것이 규칙이다 — 대조 키만 갖는다.

## 미확정

- **금액 정밀도** — `amt` 타입은 코어 규약대로 NUMERIC 로 확정. base unit 정수면 `NUMERIC(78,0)`, 표시 decimal 로 갈 경우 `NUMERIC(36,18)` — 사전 검증 메시지 단위·대조 규칙과 함께 정밀도 확정.
- **증적(`evdc_dvcd`) enum** — 솔루션별 증적 종류 확정 후.
- **다중 솔루션 라우팅 규칙** — 한 `vasp_id` 가 여러 솔루션 항목에 걸릴 때(국내·해외 동시) 어느 솔루션을 고를지.
- **사전 검증 기록 보존 기간** — 값 미정(국내 시간 규칙과 함께).
- **`cmpl_outbox_l` 보존** — 발송 완료(`S`) 건을 언제 정리할지 — 매니저 outbox 보존과 같은 결정 항목.
- **감사 컬럼 센티넬** — 자동 처리 행의 `empno`·`brcd` 시스템 센티넬 값을 코어 운영 규약과 맞춰 확정.

## 약어집

축약 규칙은 영어 단어의 모음 탈락. 이 문서가 쓰는 축약 전부다.

| 축약 | 원어 | | 축약 | 원어 |
|---|---|---|---|---|
| `cmpl` | compliance | | `vrdt` | verdict |
| `vasp` | virtual asset service provider | | `evdc` | evidence |
| `wdrl` | withdrawal | | `vrfc` | verification |
| `chk` | check | | `bnfc` | beneficiary |
| `soln` | solution | | `mtch` | match |
| `rchbl` | reachable | | `stld` | settled |
| `actv` | active (활성화) | | `occr` | occur |
| `acnt` | account | | `rqst` | request |
| `pend_expr` | pending expire | | `rpt` | report |
| `tkn_smbl` | token symbol | | `src` | source |
| `amt` | amount | | `nm` | name |
| `empno` | employee no | | `brcd` | branch code |
| `evnt` | event | | `rtry` | retry |
| `orgn` | origin | | `pub` | publish |
| `err` | error | | | |
