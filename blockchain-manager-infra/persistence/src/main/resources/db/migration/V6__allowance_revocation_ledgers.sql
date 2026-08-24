-- Phase 10 T10.6.3 — 승인된 allowance 전량 회수 snapshot·항목·event 원장

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

CREATE FUNCTION bcm_allowance_revocation_snapshot_current(execution_id VARCHAR) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT NOT EXISTS (
           SELECT 1
             FROM bcm_alwnc_rvok_item_l item
             LEFT JOIN bcm_acnt_m account ON account.acnt_id = item.acnt_id
             LEFT JOIN bcm_addr_m address
               ON address.acnt_id = item.acnt_id AND address.ntwk_cd = item.ntwk_cd AND address.tkn_smbl = item.tkn_smbl
             LEFT JOIN bcm_vndr_ast_m asset
               ON asset.ntwk_cd = item.ntwk_cd AND asset.tkn_smbl = item.tkn_smbl
            WHERE item.rvok_exec_id = execution_id
              AND (account.acnt_id IS NULL OR account.vndr_vlt_id <> item.src_vlt_id OR
                   address.acnt_id IS NULL OR lower(address.dpst_addr) <> lower(item.ownr_addr) OR
                   asset.ntwk_cd IS NULL OR asset.cntr_addr IS NULL OR
                   lower(asset.cntr_addr) <> lower(item.tkn_ctrt_addr))
         )
     AND NOT EXISTS (
           SELECT 1
             FROM bcm_swp_auth_m auth
             JOIN bcm_alwnc_rvok_exec_l execution ON execution.rvok_exec_id = execution_id
            WHERE auth.ntwk_cd = execution.ntwk_cd
              AND lower(auth.swp_ctrt_addr) = lower(execution.swp_ctrt_addr)
              AND auth.obs_alwnc > 0
              AND NOT EXISTS (
                SELECT 1
                  FROM bcm_alwnc_rvok_item_l item
                 WHERE item.rvok_exec_id = execution_id
                   AND item.acnt_id = auth.acnt_id
                   AND item.ntwk_cd = auth.ntwk_cd
                   AND item.tkn_smbl = auth.tkn_smbl
                   AND lower(item.swp_ctrt_addr) = lower(auth.swp_ctrt_addr)
              )
         );
$$;

CREATE FUNCTION bcm_guard_allowance_revocation_request() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  execution bcm_alwnc_rvok_exec_l%ROWTYPE;
  target_count INTEGER;
