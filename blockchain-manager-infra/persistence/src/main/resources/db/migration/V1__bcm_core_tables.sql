-- bcm_ 16테이블 — docs/design/03-bcm-db.md + 07-asset-master.md (코어 daw_ 규약: 일시 VARCHAR(16) · 일자 VARCHAR(8)
-- · _yn VARCHAR(1) · 벤더 id VARCHAR(64) · 감사 4컬럼). 자동 처리 행의 감사 센티넬: empno='SYSTEM' · brcd='9999'.

-- 계정 매핑 — ref UNIQUE 가 계정 생성 멱등의 최종 방어
CREATE TABLE bcm_acnt_m (
  acnt_id       VARCHAR(64)  PRIMARY KEY,     -- 매니저가 발급하는 계정 매핑 id
  acnt_typ_dvcd VARCHAR(2)   NOT NULL,        -- 계정유형 CU:고객 / SY:시스템(운영) — ref 가 어느 ID 공간의 값인지 가린다
  ref           VARCHAR(64)  NOT NULL,        -- 백엔드 참조 키 = DAW-CORE 계정 ID 그대로 (접두사 없음)
  vndr_vlt_id   VARCHAR(64)  NOT NULL,        -- 벤더 vault id — 백엔드에 노출하지 않는다
  reg_dttm      VARCHAR(16)  NOT NULL,
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  UNIQUE (acnt_typ_dvcd, ref)                 -- 멱등의 물리 근거 — ref 단독으로는 유일하지 않다
);

-- 벤더 블록체인 카탈로그 — 일 1회 동기화하고 사람이 ntwk_cd를 붙여 채택한다
CREATE TABLE bcm_blkc_m (
  vndr_blkc_id  VARCHAR(64)  PRIMARY KEY,
  ntwk_cd       VARCHAR(20)  NULL,
  chain_id      BIGINT       NULL,
  dspl_nm       VARCHAR(64)  NOT NULL,
  test_yn       VARCHAR(1)   NOT NULL,
  deprc_yn      VARCHAR(1)   NOT NULL,
  sync_dttm     VARCHAR(16)  NOT NULL,
  frst_reg_empno  VARCHAR(6) NOT NULL,
  frst_reg_brcd   VARCHAR(4) NOT NULL,
  last_chng_empno VARCHAR(6) NOT NULL,
  last_chng_brcd  VARCHAR(4) NOT NULL,
  UNIQUE (ntwk_cd)
);

-- 우리 (네트워크, 토큰) → 벤더 assetId. 행 존재가 곧 벤더 호출 가능 여부다 (사용여부 플래그 없음)
CREATE TABLE bcm_vndr_ast_m (
  ntwk_cd       VARCHAR(20)  NOT NULL,
  tkn_smbl      VARCHAR(16)  NOT NULL,
  vndr_ast_id   VARCHAR(64)  NOT NULL,
  cntr_addr     VARCHAR(128) NULL,
  reg_dttm      VARCHAR(16)  NOT NULL,
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (ntwk_cd, tkn_smbl),
  UNIQUE (vndr_ast_id),
  FOREIGN KEY (ntwk_cd) REFERENCES bcm_blkc_m (ntwk_cd)
);

-- 주소 매핑 — (계정, 자산)당 주소 하나, PK 가 주소 발급 멱등의 물리 근거
CREATE TABLE bcm_addr_m (
  acnt_id     VARCHAR(64)  NOT NULL,
  ntwk_cd     VARCHAR(20)  NOT NULL,       -- 네트워크 코드
  tkn_smbl    VARCHAR(16)  NOT NULL,
  dpst_addr   VARCHAR(128) NOT NULL,          -- 발급된 입금 주소
  reg_dttm    VARCHAR(16)  NOT NULL,
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (acnt_id, ntwk_cd, tkn_smbl)
);
CREATE INDEX idx_bcm_addr_lookup ON bcm_addr_m (dpst_addr, ntwk_cd); -- 입금 감지의 역방향 조회

