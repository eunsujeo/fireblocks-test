CREATE TABLE bcm_exec_gate_evt_l (
  gate_evt_id         VARCHAR(36)   PRIMARY KEY,
  ntwk_cd             VARCHAR(20)   NOT NULL REFERENCES bcm_blkc_m(ntwk_cd),
  gate_dvcd           VARCHAR(16)   NOT NULL,
  evt_seq             INTEGER       NOT NULL,
  gate_stcd           VARCHAR(16)   NOT NULL,
  req_rsn             VARCHAR(1000) NOT NULL,
  work_tckt           VARCHAR(128)  NOT NULL,
  idmp_key            VARCHAR(128)  NOT NULL,
  occr_dttm           VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_exec_gate_seq UNIQUE (ntwk_cd, gate_dvcd, evt_seq),
  CONSTRAINT ux_bcm_exec_gate_idmp UNIQUE (frst_reg_empno, idmp_key),
  CONSTRAINT ck_bcm_exec_gate_type CHECK (gate_dvcd IN ('WITHDRAWAL','SWEEP','APPROVE')),
  CONSTRAINT ck_bcm_exec_gate_state CHECK (gate_stcd = 'STOPPED'),
  CONSTRAINT ck_bcm_exec_gate_seq CHECK (evt_seq > 0),
  CONSTRAINT ck_bcm_exec_gate_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);

CREATE INDEX idx_bcm_exec_gate_current
  ON bcm_exec_gate_evt_l(ntwk_cd, gate_dvcd, evt_seq DESC);

CREATE TRIGGER trg_bcm_exec_gate_append_only
  BEFORE UPDATE OR DELETE ON bcm_exec_gate_evt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
