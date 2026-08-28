CREATE TABLE bcm_evnt_cmpl_l (
  evnt_id         VARCHAR(36) NOT NULL REFERENCES bcm_outbox_l (evnt_id),
  cnsmr_dvcd      VARCHAR(16) NOT NULL,
  cmpl_dttm       VARCHAR(16) NOT NULL,
  frst_reg_empno  VARCHAR(6)  NOT NULL,
  frst_reg_brcd   VARCHAR(4)  NOT NULL,
  last_chng_empno VARCHAR(6)  NOT NULL,
  last_chng_brcd  VARCHAR(4)  NOT NULL,
  PRIMARY KEY (evnt_id, cnsmr_dvcd),
  CHECK (cnsmr_dvcd IN ('DAW_CORE'))
);

CREATE INDEX idx_bcm_evnt_cmpl_consumer_time
  ON bcm_evnt_cmpl_l (cnsmr_dvcd, cmpl_dttm, evnt_id);
CREATE INDEX idx_bcm_outbox_sweep_operations
  ON bcm_outbox_l (topic, evnt_stcd, evnt_id);

CREATE TABLE bcm_swp_req_l (
  swp_req_id       VARCHAR(36)  PRIMARY KEY,
  ext_swp_req_id   VARCHAR(128) NOT NULL UNIQUE,
  req_hash         CHAR(64)     NOT NULL,
  ntwk_cd          VARCHAR(20)  NOT NULL,
  tkn_smbl         VARCHAR(16)  NOT NULL,
  swp_req_stcd     VARCHAR(16)  NOT NULL,
  item_cnt         INT          NOT NULL CHECK (item_cnt > 0),
  req_dttm         VARCHAR(16)  NOT NULL,
  fnsh_dttm        VARCHAR(16)  NULL,
  frst_reg_empno   VARCHAR(6)   NOT NULL,
  frst_reg_brcd    VARCHAR(4)   NOT NULL,
  last_chng_empno  VARCHAR(6)   NOT NULL,
  last_chng_brcd   VARCHAR(4)   NOT NULL,
  CHECK (swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'COMPLETED', 'PARTIAL', 'FAILED'))
);

CREATE TABLE bcm_swp_req_item_l (
  swp_req_item_id   VARCHAR(36) PRIMARY KEY,
  swp_req_id        VARCHAR(36) NOT NULL REFERENCES bcm_swp_req_l (swp_req_id),
  item_seq          INT         NOT NULL,
  acnt_id           VARCHAR(64) NOT NULL REFERENCES bcm_acnt_m (acnt_id),
  swp_req_item_stcd VARCHAR(16) NOT NULL,
  last_fail_cd      VARCHAR(64) NULL,
  frst_reg_empno    VARCHAR(6)  NOT NULL,
  frst_reg_brcd     VARCHAR(4)  NOT NULL,
  last_chng_empno   VARCHAR(6)  NOT NULL,
  last_chng_brcd    VARCHAR(4)  NOT NULL,
  UNIQUE (swp_req_id, item_seq),
  UNIQUE (swp_req_id, acnt_id),
  CHECK (item_seq > 0),
  CHECK (swp_req_item_stcd IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE bcm_swp_req_src_l (
  evnt_id          VARCHAR(36) PRIMARY KEY REFERENCES bcm_outbox_l (evnt_id),
  swp_req_item_id  VARCHAR(36) NOT NULL REFERENCES bcm_swp_req_item_l (swp_req_item_id),
  frst_reg_empno   VARCHAR(6)  NOT NULL,
  frst_reg_brcd    VARCHAR(4)  NOT NULL,
  last_chng_empno  VARCHAR(6)  NOT NULL,
  last_chng_brcd   VARCHAR(4)  NOT NULL
);

CREATE INDEX idx_bcm_swp_req_queue
  ON bcm_swp_req_l (swp_req_stcd, req_dttm, swp_req_id);
CREATE INDEX idx_bcm_swp_req_item_queue
  ON bcm_swp_req_item_l (swp_req_item_stcd, swp_req_id, item_seq);
CREATE INDEX idx_bcm_swp_req_src_item
  ON bcm_swp_req_src_l (swp_req_item_id, evnt_id);

-- 기존 실행 항목도 요청 항목을 반드시 가리키도록 legacy 요청 원장을 만들어 백필한다.
-- V14 이전 실행은 DAW 요청 API 도입 전 데이터이므로 실행 1건을 요청 1건으로 보존한다.
INSERT INTO bcm_swp_req_l
  (swp_req_id, ext_swp_req_id, req_hash, ntwk_cd, tkn_smbl, swp_req_stcd,
   item_cnt, req_dttm, fnsh_dttm,
   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
SELECT
  substr(md5('legacy-request:' || execution.swp_exec_id), 1, 8) || '-' ||
  substr(md5('legacy-request:' || execution.swp_exec_id), 9, 4) || '-' ||
  substr(md5('legacy-request:' || execution.swp_exec_id), 13, 4) || '-' ||
  substr(md5('legacy-request:' || execution.swp_exec_id), 17, 4) || '-' ||
  substr(md5('legacy-request:' || execution.swp_exec_id), 21, 12),
  'LEGACY:' || execution.swp_exec_id,
  execution.req_hash,
  execution.ntwk_cd,
  execution.tkn_smbl,
  CASE execution.swp_exec_stcd
    WHEN 'COMPLETED' THEN 'COMPLETED'
    WHEN 'PARTIAL' THEN 'PARTIAL'
    WHEN 'FAILED' THEN 'FAILED'
    ELSE 'PROCESSING'
  END,
  execution.item_cnt,
  execution.req_dttm,
  execution.fnsh_dttm,
  execution.frst_reg_empno,
  execution.frst_reg_brcd,
  execution.last_chng_empno,
  execution.last_chng_brcd
FROM bcm_swp_exec_l execution;

INSERT INTO bcm_swp_req_item_l
  (swp_req_item_id, swp_req_id, item_seq, acnt_id, swp_req_item_stcd, last_fail_cd,
   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
SELECT
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 1, 8) || '-' ||
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 9, 4) || '-' ||
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 13, 4) || '-' ||
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 17, 4) || '-' ||
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 21, 12),
  substr(md5('legacy-request:' || item.swp_exec_id), 1, 8) || '-' ||
  substr(md5('legacy-request:' || item.swp_exec_id), 9, 4) || '-' ||
  substr(md5('legacy-request:' || item.swp_exec_id), 13, 4) || '-' ||
  substr(md5('legacy-request:' || item.swp_exec_id), 17, 4) || '-' ||
  substr(md5('legacy-request:' || item.swp_exec_id), 21, 12),
  item.item_seq,
  item.acnt_id,
  CASE item.swp_item_stcd
    WHEN 'SUCCEEDED' THEN 'COMPLETED'
    WHEN 'FAILED' THEN 'FAILED'
    WHEN 'RETRY' THEN 'PENDING'
    ELSE 'PROCESSING'
  END,
  item.fail_cd,
  item.frst_reg_empno,
  item.frst_reg_brcd,
  item.last_chng_empno,
  item.last_chng_brcd