-- 수신 알림 원본 — transactional inbox. noti_id PK 가 중복 수신 방어
CREATE TABLE bcm_whk_l (
  noti_id       VARCHAR(64)   PRIMARY KEY,    -- 벤더가 알림마다 붙이는 UUID
  evnt_typ      VARCHAR(64)   NOT NULL,       -- 벤더 eventType — 벤더 값 그대로
  vndr_tx_id    VARCHAR(64)   NULL,
  payload       TEXT          NOT NULL,       -- 수신 바이트 그대로 — 파싱·재직렬화 금지
  payload_hash  CHAR(64)      NOT NULL,       -- 수신 바이트 SHA-256 소문자 hex
  sign_vl       TEXT          NOT NULL,       -- 검증을 통과한 수신 서명 헤더 원문
  rcv_dttm      VARCHAR(16)   NOT NULL,
  prcs_stcd     VARCHAR(1)    NOT NULL,       -- 판단 처리 상태 P:미처리 / S:처리완료 / F:격리(poison)
  rtry_cnt      INT           NOT NULL,       -- 판단 시도 횟수 — 상한 초과 시 F 로 격리
  err_msg       VARCHAR(1000) NULL,           -- 마지막 실패 요약 (격리 사유)
  prcs_dttm     VARCHAR(16)   NULL,
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
CREATE INDEX idx_bcm_whk_pick ON bcm_whk_l (prcs_stcd, rcv_dttm);     -- 판단 워커의 집기 — 미처리(P) 오래된 순

-- 거래 운영 상태 — 전이 판정·이벤트 발행·막힘 점검의 기준 행
CREATE TABLE bcm_tx_l (
  vndr_tx_id      VARCHAR(64)  PRIMARY KEY,   -- 최초 벤더 tx id = 논리 root 거래 id
  actv_tx_id      VARCHAR(64)  NOT NULL UNIQUE, -- 현재 RBF head 또는 먼저 채굴된 승자
  ext_tx_id       VARCHAR(128) NULL UNIQUE,   -- 제출 건의 백엔드 요청 키 — 재제출 중복 차단, 입금은 NULL
  acnt_id         VARCHAR(64)  NOT NULL,      -- 귀속 계정 — 이벤트 파티션 키
  ntwk_cd         VARCHAR(20)  NOT NULL,      -- 네트워크 코드
  tkn_smbl        VARCHAR(16)  NOT NULL,
  tx_hash         VARCHAR(128) NULL,          -- active 물리 거래의 온체인 hash
  last_pub_stcd   VARCHAR(16)  NOT NULL,      -- 마지막으로 발행한 TxStatus
  cnfm_cnt        INT          NOT NULL,      -- 큰 값으로만 갱신 (감소 금지)
  vndr_sub_stcd   VARCHAR(64)  NULL,          -- 마지막 알림의 벤더 subStatus 원어 — 운영 조사용, 이벤트 미탑재
  vndr_ntwk_stcd  VARCHAR(64)  NULL,          -- 마지막 알림의 벤더 networkStatus 원어 — 운영 조사용, 이벤트 미탑재
  stall_alrt_dttm VARCHAR(16)  NULL,          -- 막힘 경보 일시 — 있으면 다음 주기 건너뜀
  frst_dtct_dttm  VARCHAR(16)  NOT NULL,
  last_chng_dttm  VARCHAR(16)  NOT NULL,      -- 감소 금지 — 막힘 점검의 기준
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
CREATE INDEX idx_bcm_tx_stall ON bcm_tx_l (last_pub_stcd, last_chng_dttm)
  WHERE stall_alrt_dttm IS NULL AND last_pub_stcd IN ('SUBMITTED', 'CONFIRMED');

-- 제출 원장 — externalTxId 멱등 판정과 벤더 호출 전 요청 영속화의 기준 행
CREATE TABLE bcm_sbmt_l (
  ext_tx_id      VARCHAR(128) PRIMARY KEY,
  req_hash       CHAR(64)     NOT NULL,
  hash_vrsn      VARCHAR(8)   NOT NULL,
  sbmt_stcd      VARCHAR(16)  NOT NULL,       -- REQUESTED / SUBMITTED / FAILED
  claim_id       VARCHAR(36)  NULL,
  claim_exp_dttm VARCHAR(16)  NULL,
  tx_dvcd        VARCHAR(16)  NOT NULL,       -- WITHDRAWAL / INTERNAL / SWEEP_APPROVE / SWEEP_BATCH
  vndr_tx_id     VARCHAR(64)  NULL,
  swp_exec_id    VARCHAR(36)  NULL,           -- SWEEP_BATCH일 때 실행 원장 연결
  snd_acnt_id    VARCHAR(64)  NOT NULL,
  rcv_dvcd       VARCHAR(16)  NOT NULL,       -- ADDRESS / ACCOUNT / WHITELISTED
  rcv_vl         VARCHAR(128) NOT NULL,
  ntwk_cd        VARCHAR(20)  NOT NULL,
  tkn_smbl       VARCHAR(16)  NOT NULL,
  trsf_amt       NUMERIC(36,18) NOT NULL,
  call_data      TEXT         NULL,           -- cc-v1 CONTRACT_CALL calldata 소문자 hex
  req_dttm       VARCHAR(16)  NOT NULL,
  rsp_dttm       VARCHAR(16)  NULL,
  last_chck_dttm VARCHAR(16)  NULL,           -- 미결 제출 점검의 마지막 벤더 조회 시각
  chck_cnt       INT          NOT NULL DEFAULT 0, -- 미결 조회 횟수 — 백오프·경보 기준
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  CHECK ((tx_dvcd IN ('SWEEP_APPROVE', 'SWEEP_BATCH')) = (call_data IS NOT NULL)),
  CHECK (call_data IS NULL OR call_data ~ '^0x([0-9a-f][0-9a-f])+$')
);
CREATE UNIQUE INDEX ux_bcm_sbmt_vndr_tx ON bcm_sbmt_l (vndr_tx_id) WHERE vndr_tx_id IS NOT NULL;
CREATE INDEX idx_bcm_sbmt_open ON bcm_sbmt_l (sbmt_stcd, last_chck_dttm, req_dttm);

-- 발행 outbox — 상태 갱신과 같은 트랜잭션으로 적재(P), relay 가 evnt_id 순으로 발송(S)
CREATE TABLE bcm_outbox_l (
  evnt_id         VARCHAR(36)   PRIMARY KEY,  -- time-ordered UUID v7 · 컨슈머 dedup 키
  evnt_dt         VARCHAR(8)    NOT NULL,
  vndr_tx_id      VARCHAR(64)   NOT NULL,     -- 집합체ID(코어 agg_id 대응)
  agg_typ_dvcd    VARCHAR(2)    NOT NULL,     -- TX:거래 / DL:델타
  evt_typ_dvcd    VARCHAR(4)    NOT NULL,     -- TXCK/TXCF/TXFL — 코어 정합
  topic           VARCHAR(32)   NOT NULL,     -- deposit / withdrawal / internal-events
  payload         JSONB         NOT NULL,
  evnt_stcd       VARCHAR(1)    NOT NULL,     -- P:PENDING / D:DISPATCHED / F:FAILED / S:SUCCESS
  rtry_cnt        INT           NOT NULL,
  max_rtry_cnt    INT           NOT NULL,
  orgn_id         VARCHAR(36)   NULL,         -- 원본이벤트ID — 재발행·파생 추적
  trace_id        VARCHAR(64)   NULL,
  pub_dttm        VARCHAR(16)   NULL,
  last_rtry_dttm  VARCHAR(16)   NULL,
  err_msg         VARCHAR(1000) NULL,
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);
CREATE INDEX idx_bcm_outbox_send ON bcm_outbox_l (evnt_stcd, evnt_id); -- 미발행(P) 오래된 순 = 시간정렬 UUID v7

-- sweep 대상 — (계정, 자산)당 한 행, batch 실행 항목이 claim한다
CREATE TABLE bcm_swp_trgt (
  acnt_id       VARCHAR(64)  NOT NULL,
  ntwk_cd       VARCHAR(20)  NOT NULL,       -- 네트워크 코드
  tkn_smbl      VARCHAR(16)  NOT NULL,
  reg_dttm      VARCHAR(16)  NOT NULL,
  actv_swp_exec_id VARCHAR(36) NULL,           -- 현재 claim한 sweep 실행
  actv_item_seq INT          NULL,             -- 실행 안의 항목 순번
  try_cnt       INT          NOT NULL,
  last_try_dttm VARCHAR(16)  NULL,
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (acnt_id, ntwk_cd, tkn_smbl),
  CHECK ((actv_swp_exec_id IS NULL) = (actv_item_seq IS NULL))
);

-- 고객 vault별·sweep 컨트랙트별 allowance 관찰 상태
CREATE TABLE bcm_swp_auth_m (
  acnt_id         VARCHAR(64)    NOT NULL,
  ntwk_cd         VARCHAR(20)    NOT NULL,
  tkn_smbl        VARCHAR(16)    NOT NULL,
  swp_ctrt_addr   VARCHAR(128)   NOT NULL,
  alwnc_cap       NUMERIC(36,18) NOT NULL,
  obs_alwnc       NUMERIC(36,18) NOT NULL,
  auth_stcd       VARCHAR(16)    NOT NULL,
  aprv_ext_tx_id  VARCHAR(128)   NULL,
  aprv_vndr_tx_id VARCHAR(64)    NULL,
  last_chck_dttm  VARCHAR(16)    NOT NULL,
  frst_reg_empno  VARCHAR(6)     NOT NULL,
  frst_reg_brcd   VARCHAR(4)     NOT NULL,
  last_chng_empno VARCHAR(6)     NOT NULL,
  last_chng_brcd  VARCHAR(4)     NOT NULL,
  PRIMARY KEY (acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr)
);

-- sweep 최상위 batch 실행
CREATE TABLE bcm_swp_exec_l (
  swp_exec_id      VARCHAR(36)    PRIMARY KEY,
  ext_tx_id        VARCHAR(128)   NOT NULL UNIQUE,
  req_hash         CHAR(64)       NOT NULL,
  ntwk_cd          VARCHAR(20)    NOT NULL,
  tkn_smbl         VARCHAR(16)    NOT NULL,
  opr_acnt_id      VARCHAR(64)    NOT NULL,
  swp_ctrt_addr    VARCHAR(128)   NOT NULL,
  swp_exec_stcd    VARCHAR(16)    NOT NULL,
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

-- 최상위 batch 실행 아래 원천 vault 이동 N건
CREATE TABLE bcm_swp_item_l (
  swp_exec_id      VARCHAR(36)    NOT NULL,
  item_seq         INT            NOT NULL,
  acnt_id          VARCHAR(64)    NOT NULL,
  src_addr         VARCHAR(128)   NOT NULL,
  req_amt          NUMERIC(36,18) NOT NULL,
  actl_amt         NUMERIC(36,18) NULL,
  swp_item_stcd    VARCHAR(16)    NOT NULL,
  fail_cd          VARCHAR(64)    NULL,
  log_idx          INT            NULL,
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

-- boost 이력 — 호출 전 intent와 결과를 함께 보존
CREATE TABLE bcm_boost_l (
  orig_tx_id      VARCHAR(64)  NOT NULL,
  try_seq         INT          NOT NULL,
  ext_tx_id       VARCHAR(128) NOT NULL UNIQUE,
  bst_stcd        VARCHAR(16)  NOT NULL,
  claim_id        VARCHAR(36)  NULL,
  claim_exp_dttm  VARCHAR(16)  NULL,
  rplc_tx_id      VARCHAR(64)  NOT NULL,
  rplc_tx_hash    VARCHAR(128) NOT NULL,
  fee_lvl         VARCHAR(16)  NOT NULL,
  gasless_yn      VARCHAR(1)   NOT NULL,
  new_tx_id       VARCHAR(64)  NULL UNIQUE,
  req_dttm        VARCHAR(16)  NOT NULL,
  rsp_dttm        VARCHAR(16)  NULL,
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (orig_tx_id, try_seq),
  FOREIGN KEY (orig_tx_id) REFERENCES bcm_tx_l(vndr_tx_id)
);
CREATE INDEX idx_bcm_boost_open ON bcm_boost_l (bst_stcd, req_dttm);

-- 주기 작업 상태 — heartbeat · tx 대사 커서
CREATE TABLE bcm_job_m (
  job_nm         VARCHAR(64)  PRIMARY KEY,
  last_run_dttm  VARCHAR(16)  NOT NULL,       -- heartbeat
  last_scs_dttm  VARCHAR(16)  NULL,           -- tx 대사의 대조 범위 커서
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL
);

-- 자산별 LOW/MEDIUM/HIGH 네트워크 수수료 견적 시계열
CREATE TABLE bcm_fee_qt_l (
  ntwk_cd          VARCHAR(20)    NOT NULL,
  tkn_smbl         VARCHAR(16)    NOT NULL,
  obs_dttm         VARCHAR(16)    NOT NULL,
  fee_lvl          VARCHAR(16)    NOT NULL,
  vndr_ast_id      VARCHAR(64)    NOT NULL,
  fee_per_byte     NUMERIC(36,18) NULL,
  gas_price        NUMERIC(36,18) NULL,
  ntwk_fee         NUMERIC(36,18) NULL,
  base_fee         NUMERIC(36,18) NULL,
  priority_fee     NUMERIC(36,18) NULL,
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

-- finalize 트랜잭션 원본 — 장기 보관 (월 단위 파티션, 대상 월 전에 배포 역할이 선생성)
CREATE TABLE bcm_raw_tx_l (
  base_dt        VARCHAR(8)   NOT NULL,       -- 적재 기준일 = 파티션 키
  vndr_tx_id     VARCHAR(64)  NOT NULL,
  ext_tx_id      VARCHAR(128) NULL,
  tx_hash        VARCHAR(128) NULL,
  addr           VARCHAR(128) NOT NULL,       -- 입금은 수취 주소, 출금은 출발 주소
  ntwk_cd        VARCHAR(20)  NOT NULL,   -- 네트워크 코드
  tkn_smbl       VARCHAR(16)  NOT NULL,
  final_stcd     VARCHAR(16)  NOT NULL,       -- 도달한 최종 상태
  payload        TEXT         NOT NULL,       -- 받은 바이트 그대로, 가공 금지 (해시가 원문 바이트 기준이라 TEXT)
  payload_hash   CHAR(64)     NOT NULL,       -- bcm_whk_l 의 수신 바이트 SHA-256을 그대로 복사 (재계산 금지)
  sign_vl        TEXT         NOT NULL,       -- 수신 서명 헤더 원문 — bcm_whk_l 에서 그대로 복사
  rcv_dttm       VARCHAR(16)  NOT NULL,
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (base_dt, vndr_tx_id)
) PARTITION BY RANGE (base_dt);
CREATE INDEX idx_bcm_raw_tx_hash ON bcm_raw_tx_l (tx_hash);
CREATE INDEX idx_bcm_raw_tx_addr ON bcm_raw_tx_l (addr, base_dt);
CREATE INDEX idx_bcm_raw_tx_vendor ON bcm_raw_tx_l (vndr_tx_id, rcv_dttm);
