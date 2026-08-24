-- Phase 10 T10.4 — 불변 Admin 원장과 scope별 현재 binding projection

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
    runtime_code_hash ~ '^[0-9a-f]{64}$' AND immut_hash ~ '^[0-9a-f]{64}$' AND
    ceiling_hash ~ '^[0-9a-f]{64}$'
  )
);
CREATE INDEX idx_bcm_ctrt_vrsn_scope ON bcm_ctrt_vrsn_l (ctrt_scope_id, reg_dttm);

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
  CONSTRAINT ck_bcm_ctrt_evdc_hashes CHECK (
    snps_hash ~ '^[0-9a-f]{64}$' AND exp_code_hash ~ '^[0-9a-f]{64}$' AND
    exp_immut_hash ~ '^[0-9a-f]{64}$' AND doc_evdc_hash ~ '^[0-9a-f]{64}$'
  ),
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
CREATE INDEX idx_bcm_ctrt_evdc_current ON bcm_ctrt_evdc_l (ctrt_vrsn_id, evdc_stcd, vld_until_dttm DESC);

CREATE TABLE bcm_plcy_vrsn_l (
  plcy_vrsn_id      VARCHAR(36)  PRIMARY KEY,
  plcy_scope_id     VARCHAR(128) NOT NULL,
  vrsn_no           INTEGER      NOT NULL,
  plcy_schm_vrsn    VARCHAR(16)  NOT NULL,
  base_plcy_vrsn_id VARCHAR(36)  NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  ctrt_vrsn_id      VARCHAR(36)  NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  plcy_payload      JSONB        NOT NULL,
  plcy_hash         VARCHAR(64)  NOT NULL,
  ceiling_snps      JSONB        NOT NULL,
  ceiling_hash      VARCHAR(64)  NOT NULL,
  ceiling_pass_yn   VARCHAR(1)   NOT NULL,
  reg_dttm          VARCHAR(16)  NOT NULL,
  frst_reg_empno    VARCHAR(6)   NOT NULL,
  frst_reg_brcd     VARCHAR(4)   NOT NULL,
  last_chng_empno   VARCHAR(6)   NOT NULL,
  last_chng_brcd    VARCHAR(4)   NOT NULL,
  CONSTRAINT ux_bcm_plcy_vrsn_scope UNIQUE (plcy_scope_id, vrsn_no),
  CONSTRAINT ux_bcm_plcy_vrsn_hash UNIQUE (plcy_scope_id, plcy_hash),
  CONSTRAINT ck_bcm_plcy_ceiling CHECK (ceiling_pass_yn IN ('Y','N')),
  CONSTRAINT ck_bcm_plcy_hashes CHECK (
    plcy_hash ~ '^[0-9a-f]{64}$' AND ceiling_hash ~ '^[0-9a-f]{64}$'
  )
);

CREATE TABLE bcm_chng_req_l (
  req_id             VARCHAR(36)   PRIMARY KEY,
  tgt_dvcd           VARCHAR(16)   NOT NULL,
  scope_id           VARCHAR(128)  NOT NULL,
  bfr_ctrt_vrsn_id   VARCHAR(36)   NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  aft_ctrt_vrsn_id   VARCHAR(36)   NULL REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  bfr_plcy_vrsn_id   VARCHAR(36)   NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  aft_plcy_vrsn_id   VARCHAR(36)   NULL REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
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
    (tgt_dvcd = 'CONTRACT' AND aft_ctrt_vrsn_id IS NOT NULL AND aft_plcy_vrsn_id IS NULL) OR
    (tgt_dvcd = 'POLICY' AND aft_plcy_vrsn_id IS NOT NULL AND aft_ctrt_vrsn_id IS NULL)
  ),
  CONSTRAINT ck_bcm_chng_req_risk CHECK (risk_dvcd IN ('GENERAL','SECURITY','RESUME','FUND')),
  CONSTRAINT ck_bcm_chng_req_hashes CHECK (
    tgt_snps_hash ~ '^[0-9a-f]{64}$' AND diff_hash ~ '^[0-9a-f]{64}$' AND
    impact_hash ~ '^[0-9a-f]{64}$'
  )
);
CREATE INDEX idx_bcm_chng_req_scope ON bcm_chng_req_l (scope_id, req_dttm DESC);
CREATE INDEX idx_bcm_chng_req_expiry ON bcm_chng_req_l (expr_dttm);