FROM bcm_swp_item_l item;

ALTER TABLE bcm_swp_item_l ADD COLUMN swp_req_item_id VARCHAR(36);

UPDATE bcm_swp_item_l item
SET swp_req_item_id =
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 1, 8) || '-' ||
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 9, 4) || '-' ||
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 13, 4) || '-' ||
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 17, 4) || '-' ||
  substr(md5('legacy-item:' || item.swp_exec_id || ':' || item.item_seq), 21, 12);

ALTER TABLE bcm_swp_item_l ALTER COLUMN swp_req_item_id SET NOT NULL;
ALTER TABLE bcm_swp_item_l
  ADD CONSTRAINT fk_bcm_swp_item_request_item
  FOREIGN KEY (swp_req_item_id) REFERENCES bcm_swp_req_item_l (swp_req_item_id);
CREATE INDEX idx_bcm_swp_item_request_item ON bcm_swp_item_l (swp_req_item_id, swp_exec_id, item_seq);

-- V14 이전에 FINALIZED 관찰만으로 만들어진 미claim target은 DAW 요청 근거가 없으므로 실행하지 않고 폐기한다.
-- 이미 실행에 claim된 target은 위 legacy 요청/항목으로 연결되어 기존 실행의 회수·대사를 계속한다.
DELETE FROM bcm_swp_trgt target
WHERE target.actv_swp_exec_id IS NULL
  AND target.actv_item_seq IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM bcm_swp_req_item_l request_item
    JOIN bcm_swp_req_l request ON request.swp_req_id = request_item.swp_req_id
    WHERE request_item.acnt_id = target.acnt_id
      AND request.ntwk_cd = target.ntwk_cd
      AND request.tkn_smbl = target.tkn_smbl
      AND request_item.swp_req_item_stcd IN ('PENDING', 'PROCESSING')
      AND request.swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'PARTIAL')
  );

-- bcm_evnt_cmpl_l·bcm_swp_req_src_l의 RESTRICT FK가 참조 outbox의 독립 삭제를 막는다.
-- outbox archive가 도입되기 전에는 이 원장들을 물리 삭제하지 않으며, 향후에는 같은 보존 단위로만 이관한다.
