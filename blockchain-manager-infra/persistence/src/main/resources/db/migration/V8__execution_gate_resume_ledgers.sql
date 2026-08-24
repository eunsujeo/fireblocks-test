-- Phase 10 T10.6.5 — 강화 재개 요청 snapshot과 실행 직전 외부 재검사 원장

ALTER TABLE bcm_exec_gate_evt_l ADD COLUMN rsm_req_id VARCHAR(36) NULL
  REFERENCES bcm_chng_req_l(req_id);
ALTER TABLE bcm_exec_gate_evt_l DROP CONSTRAINT ck_bcm_exec_gate_state;
ALTER TABLE bcm_exec_gate_evt_l ADD CONSTRAINT ck_bcm_exec_gate_state
  CHECK (gate_stcd IN ('STOPPED','RESUMED'));
ALTER TABLE bcm_exec_gate_evt_l ADD CONSTRAINT ck_bcm_exec_gate_resume
  CHECK (
    (gate_stcd = 'STOPPED' AND rsm_req_id IS NULL) OR
    (gate_stcd = 'RESUMED' AND rsm_req_id IS NOT NULL)
  );

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
  CONSTRAINT ck_bcm_exec_gate_rsm_uri CHECK (length(trim(cause_evdc_uri)) > 0),
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
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_seq CHECK (chk_seq > 0),
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_rpc CHECK (rpc1_id <> rpc2_id),
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_state CHECK (
    chk_stcd IN ('READY','DRIFT','STALE','UNCONFIRMED','ERROR')
  ),
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_yn CHECK (
    (tap_blck_yn IS NULL OR tap_blck_yn IN ('Y','N')) AND
    (rpc1_pause_yn IS NULL OR rpc1_pause_yn IN ('Y','N')) AND
    (rpc2_pause_yn IS NULL OR rpc2_pause_yn IN ('Y','N'))
  ),
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_hash CHECK (
    snps_hash ~ '^[0-9a-f]{64}$' AND issue_hash ~ '^[0-9a-f]{64}$' AND
    (rpc1_oprtr_hash IS NULL OR rpc1_oprtr_hash ~ '^[0-9a-f]{64}$') AND
    (rpc2_oprtr_hash IS NULL OR rpc2_oprtr_hash ~ '^[0-9a-f]{64}$')
  ),
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_ready CHECK (
    chk_stcd <> 'READY' OR (
      tap_blck_yn = 'N' AND tap_obs_dttm IS NOT NULL AND
      rpc1_blck_no = pin_blck_no AND rpc2_blck_no = pin_blck_no AND
      rpc1_pause_yn = 'N' AND rpc2_pause_yn = 'N' AND
      rpc1_oprtr_hash IS NOT NULL AND rpc2_oprtr_hash IS NOT NULL AND
      rpc1_obs_dttm IS NOT NULL AND rpc2_obs_dttm IS NOT NULL
    )
  ),
  CONSTRAINT ck_bcm_exec_gate_rsm_chk_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);
CREATE INDEX idx_bcm_exec_gate_rsm_chk_current
  ON bcm_exec_gate_rsm_chk_l(rsm_id, chk_seq DESC);

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

CREATE FUNCTION bcm_allowance_revocation_completed(execution_id VARCHAR) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT execution.item_cnt = (
           SELECT count(*) FROM bcm_alwnc_rvok_item_l item WHERE item.rvok_exec_id = execution.rvok_exec_id
         )
     AND execution.item_cnt = (
           SELECT count(*)
             FROM bcm_alwnc_rvok_item_l item
            WHERE item.rvok_exec_id = execution.rvok_exec_id
              AND (SELECT event.rvok_stcd
                     FROM bcm_alwnc_rvok_evt_l event
                    WHERE event.rvok_exec_id = item.rvok_exec_id AND event.item_seq = item.item_seq
                    ORDER BY event.evt_seq DESC LIMIT 1) = 'ZERO_CONFIRMED'
         )
     AND bcm_allowance_revocation_snapshot_current(execution.rvok_exec_id)
    FROM bcm_alwnc_rvok_exec_l execution
   WHERE execution.rvok_exec_id = execution_id;
$$;

