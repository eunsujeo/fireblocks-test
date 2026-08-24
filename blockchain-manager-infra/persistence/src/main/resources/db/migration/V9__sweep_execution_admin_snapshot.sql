ALTER TABLE bcm_swp_exec_l
  ADD COLUMN plcy_vrsn_id VARCHAR(36) NOT NULL,
  ADD COLUMN plcy_snps_hash CHAR(64) NOT NULL,
  ADD COLUMN ctrt_vrsn_id VARCHAR(36) NOT NULL,
  ADD COLUMN ctrt_evdc_id VARCHAR(36) NOT NULL;

ALTER TABLE bcm_swp_exec_l
  ADD CONSTRAINT fk_bcm_swp_exec_policy_version
    FOREIGN KEY (plcy_vrsn_id) REFERENCES bcm_plcy_vrsn_l(plcy_vrsn_id),
  ADD CONSTRAINT fk_bcm_swp_exec_contract_version
    FOREIGN KEY (ctrt_vrsn_id) REFERENCES bcm_ctrt_vrsn_l(ctrt_vrsn_id),
  ADD CONSTRAINT fk_bcm_swp_exec_contract_evidence
    FOREIGN KEY (ctrt_evdc_id) REFERENCES bcm_ctrt_evdc_l(evdc_id),
  ADD CONSTRAINT ck_bcm_swp_exec_admin_snapshot
    CHECK (plcy_snps_hash ~ '^[0-9a-f]{64}$');

CREATE FUNCTION bcm_lock_sweep_contract_evidence() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('BCM:SWEEP:EVIDENCE:' || NEW.ctrt_vrsn_id, 0));
  RETURN NEW;
END;
$$;

CREATE FUNCTION bcm_guard_sweep_execution_snapshot() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  policy_binding bcm_plcy_bind_m%ROWTYPE;
  policy bcm_plcy_vrsn_l%ROWTYPE;
  contract_binding bcm_ctrt_bind_m%ROWTYPE;
  contract bcm_ctrt_vrsn_l%ROWTYPE;
  evidence bcm_ctrt_evdc_l%ROWTYPE;
  now_core VARCHAR(16);
BEGIN
  SELECT * INTO STRICT policy_binding
    FROM bcm_plcy_bind_m
   WHERE plcy_scope_id = 'POLICY:' || NEW.ntwk_cd || ':' || NEW.tkn_smbl
   FOR SHARE;
  SELECT * INTO STRICT policy
    FROM bcm_plcy_vrsn_l
   WHERE plcy_vrsn_id = policy_binding.actv_plcy_vrsn_id;
  IF policy.plcy_vrsn_id <> NEW.plcy_vrsn_id OR
     policy_binding.bind_snps_hash <> NEW.plcy_snps_hash OR
     policy.ctrt_vrsn_id IS DISTINCT FROM NEW.ctrt_vrsn_id OR
     policy.ceiling_pass_yn <> 'Y' OR
     policy.plcy_payload->>'enabled' IS DISTINCT FROM 'true' THEN
    RAISE EXCEPTION 'sweep execution policy snapshot is not active'
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT * INTO STRICT contract
    FROM bcm_ctrt_vrsn_l
   WHERE ctrt_vrsn_id = NEW.ctrt_vrsn_id;
  SELECT * INTO STRICT contract_binding
    FROM bcm_ctrt_bind_m
   WHERE ctrt_scope_id = contract.ctrt_scope_id
   FOR SHARE;
  PERFORM pg_advisory_xact_lock(hashtextextended('BCM:SWEEP:EVIDENCE:' || NEW.ctrt_vrsn_id, 0));
  IF contract_binding.actv_ctrt_vrsn_id IS DISTINCT FROM NEW.ctrt_vrsn_id OR
     contract.ntwk_cd <> NEW.ntwk_cd OR contract.use_dvcd <> 'SWEEP' OR
     lower(contract.ctrt_addr) <> lower(NEW.swp_ctrt_addr) THEN
    RAISE EXCEPTION 'sweep execution contract snapshot is not active'
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT * INTO STRICT evidence
    FROM bcm_ctrt_evdc_l
   WHERE ctrt_vrsn_id = NEW.ctrt_vrsn_id
   ORDER BY obs_dttm DESC, evdc_id DESC
   LIMIT 1;
  now_core := to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS');
  IF evidence.evdc_id <> NEW.ctrt_evdc_id OR evidence.evdc_stcd <> 'VALID' OR
     evidence.launch_gate_yn <> 'Y' OR evidence.vld_until_dttm <= now_core THEN
    RAISE EXCEPTION 'sweep execution contract evidence is not current and valid'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
EXCEPTION
  WHEN NO_DATA_FOUND THEN
    RAISE EXCEPTION 'sweep execution active Admin snapshot is missing'
      USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_bcm_ctrt_evdc_sweep_lock
  BEFORE INSERT ON bcm_ctrt_evdc_l
  FOR EACH ROW EXECUTE FUNCTION bcm_lock_sweep_contract_evidence();

CREATE TRIGGER trg_bcm_swp_exec_snapshot_guard
  BEFORE INSERT ON bcm_swp_exec_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_sweep_execution_snapshot();
