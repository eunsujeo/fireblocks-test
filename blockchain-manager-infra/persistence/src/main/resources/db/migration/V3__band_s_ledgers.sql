-- Phase 10 T10.5 — DAW-CORE 계산 밴드S snapshot·proposal·reservation·execution 원장

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
  CONSTRAINT ck_bcm_bnds_snps_hash CHECK (
    snps_hash ~ '^[0-9a-f]{64}$' AND input_hash ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_bcm_bnds_snps_time CHECK (base_dttm < expr_dttm),
  CONSTRAINT ck_bcm_bnds_snps_amount CHECK (
    total_ast_krw_amt >= 0 AND obs_hot_krw_amt >= 0 AND
    obs_cold_krw_amt >= 0 AND efct_hot_krw_amt >= 0
  ),
  CONSTRAINT ck_bcm_bnds_snps_ratio CHECK (
    low_ratio >= 0 AND low_ratio < trgt_ratio AND trgt_ratio < up_ratio AND
    up_ratio <= 100 AND hot_ratio >= 0
  )
);
CREATE INDEX idx_bcm_bnds_snps_current ON bcm_bnds_snps_l (base_dttm DESC, expr_dttm DESC);

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
  CONSTRAINT ck_bcm_bnds_prop_hash CHECK (
    prop_hash ~ '^[0-9a-f]{64}$' AND input_hash ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_bcm_bnds_prop_yn CHECK (exec_able_yn IN ('Y','N')),
  CONSTRAINT ck_bcm_bnds_prop_amount CHECK (item_cnt > 0 AND total_krw_amt > 0 AND aft_hot_ratio >= 0)
);
CREATE INDEX idx_bcm_bnds_prop_snapshot ON bcm_bnds_prop_l (snps_id, reg_dttm DESC);

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
  CONSTRAINT ck_bcm_bnds_prop_item_amount CHECK (
    item_seq > 0 AND amt > 0 AND krw_amt > 0 AND exp_fee_amt >= 0
  )
);

ALTER TABLE bcm_chng_req_l ADD COLUMN aft_bnds_prop_id VARCHAR(36) NULL
  REFERENCES bcm_bnds_prop_l(prop_id);
ALTER TABLE bcm_chng_req_l DROP CONSTRAINT ck_bcm_chng_req_target;
ALTER TABLE bcm_chng_req_l ADD CONSTRAINT ck_bcm_chng_req_target CHECK (
  (tgt_dvcd = 'CONTRACT' AND aft_ctrt_vrsn_id IS NOT NULL AND
    aft_plcy_vrsn_id IS NULL AND aft_bnds_prop_id IS NULL) OR
  (tgt_dvcd = 'POLICY' AND aft_plcy_vrsn_id IS NOT NULL AND
    aft_ctrt_vrsn_id IS NULL AND aft_bnds_prop_id IS NULL) OR
  (tgt_dvcd = 'BAND_S' AND aft_bnds_prop_id IS NOT NULL AND
    aft_ctrt_vrsn_id IS NULL AND aft_plcy_vrsn_id IS NULL)
);

ALTER TABLE bcm_adm_actn_l DROP CONSTRAINT ck_bcm_adm_actn_type;
ALTER TABLE bcm_adm_actn_l ADD CONSTRAINT ck_bcm_adm_actn_type CHECK (
  actn_dvcd IN ('ACTIVATE','CANCEL','PAUSE','RESUME','EXECUTE')
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
    prop_hash ~ '^[0-9a-f]{64}$' AND input_hash ~ '^[0-9a-f]{64}$' AND
    exec_hash ~ '^[0-9a-f]{64}$'
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
  CONSTRAINT ck_bcm_bnds_exec_evt_hash CHECK (obs_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bcm_bnds_exec_evt_ids CHECK (
    (exec_stcd = 'SUBMIT_INTENT' AND ext_tx_id IS NOT NULL AND vndr_tx_id IS NULL) OR
    (exec_stcd = 'SUBMITTED' AND ext_tx_id IS NULL AND vndr_tx_id IS NOT NULL) OR
    (exec_stcd NOT IN ('SUBMIT_INTENT','SUBMITTED') AND ext_tx_id IS NULL AND vndr_tx_id IS NULL)
  )
);
CREATE UNIQUE INDEX ux_bcm_bnds_exec_evt_ext
  ON bcm_bnds_exec_evt_l(ext_tx_id) WHERE ext_tx_id IS NOT NULL;
CREATE UNIQUE INDEX ux_bcm_bnds_exec_evt_vndr
  ON bcm_bnds_exec_evt_l(vndr_tx_id) WHERE vndr_tx_id IS NOT NULL;
CREATE INDEX idx_bcm_bnds_exec_evt_current
  ON bcm_bnds_exec_evt_l(exec_id, item_seq, evt_seq DESC);

CREATE FUNCTION bcm_guard_band_execution() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  req bcm_chng_req_l%ROWTYPE;
  prop bcm_bnds_prop_l%ROWTYPE;
  snps bcm_bnds_snps_l%ROWTYPE;
  approval_count INTEGER;
  reject_count INTEGER;
  now_core VARCHAR(16) := to_char(current_timestamp AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS');
BEGIN
  SELECT * INTO STRICT req FROM bcm_chng_req_l WHERE req_id = NEW.req_id;
  SELECT * INTO STRICT prop FROM bcm_bnds_prop_l WHERE prop_id = NEW.prop_id;
  SELECT * INTO STRICT snps FROM bcm_bnds_snps_l WHERE snps_id = NEW.snps_id;
  IF req.tgt_dvcd <> 'BAND_S' OR req.risk_dvcd <> 'FUND' OR req.aft_bnds_prop_id <> NEW.prop_id OR
     req.tgt_snps_hash <> prop.prop_hash OR prop.snps_id <> NEW.snps_id OR
     prop.plcy_vrsn_id <> NEW.plcy_vrsn_id OR prop.prop_hash <> NEW.prop_hash OR
     prop.input_hash <> NEW.input_hash OR snps.input_hash <> NEW.input_hash OR
     snps.plcy_vrsn_id <> NEW.plcy_vrsn_id THEN
    RAISE EXCEPTION 'band execution does not match approved proposal' USING ERRCODE = 'check_violation';
  END IF;
  IF req.expr_dttm <= now_core OR snps.expr_dttm <= now_core OR snps.input_cmplt_yn <> 'Y' OR
     prop.exec_able_yn <> 'Y' THEN
    RAISE EXCEPTION 'band proposal is expired or blocked' USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM bcm_plcy_bind_m
     WHERE actv_plcy_vrsn_id = NEW.plcy_vrsn_id
  ) THEN
    RAISE EXCEPTION 'band policy version is not active' USING ERRCODE = 'check_violation';
  END IF;
  IF EXISTS (
    SELECT 1 FROM bcm_bnds_prop_item_l
     WHERE prop_id = NEW.prop_id AND (exec_able_yn <> 'Y' OR block_rsn_cd IS NOT NULL)
  ) THEN
    RAISE EXCEPTION 'band proposal has blocked items' USING ERRCODE = 'check_violation';
  END IF;
  SELECT count(*) FILTER (WHERE dcsn_dvcd = 'APPROVE'),
         count(*) FILTER (WHERE dcsn_dvcd = 'REJECT')
    INTO approval_count, reject_count
    FROM bcm_chng_dcsn_l WHERE req_id = NEW.req_id;
  IF reject_count > 0 OR approval_count < 1 THEN
    RAISE EXCEPTION 'band execution quorum is not satisfied' USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM bcm_adm_actn_l
     WHERE req_id = NEW.req_id AND actn_dvcd = 'EXECUTE' AND actn_stcd = 'INTENT'
  ) THEN
    RAISE EXCEPTION 'band execution intent is missing' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION bcm_guard_band_event() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  previous_state VARCHAR(24);
  previous_seq INTEGER;
  leg_type VARCHAR(24);
  dependency_seq INTEGER;
  dependency_state VARCHAR(24);