CREATE FUNCTION bcm_guard_execution_gate_resume_snapshot() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  stopped bcm_exec_gate_evt_l%ROWTYPE;
  binding bcm_ctrt_bind_m%ROWTYPE;
  evidence bcm_ctrt_evdc_l%ROWTYPE;
  revocation bcm_alwnc_rvok_exec_l%ROWTYPE;
  now_core VARCHAR(16) := to_char(current_timestamp AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS');
BEGIN
  SELECT * INTO STRICT stopped FROM bcm_exec_gate_evt_l WHERE gate_evt_id = NEW.stop_gate_evt_id;
  IF stopped.ntwk_cd <> NEW.ntwk_cd OR stopped.gate_dvcd <> NEW.gate_dvcd OR stopped.gate_stcd <> 'STOPPED' OR
     EXISTS (SELECT 1 FROM bcm_exec_gate_evt_l newer WHERE newer.ntwk_cd = NEW.ntwk_cd AND
       newer.gate_dvcd = NEW.gate_dvcd AND newer.evt_seq > stopped.evt_seq) THEN
    RAISE EXCEPTION 'resume snapshot does not reference the current stopped gate'
      USING ERRCODE = 'check_violation';
  END IF;
  SELECT * INTO STRICT binding FROM bcm_ctrt_bind_m WHERE ctrt_scope_id = NEW.ntwk_cd || ':SWEEP';
  SELECT * INTO STRICT evidence FROM bcm_ctrt_evdc_l WHERE evdc_id = NEW.ctrt_evdc_id;
  SELECT * INTO STRICT revocation FROM bcm_alwnc_rvok_exec_l WHERE rvok_exec_id = NEW.rvok_exec_id;
  IF binding.actv_ctrt_vrsn_id <> NEW.ctrt_vrsn_id OR binding.bind_rvsn <> NEW.ctrt_bind_rvsn OR
     evidence.ctrt_vrsn_id <> NEW.ctrt_vrsn_id OR evidence.evdc_stcd <> 'VALID' OR
     evidence.vld_until_dttm <= now_core OR
     EXISTS (SELECT 1 FROM bcm_ctrt_evdc_l newer WHERE newer.ctrt_vrsn_id = NEW.ctrt_vrsn_id AND
       (newer.obs_dttm, newer.evdc_id) > (evidence.obs_dttm, evidence.evdc_id)) OR
     revocation.ntwk_cd <> NEW.ntwk_cd OR revocation.ctrt_vrsn_id <> NEW.ctrt_vrsn_id OR
     revocation.ctrt_bind_rvsn <> NEW.ctrt_bind_rvsn OR
     NOT bcm_allowance_revocation_completed(NEW.rvok_exec_id) THEN
    RAISE EXCEPTION 'resume snapshot prerequisites are stale or incomplete'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION bcm_guard_execution_gate_resume_request() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  resume bcm_exec_gate_rsm_l%ROWTYPE;
BEGIN
  IF NEW.tgt_dvcd <> 'EXECUTION_GATE' THEN
    RETURN NEW;
  END IF;
  SELECT * INTO STRICT resume FROM bcm_exec_gate_rsm_l WHERE rsm_id = NEW.aft_gate_rsm_id;
  IF NEW.risk_dvcd <> 'RESUME' OR NEW.scope_id <> resume.ntwk_cd || ':' || resume.gate_dvcd OR
     NEW.base_bind_rvsn <> resume.ctrt_bind_rvsn OR NEW.tgt_snps_hash <> resume.tgt_snps_hash OR
     NEW.req_role_dvcd <> 'BCM_OPERATOR' THEN
    RAISE EXCEPTION 'execution gate resume request does not match target snapshot'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION bcm_guard_execution_gate_resume_check() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  resume bcm_exec_gate_rsm_l%ROWTYPE;
  previous_seq INTEGER;
BEGIN
  SELECT * INTO STRICT resume FROM bcm_exec_gate_rsm_l WHERE rsm_id = NEW.rsm_id;
  SELECT chk_seq INTO previous_seq FROM bcm_exec_gate_rsm_chk_l
   WHERE rsm_id = NEW.rsm_id ORDER BY chk_seq DESC LIMIT 1;
  IF NEW.chk_seq <> COALESCE(previous_seq, 0) + 1 THEN
    RAISE EXCEPTION 'execution gate resume check sequence must be contiguous'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.chk_stcd = 'READY' AND
     (NEW.rpc1_oprtr_hash <> resume.exp_oprtr_hash OR NEW.rpc2_oprtr_hash <> resume.exp_oprtr_hash) THEN
    RAISE EXCEPTION 'execution gate resume operator snapshot has drifted'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION bcm_guard_execution_gate_event() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  previous bcm_exec_gate_evt_l%ROWTYPE;
  request bcm_chng_req_l%ROWTYPE;
  resume bcm_exec_gate_rsm_l%ROWTYPE;
  check_row bcm_exec_gate_rsm_chk_l%ROWTYPE;
  binding bcm_ctrt_bind_m%ROWTYPE;
  evidence bcm_ctrt_evdc_l%ROWTYPE;
  approval_count INTEGER;
  security_count INTEGER;
  reject_count INTEGER;
  now_core VARCHAR(16) := to_char(current_timestamp AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS');
BEGIN
  SELECT * INTO previous FROM bcm_exec_gate_evt_l
   WHERE ntwk_cd = NEW.ntwk_cd AND gate_dvcd = NEW.gate_dvcd ORDER BY evt_seq DESC LIMIT 1;
  IF previous.gate_evt_id IS NOT NULL AND NEW.evt_seq <= previous.evt_seq THEN
    RETURN NEW;
  END IF;
  IF NEW.evt_seq <> COALESCE(previous.evt_seq, 0) + 1 OR
     (previous.gate_evt_id IS NULL AND NEW.gate_stcd <> 'STOPPED') OR
     (previous.gate_stcd = NEW.gate_stcd) THEN
    RAISE EXCEPTION 'execution gate event transition is not allowed'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.gate_stcd = 'STOPPED' THEN
    RETURN NEW;
  END IF;

  SELECT * INTO STRICT request FROM bcm_chng_req_l WHERE req_id = NEW.rsm_req_id;
  SELECT * INTO STRICT resume FROM bcm_exec_gate_rsm_l WHERE rsm_id = request.aft_gate_rsm_id;
  SELECT * INTO STRICT check_row FROM bcm_exec_gate_rsm_chk_l
   WHERE rsm_id = resume.rsm_id ORDER BY chk_seq DESC LIMIT 1;
  IF request.tgt_dvcd <> 'EXECUTION_GATE' OR request.risk_dvcd <> 'RESUME' OR
     request.scope_id <> NEW.ntwk_cd || ':' || NEW.gate_dvcd OR
     request.tgt_snps_hash <> resume.tgt_snps_hash OR request.expr_dttm <= now_core OR
     resume.stop_gate_evt_id <> previous.gate_evt_id OR check_row.chk_stcd <> 'READY' OR
     check_row.vld_until_dttm <= now_core THEN
    RAISE EXCEPTION 'execution gate resume snapshot is stale or not ready'
      USING ERRCODE = 'check_violation';
  END IF;
  SELECT * INTO STRICT binding FROM bcm_ctrt_bind_m WHERE ctrt_scope_id = resume.ntwk_cd || ':SWEEP';
  SELECT * INTO STRICT evidence FROM bcm_ctrt_evdc_l WHERE evdc_id = resume.ctrt_evdc_id;
  IF binding.actv_ctrt_vrsn_id <> resume.ctrt_vrsn_id OR binding.bind_rvsn <> resume.ctrt_bind_rvsn OR
     evidence.ctrt_vrsn_id <> resume.ctrt_vrsn_id OR evidence.evdc_stcd <> 'VALID' OR
     evidence.vld_until_dttm <= now_core OR
     EXISTS (SELECT 1 FROM bcm_ctrt_evdc_l newer WHERE newer.ctrt_vrsn_id = resume.ctrt_vrsn_id AND
       (newer.obs_dttm, newer.evdc_id) > (evidence.obs_dttm, evidence.evdc_id)) OR
     NOT bcm_allowance_revocation_completed(resume.rvok_exec_id) THEN
    RAISE EXCEPTION 'execution gate resume prerequisites changed' USING ERRCODE = 'check_violation';
  END IF;
  SELECT count(*) FILTER (WHERE dcsn_dvcd = 'APPROVE'),
         count(*) FILTER (WHERE dcsn_dvcd = 'APPROVE' AND aprv_role_dvcd = 'BCM_SECURITY_APPROVER'),
         count(*) FILTER (WHERE dcsn_dvcd = 'REJECT')
    INTO approval_count, security_count, reject_count
    FROM bcm_chng_dcsn_l WHERE req_id = request.req_id;
  IF reject_count > 0 OR approval_count < 2 OR security_count < 1 THEN
    RAISE EXCEPTION 'execution gate resume quorum is not satisfied'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM bcm_adm_actn_l WHERE req_id = request.req_id AND
    actn_dvcd = 'RESUME' AND actn_stcd = 'INTENT') THEN
    RAISE EXCEPTION 'execution gate resume intent is missing' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_exec_gate_rsm_guard
  BEFORE INSERT ON bcm_exec_gate_rsm_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_execution_gate_resume_snapshot();
CREATE TRIGGER trg_bcm_exec_gate_rsm_append_only
  BEFORE UPDATE OR DELETE ON bcm_exec_gate_rsm_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_exec_gate_rsm_chk_guard
  BEFORE INSERT ON bcm_exec_gate_rsm_chk_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_execution_gate_resume_check();
CREATE TRIGGER trg_bcm_exec_gate_rsm_chk_append_only
  BEFORE UPDATE OR DELETE ON bcm_exec_gate_rsm_chk_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_exec_gate_rsm_request_guard
  BEFORE INSERT ON bcm_chng_req_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_execution_gate_resume_request();
CREATE TRIGGER trg_bcm_exec_gate_event_guard
  BEFORE INSERT ON bcm_exec_gate_evt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_execution_gate_event();