CREATE TABLE bcm_chng_dcsn_l (
  req_id             VARCHAR(36)   NOT NULL REFERENCES bcm_chng_req_l(req_id),
  aprv_empno         VARCHAR(6)    NOT NULL,
  aprv_brcd          VARCHAR(4)    NOT NULL,
  aprv_role_dvcd     VARCHAR(32)   NOT NULL,
  dcsn_dvcd          VARCHAR(16)   NOT NULL,
  dcsn_snps_hash     VARCHAR(64)   NOT NULL,
  dcsn_opin          VARCHAR(1000) NULL,
  dcsn_dttm          VARCHAR(16)   NOT NULL,
  frst_reg_empno     VARCHAR(6)    NOT NULL,
  frst_reg_brcd      VARCHAR(4)    NOT NULL,
  last_chng_empno    VARCHAR(6)    NOT NULL,
  last_chng_brcd     VARCHAR(4)    NOT NULL,
  PRIMARY KEY (req_id, aprv_empno),
  CONSTRAINT ck_bcm_chng_dcsn CHECK (dcsn_dvcd IN ('APPROVE','REJECT')),
  CONSTRAINT ck_bcm_chng_aprv_role CHECK (
    aprv_role_dvcd IN ('BCM_APPROVER','BCM_SECURITY_APPROVER')
  ),
  CONSTRAINT ck_bcm_chng_dcsn_actor CHECK (
    aprv_empno = frst_reg_empno AND aprv_brcd = frst_reg_brcd
  )
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
  CONSTRAINT ck_bcm_adm_actn_type CHECK (
    actn_dvcd IN ('ACTIVATE','CANCEL','PAUSE','RESUME')
  ),
  CONSTRAINT ck_bcm_adm_actn_state CHECK (actn_stcd IN ('INTENT','SUCCEEDED','FAILED')),
  CONSTRAINT ck_bcm_adm_actn_hashes CHECK (
    req_hash ~ '^[0-9a-f]{64}$' AND exp_state_hash ~ '^[0-9a-f]{64}$' AND
    (rsp_hash IS NULL OR rsp_hash ~ '^[0-9a-f]{64}$') AND
    (obs_state_hash IS NULL OR obs_state_hash ~ '^[0-9a-f]{64}$')
  )
);
CREATE UNIQUE INDEX ux_bcm_adm_actn_idmp
  ON bcm_adm_actn_l(req_id, actn_dvcd, idmp_key)
  WHERE actn_stcd = 'INTENT';