BEGIN
  SELECT item.leg_dvcd, item.dep_item_seq
    INTO STRICT leg_type, dependency_seq
    FROM bcm_bnds_exec_item_key item_key
    JOIN bcm_bnds_prop_item_l item
      ON item.prop_id = item_key.prop_id AND item.item_seq = item_key.item_seq
   WHERE item_key.exec_id = NEW.exec_id AND item_key.item_seq = NEW.item_seq;

  SELECT evt_seq, exec_stcd INTO previous_seq, previous_state
    FROM bcm_bnds_exec_evt_l
   WHERE exec_id = NEW.exec_id AND item_seq = NEW.item_seq
   ORDER BY evt_seq DESC LIMIT 1;

  IF previous_seq IS NULL THEN
    IF NEW.evt_seq <> 1 OR NEW.exec_stcd <> 'RESERVED' THEN
      RAISE EXCEPTION 'band item must start as RESERVED' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.evt_seq <> previous_seq + 1 THEN
    RAISE EXCEPTION 'band item event sequence is not contiguous' USING ERRCODE = 'check_violation';
  END IF;
  IF dependency_seq IS NOT NULL AND NEW.exec_stcd = 'SUBMIT_INTENT' THEN
    SELECT exec_stcd INTO dependency_state
      FROM bcm_bnds_exec_evt_l
     WHERE exec_id = NEW.exec_id AND item_seq = dependency_seq
     ORDER BY evt_seq DESC LIMIT 1;
    IF dependency_state <> 'RECONCILED' THEN
      RAISE EXCEPTION 'band item dependency is not reconciled' USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  IF NOT (
    (previous_state = 'RESERVED' AND NEW.exec_stcd IN ('SUBMIT_INTENT','RELEASED')) OR
    (previous_state = 'RESERVED' AND leg_type = 'COLD_DEPOSIT' AND NEW.exec_stcd IN ('FINALIZED','FAILED')) OR
    (previous_state = 'SUBMIT_INTENT' AND NEW.exec_stcd IN ('SUBMITTED','FAILED','RELEASED')) OR
    (previous_state = 'SUBMITTED' AND NEW.exec_stcd IN ('FINALIZED','FAILED')) OR
    (previous_state = 'FINALIZED' AND NEW.exec_stcd IN ('RECONCILED','FAILED'))
  ) THEN
    RAISE EXCEPTION 'invalid band item event transition % -> %', previous_state, NEW.exec_stcd
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_bnds_exec_guard
  BEFORE INSERT ON bcm_bnds_exec_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_band_execution();
CREATE TRIGGER trg_bcm_bnds_exec_evt_guard
  BEFORE INSERT ON bcm_bnds_exec_evt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_band_event();

CREATE TRIGGER trg_bcm_bnds_snps_append_only
  BEFORE UPDATE OR DELETE ON bcm_bnds_snps_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_bnds_prop_append_only
  BEFORE UPDATE OR DELETE ON bcm_bnds_prop_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_bnds_prop_item_append_only
  BEFORE UPDATE OR DELETE ON bcm_bnds_prop_item_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_bnds_exec_append_only
  BEFORE UPDATE OR DELETE ON bcm_bnds_exec_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_bnds_exec_item_append_only
  BEFORE UPDATE OR DELETE ON bcm_bnds_exec_item_key
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_bnds_exec_evt_append_only
  BEFORE UPDATE OR DELETE ON bcm_bnds_exec_evt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
