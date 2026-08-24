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

CREATE TRIGGER trg_bcm_ext_ctrl_evdc_append_only
  BEFORE UPDATE OR DELETE ON bcm_ext_ctrl_evdc_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