CREATE INDEX idx_bcm_adm_actn_request ON bcm_adm_actn_l (req_id, occr_dttm);

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
  CONSTRAINT ck_bcm_ctrt_bind_scope CHECK (ctrt_scope_id = ntwk_cd || ':' || use_dvcd),
  CONSTRAINT ck_bcm_ctrt_bind_hash CHECK (bind_snps_hash ~ '^[0-9a-f]{64}$')
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
  last_chng_brcd      VARCHAR(4)   NOT NULL,
  CONSTRAINT ck_bcm_plcy_bind_hash CHECK (bind_snps_hash ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION bcm_admin_reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Admin ledger % is append-only', TG_TABLE_NAME
    USING ERRCODE = 'check_violation';
END;
$$;

CREATE FUNCTION bcm_admin_reject_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Admin binding % cannot be deleted', TG_TABLE_NAME
    USING ERRCODE = 'check_violation';
END;
$$;

CREATE FUNCTION bcm_guard_change_decision() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  req bcm_chng_req_l%ROWTYPE;
  now_core VARCHAR(16) := to_char(current_timestamp AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS');
BEGIN
  SELECT * INTO STRICT req FROM bcm_chng_req_l WHERE req_id = NEW.req_id;
  IF NEW.aprv_empno = req.frst_reg_empno THEN
    RAISE EXCEPTION 'requester cannot decide own request' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.dcsn_snps_hash <> req.tgt_snps_hash THEN
    RAISE EXCEPTION 'decision snapshot is stale' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.dcsn_dvcd = 'APPROVE' AND req.expr_dttm <= now_core THEN
    RAISE EXCEPTION 'expired request cannot be approved' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION bcm_guard_contract_binding() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  req bcm_chng_req_l%ROWTYPE;
  evdc bcm_ctrt_evdc_l%ROWTYPE;
  approval_count INTEGER;
  security_count INTEGER;
  reject_count INTEGER;
  now_core VARCHAR(16) := to_char(current_timestamp AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS');
BEGIN
  IF NEW.actv_ctrt_vrsn_id IS NOT DISTINCT FROM OLD.actv_ctrt_vrsn_id THEN
    RAISE EXCEPTION 'contract activation must change the active version' USING ERRCODE = 'check_violation';
  END IF;
  SELECT * INTO STRICT req FROM bcm_chng_req_l WHERE req_id = NEW.last_req_id;
  IF req.tgt_dvcd <> 'CONTRACT' OR req.scope_id <> OLD.ctrt_scope_id OR
     req.aft_ctrt_vrsn_id <> NEW.actv_ctrt_vrsn_id OR req.base_bind_rvsn <> OLD.bind_rvsn OR
     NEW.bind_rvsn <> OLD.bind_rvsn + 1 OR NEW.bind_snps_hash <> req.tgt_snps_hash OR
     NEW.last_evdc_id IS DISTINCT FROM req.evdc_id OR req.risk_dvcd NOT IN ('SECURITY','RESUME') THEN
    RAISE EXCEPTION 'contract binding request does not match current snapshot' USING ERRCODE = 'check_violation';
  END IF;
  IF req.expr_dttm <= now_core THEN
    RAISE EXCEPTION 'expired request cannot activate contract' USING ERRCODE = 'check_violation';
  END IF;
  SELECT * INTO STRICT evdc FROM bcm_ctrt_evdc_l WHERE evdc_id = req.evdc_id;
  IF evdc.ctrt_vrsn_id <> NEW.actv_ctrt_vrsn_id OR evdc.evdc_stcd <> 'VALID' OR
     evdc.vld_until_dttm <= now_core THEN
    RAISE EXCEPTION 'contract evidence is invalid or stale' USING ERRCODE = 'check_violation';
  END IF;
  SELECT count(*) FILTER (WHERE dcsn_dvcd = 'APPROVE'),
         count(*) FILTER (WHERE dcsn_dvcd = 'APPROVE' AND aprv_role_dvcd = 'BCM_SECURITY_APPROVER'),
         count(*) FILTER (WHERE dcsn_dvcd = 'REJECT')
    INTO approval_count, security_count, reject_count
    FROM bcm_chng_dcsn_l WHERE req_id = req.req_id;
  IF reject_count > 0 OR approval_count < 2 OR security_count < 1 THEN
    RAISE EXCEPTION 'contract activation quorum is not satisfied' USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM bcm_adm_actn_l
     WHERE req_id = req.req_id AND actn_dvcd = 'ACTIVATE' AND actn_stcd = 'INTENT'
  ) THEN
    RAISE EXCEPTION 'contract activation intent is missing' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION bcm_guard_policy_binding() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  req bcm_chng_req_l%ROWTYPE;
  policy bcm_plcy_vrsn_l%ROWTYPE;
  approval_count INTEGER;
  security_count INTEGER;
  reject_count INTEGER;
  required_count INTEGER;
  now_core VARCHAR(16) := to_char(current_timestamp AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS');
BEGIN
  IF NEW.actv_plcy_vrsn_id IS NOT DISTINCT FROM OLD.actv_plcy_vrsn_id THEN
    RAISE EXCEPTION 'policy activation must change the active version' USING ERRCODE = 'check_violation';
  END IF;
  SELECT * INTO STRICT req FROM bcm_chng_req_l WHERE req_id = NEW.last_req_id;
  IF req.tgt_dvcd <> 'POLICY' OR req.scope_id <> OLD.plcy_scope_id OR
     req.aft_plcy_vrsn_id <> NEW.actv_plcy_vrsn_id OR req.base_bind_rvsn <> OLD.bind_rvsn OR
     NEW.bind_rvsn <> OLD.bind_rvsn + 1 OR NEW.bind_snps_hash <> req.tgt_snps_hash THEN
    RAISE EXCEPTION 'policy binding request does not match current snapshot' USING ERRCODE = 'check_violation';
  END IF;
  IF req.expr_dttm <= now_core THEN
    RAISE EXCEPTION 'expired request cannot activate policy' USING ERRCODE = 'check_violation';
  END IF;
  SELECT * INTO STRICT policy FROM bcm_plcy_vrsn_l WHERE plcy_vrsn_id = NEW.actv_plcy_vrsn_id;
  IF policy.plcy_scope_id <> NEW.plcy_scope_id OR policy.ceiling_pass_yn <> 'Y' THEN
    RAISE EXCEPTION 'policy violates its hard ceiling' USING ERRCODE = 'check_violation';
  END IF;
  IF policy.ctrt_vrsn_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM bcm_ctrt_vrsn_l version
      JOIN bcm_ctrt_bind_m binding ON binding.ctrt_scope_id = version.ctrt_scope_id
     WHERE version.ctrt_vrsn_id = policy.ctrt_vrsn_id
       AND binding.actv_ctrt_vrsn_id = version.ctrt_vrsn_id
  ) THEN
    RAISE EXCEPTION 'policy contract version is not active' USING ERRCODE = 'check_violation';
  END IF;
  SELECT count(*) FILTER (WHERE dcsn_dvcd = 'APPROVE'),
         count(*) FILTER (WHERE dcsn_dvcd = 'APPROVE' AND aprv_role_dvcd = 'BCM_SECURITY_APPROVER'),
         count(*) FILTER (WHERE dcsn_dvcd = 'REJECT')
    INTO approval_count, security_count, reject_count
    FROM bcm_chng_dcsn_l WHERE req_id = req.req_id;
  required_count := CASE WHEN req.risk_dvcd IN ('SECURITY','RESUME') THEN 2 ELSE 1 END;
  IF reject_count > 0 OR approval_count < required_count OR
     (req.risk_dvcd IN ('SECURITY','RESUME') AND security_count < 1) THEN
    RAISE EXCEPTION 'policy activation quorum is not satisfied' USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM bcm_adm_actn_l
     WHERE req_id = req.req_id AND actn_dvcd = 'ACTIVATE' AND actn_stcd = 'INTENT'
  ) THEN
    RAISE EXCEPTION 'policy activation intent is missing' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_chng_dcsn_guard
  BEFORE INSERT ON bcm_chng_dcsn_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_change_decision();
CREATE TRIGGER trg_bcm_ctrt_bind_guard
  BEFORE UPDATE ON bcm_ctrt_bind_m
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_contract_binding();
CREATE TRIGGER trg_bcm_plcy_bind_guard
  BEFORE UPDATE ON bcm_plcy_bind_m
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_policy_binding();
CREATE TRIGGER trg_bcm_ctrt_bind_no_delete
  BEFORE DELETE ON bcm_ctrt_bind_m
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_delete();
CREATE TRIGGER trg_bcm_plcy_bind_no_delete
  BEFORE DELETE ON bcm_plcy_bind_m
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_delete();

CREATE TRIGGER trg_bcm_ctrt_vrsn_append_only
  BEFORE UPDATE OR DELETE ON bcm_ctrt_vrsn_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_ctrt_evdc_append_only
  BEFORE UPDATE OR DELETE ON bcm_ctrt_evdc_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_plcy_vrsn_append_only
  BEFORE UPDATE OR DELETE ON bcm_plcy_vrsn_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_chng_req_append_only
  BEFORE UPDATE OR DELETE ON bcm_chng_req_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_chng_dcsn_append_only
  BEFORE UPDATE OR DELETE ON bcm_chng_dcsn_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_adm_actn_append_only
  BEFORE UPDATE OR DELETE ON bcm_adm_actn_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
