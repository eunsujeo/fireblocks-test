-- Phase 10 T10.7 — 실행 중지와 신규 자금 실행 의도, sweep 활성 snapshot을 원자 직렬화한다.

CREATE FUNCTION bcm_lock_execution_gate_event() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(
    hashtextextended('BCM:EXECUTION_GATE:' || NEW.ntwk_cd || ':' || NEW.gate_dvcd, 0)
  );
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_exec_gate_00_lock
  BEFORE INSERT ON bcm_exec_gate_evt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_lock_execution_gate_event();

CREATE FUNCTION bcm_guard_sweep_execution_policy_cap() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  policy bcm_plcy_vrsn_l%ROWTYPE;
BEGIN
  SELECT * INTO STRICT policy FROM bcm_plcy_vrsn_l WHERE plcy_vrsn_id = NEW.plcy_vrsn_id;
  IF NEW.item_cnt > (policy.plcy_payload->>'batchSize')::integer OR
     NEW.req_tot_amt > (policy.plcy_payload->>'batchAmountCap')::numeric THEN
    RAISE EXCEPTION 'sweep execution exceeds active policy batch cap'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_swp_exec_policy_cap
  BEFORE INSERT ON bcm_swp_exec_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_sweep_execution_policy_cap();

CREATE FUNCTION bcm_guard_sweep_item_policy_cap() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  execution bcm_swp_exec_l%ROWTYPE;
  policy bcm_plcy_vrsn_l%ROWTYPE;
BEGIN
  SELECT * INTO STRICT execution FROM bcm_swp_exec_l WHERE swp_exec_id = NEW.swp_exec_id;
  SELECT * INTO STRICT policy FROM bcm_plcy_vrsn_l WHERE plcy_vrsn_id = execution.plcy_vrsn_id;
  IF NEW.req_amt > (policy.plcy_payload->>'itemAmountCap')::numeric THEN
    RAISE EXCEPTION 'sweep item exceeds active policy item cap'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_swp_item_policy_cap
  BEFORE INSERT ON bcm_swp_item_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_sweep_item_policy_cap();

CREATE FUNCTION bcm_guard_sweep_submission() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  gate_status VARCHAR(16);
  policy_binding bcm_plcy_bind_m%ROWTYPE;
  policy bcm_plcy_vrsn_l%ROWTYPE;
  contract_binding bcm_ctrt_bind_m%ROWTYPE;
  contract bcm_ctrt_vrsn_l%ROWTYPE;
  evidence bcm_ctrt_evdc_l%ROWTYPE;
  stored_item_count INTEGER;
  stored_total NUMERIC;
  now_core VARCHAR(16);
BEGIN
  IF OLD.swp_exec_stcd <> 'READY' OR NEW.swp_exec_stcd <> 'SUBMITTING' THEN
    RETURN NEW;
  END IF;

  SELECT gate_stcd INTO gate_status
    FROM bcm_exec_gate_evt_l
   WHERE ntwk_cd = NEW.ntwk_cd AND gate_dvcd = 'SWEEP'
   ORDER BY evt_seq DESC LIMIT 1;
  IF gate_status = 'STOPPED' THEN
    RAISE EXCEPTION 'sweep execution gate is stopped' USING ERRCODE = 'check_violation';
  END IF;

  SELECT * INTO STRICT policy_binding FROM bcm_plcy_bind_m
   WHERE plcy_scope_id = 'POLICY:' || NEW.ntwk_cd || ':' || NEW.tkn_smbl;
  SELECT * INTO STRICT policy FROM bcm_plcy_vrsn_l
   WHERE plcy_vrsn_id = policy_binding.actv_plcy_vrsn_id;
  SELECT * INTO STRICT contract FROM bcm_ctrt_vrsn_l WHERE ctrt_vrsn_id = NEW.ctrt_vrsn_id;
  SELECT * INTO STRICT contract_binding FROM bcm_ctrt_bind_m WHERE ctrt_scope_id = contract.ctrt_scope_id;
  SELECT * INTO STRICT evidence FROM bcm_ctrt_evdc_l
   WHERE ctrt_vrsn_id = NEW.ctrt_vrsn_id
   ORDER BY obs_dttm DESC, evdc_id DESC LIMIT 1;
  SELECT count(*), COALESCE(sum(req_amt), 0) INTO stored_item_count, stored_total
    FROM bcm_swp_item_l WHERE swp_exec_id = NEW.swp_exec_id;
  now_core := to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS');

  IF policy.plcy_vrsn_id <> NEW.plcy_vrsn_id OR
     policy_binding.bind_snps_hash <> NEW.plcy_snps_hash OR
     policy.ctrt_vrsn_id IS DISTINCT FROM NEW.ctrt_vrsn_id OR
     policy.ceiling_pass_yn <> 'Y' OR policy.plcy_payload->>'enabled' IS DISTINCT FROM 'true' OR
     contract_binding.actv_ctrt_vrsn_id IS DISTINCT FROM NEW.ctrt_vrsn_id OR
     contract.ntwk_cd <> NEW.ntwk_cd OR contract.use_dvcd <> 'SWEEP' OR
     lower(contract.ctrt_addr) <> lower(NEW.swp_ctrt_addr) OR
     evidence.evdc_id <> NEW.ctrt_evdc_id OR evidence.evdc_stcd <> 'VALID' OR
     evidence.launch_gate_yn <> 'Y' OR evidence.vld_until_dttm <= now_core OR
     stored_item_count <> NEW.item_cnt OR stored_total <> NEW.req_tot_amt OR
     stored_item_count > (policy.plcy_payload->>'batchSize')::integer OR
     stored_total > (policy.plcy_payload->>'batchAmountCap')::numeric OR
     EXISTS (SELECT 1 FROM bcm_swp_item_l item WHERE item.swp_exec_id = NEW.swp_exec_id AND
       item.req_amt > (policy.plcy_payload->>'itemAmountCap')::numeric) THEN
    RAISE EXCEPTION 'sweep submission snapshot is stale or exceeds active policy'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
EXCEPTION
  WHEN NO_DATA_FOUND THEN
    RAISE EXCEPTION 'sweep submission active Admin snapshot is missing'
      USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_bcm_swp_exec_submission_guard
  BEFORE UPDATE ON bcm_swp_exec_l
  FOR EACH ROW EXECUTE FUNCTION bcm_guard_sweep_submission();