BEGIN
  IF NEW.tgt_dvcd <> 'ALLOWANCE_REVOKE' THEN
    RETURN NEW;
  END IF;
  SELECT * INTO STRICT execution
    FROM bcm_alwnc_rvok_exec_l WHERE rvok_exec_id = NEW.aft_alwnc_rvok_id;
  SELECT count(*) INTO target_count
    FROM bcm_alwnc_rvok_item_l WHERE rvok_exec_id = execution.rvok_exec_id;
  IF NEW.risk_dvcd <> 'FUND' OR NEW.scope_id <> execution.ntwk_cd || ':SWEEP' OR
     NEW.base_bind_rvsn <> execution.ctrt_bind_rvsn OR
     NEW.tgt_snps_hash <> execution.tgt_snps_hash OR target_count <> execution.item_cnt OR
     NOT bcm_allowance_revocation_snapshot_current(execution.rvok_exec_id) THEN
    RAISE EXCEPTION 'allowance revocation request does not match target snapshot'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION bcm_guard_allowance_revocation_event() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  target bcm_alwnc_rvok_item_l%ROWTYPE;
  execution bcm_alwnc_rvok_exec_l%ROWTYPE;
  request bcm_chng_req_l%ROWTYPE;
  previous_state VARCHAR(24);
  previous_seq INTEGER;
  approval_count INTEGER;
  reject_count INTEGER;
  now_core VARCHAR(16) := to_char(current_timestamp AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS');
BEGIN
  SELECT * INTO STRICT target FROM bcm_alwnc_rvok_item_l
   WHERE rvok_exec_id = NEW.rvok_exec_id AND item_seq = NEW.item_seq;
  SELECT * INTO STRICT execution FROM bcm_alwnc_rvok_exec_l
   WHERE rvok_exec_id = NEW.rvok_exec_id;
  SELECT * INTO STRICT request FROM bcm_chng_req_l
   WHERE aft_alwnc_rvok_id = NEW.rvok_exec_id;
  SELECT rvok_stcd, evt_seq INTO previous_state, previous_seq
    FROM bcm_alwnc_rvok_evt_l
   WHERE rvok_exec_id = NEW.rvok_exec_id AND item_seq = NEW.item_seq
   ORDER BY evt_seq DESC LIMIT 1;

  IF NEW.evt_seq <> COALESCE(previous_seq, 0) + 1 THEN
    RAISE EXCEPTION 'allowance revocation event sequence must be contiguous'
      USING ERRCODE = 'check_violation';
  END IF;
  IF (previous_state IS NULL AND NEW.rvok_stcd <> 'RESERVED') OR
     (previous_state = 'RESERVED' AND NEW.rvok_stcd NOT IN ('SUBMIT_INTENT','ZERO_CONFIRMED','FAILED')) OR
     (previous_state = 'SUBMIT_INTENT' AND NEW.rvok_stcd NOT IN ('SUBMIT_INTENT','SUBMITTED','ZERO_CONFIRMED','FAILED')) OR
     (previous_state = 'SUBMITTED' AND NEW.rvok_stcd NOT IN ('ZERO_CONFIRMED','FAILED')) OR
     (previous_state = 'FAILED' AND NEW.rvok_stcd NOT IN ('SUBMIT_INTENT','SUBMITTED','ZERO_CONFIRMED')) OR
     previous_state = 'ZERO_CONFIRMED' THEN
    RAISE EXCEPTION 'allowance revocation event transition is not allowed'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.ext_tx_id IS NOT NULL AND NEW.ext_tx_id <> target.ext_tx_id THEN
    RAISE EXCEPTION 'allowance revocation external transaction id does not match target'
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.rvok_stcd IN ('RESERVED','SUBMIT_INTENT') THEN
    IF request.tgt_dvcd <> 'ALLOWANCE_REVOKE' OR request.risk_dvcd <> 'FUND' OR
       request.tgt_snps_hash <> execution.tgt_snps_hash OR
       request.base_bind_rvsn <> execution.ctrt_bind_rvsn OR request.expr_dttm <= now_core THEN
      RAISE EXCEPTION 'allowance revocation approval snapshot is stale'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM bcm_ctrt_bind_m
       WHERE ctrt_scope_id = execution.ntwk_cd || ':SWEEP' AND use_dvcd = 'SWEEP' AND
             actv_ctrt_vrsn_id = execution.ctrt_vrsn_id AND bind_rvsn = execution.ctrt_bind_rvsn
    ) OR NOT EXISTS (
      SELECT 1 FROM bcm_ctrt_vrsn_l
       WHERE ctrt_vrsn_id = execution.ctrt_vrsn_id AND ntwk_cd = execution.ntwk_cd AND
             use_dvcd = 'SWEEP' AND lower(ctrt_addr) = lower(execution.swp_ctrt_addr)
    ) THEN
      RAISE EXCEPTION 'allowance revocation contract binding is stale'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NOT bcm_allowance_revocation_snapshot_current(execution.rvok_exec_id) THEN
      RAISE EXCEPTION 'allowance revocation target snapshot is stale'
        USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*) FILTER (WHERE dcsn_dvcd = 'APPROVE'),
           count(*) FILTER (WHERE dcsn_dvcd = 'REJECT')
      INTO approval_count, reject_count
      FROM bcm_chng_dcsn_l WHERE req_id = request.req_id;
    IF reject_count > 0 OR approval_count < 1 THEN
      RAISE EXCEPTION 'allowance revocation quorum is not satisfied'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM bcm_adm_actn_l
       WHERE req_id = request.req_id AND actn_dvcd = 'EXECUTE' AND actn_stcd = 'INTENT'
    ) THEN
      RAISE EXCEPTION 'allowance revocation execution intent is missing'
        USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
      SELECT 1 FROM bcm_swp_exec_l
       WHERE ntwk_cd = execution.ntwk_cd AND lower(swp_ctrt_addr) = lower(execution.swp_ctrt_addr) AND
             swp_exec_stcd IN ('READY','SUBMITTING','SUBMITTED','RECONCILING')
    ) OR EXISTS (
      SELECT 1 FROM bcm_swp_trgt
       WHERE acnt_id = target.acnt_id AND ntwk_cd = target.ntwk_cd AND tkn_smbl = target.tkn_smbl AND
             actv_swp_exec_id IS NOT NULL
    ) THEN
      RAISE EXCEPTION 'allowance revocation is blocked by an active sweep batch'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_alwnc_rvok_request_guard
  BEFORE INSERT ON bcm_chng_req_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_allowance_revocation_request();
CREATE TRIGGER trg_bcm_alwnc_rvok_event_guard
  BEFORE INSERT ON bcm_alwnc_rvok_evt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_allowance_revocation_event();

CREATE TRIGGER trg_bcm_alwnc_rvok_exec_append_only
  BEFORE UPDATE OR DELETE ON bcm_alwnc_rvok_exec_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_alwnc_rvok_item_append_only
  BEFORE UPDATE OR DELETE ON bcm_alwnc_rvok_item_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_alwnc_rvok_evt_append_only
  BEFORE UPDATE OR DELETE ON bcm_alwnc_rvok_evt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
