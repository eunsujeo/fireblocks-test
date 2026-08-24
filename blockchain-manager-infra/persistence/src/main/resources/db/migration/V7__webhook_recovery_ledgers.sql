CREATE TABLE bcm_whk_rcvr_req_l (
  rcvr_req_id         VARCHAR(36)   PRIMARY KEY,
  whk_id              VARCHAR(64)   NOT NULL,
  scope_dvcd          VARCHAR(24)   NOT NULL,
  req_evt_payload     JSONB         NOT NULL,
  req_evt_hash        VARCHAR(64)   NOT NULL,
  idmp_key            VARCHAR(128)  NOT NULL,
  req_rsn             VARCHAR(1000) NOT NULL,
  work_tckt           VARCHAR(128)  NOT NULL,
  req_dttm            VARCHAR(16)   NOT NULL,
  aprv_empno          VARCHAR(6)    NOT NULL,
  aprv_brcd           VARCHAR(4)    NOT NULL,
  aprv_dttm           VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  CONSTRAINT ux_bcm_whk_rcvr_req_idmp UNIQUE (frst_reg_empno, idmp_key),
  CONSTRAINT ck_bcm_whk_rcvr_req_scope CHECK (scope_dvcd = 'FAILED_LAST_24H'),
  CONSTRAINT ck_bcm_whk_rcvr_req_events CHECK (jsonb_typeof(req_evt_payload) = 'array'),
  CONSTRAINT ck_bcm_whk_rcvr_req_hash CHECK (req_evt_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_bcm_whk_rcvr_req_independent_approval CHECK (aprv_empno <> frst_reg_empno),
  CONSTRAINT ck_bcm_whk_rcvr_req_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);

CREATE TABLE bcm_whk_rcvr_evt_l (
  rcvr_req_id         VARCHAR(36)   NOT NULL REFERENCES bcm_whk_rcvr_req_l(rcvr_req_id),
  evt_seq             INTEGER       NOT NULL,
  rcvr_stcd           VARCHAR(24)   NOT NULL,
  call_dvcd           VARCHAR(24)   NOT NULL,
  call_dttm           VARCHAR(16)   NOT NULL,
  rslt_dttm           VARCHAR(16)   NULL,
  whk_stcd            VARCHAR(16)   NULL,
  obs_evt_payload     JSONB         NULL,
  obs_evt_hash        VARCHAR(64)   NULL,
  scope_fr_dttm       VARCHAR(16)   NULL,
  scope_to_dttm       VARCHAR(16)   NULL,
  schd_noti_cnt       INTEGER       NULL,
  rsp_payload         JSONB         NULL,
  rsp_hash            VARCHAR(64)   NULL,
  err_cd              VARCHAR(64)   NULL,
  occr_dttm           VARCHAR(16)   NOT NULL,
  frst_reg_empno      VARCHAR(6)    NOT NULL,
  frst_reg_brcd       VARCHAR(4)    NOT NULL,
  last_chng_empno     VARCHAR(6)    NOT NULL,
  last_chng_brcd      VARCHAR(4)    NOT NULL,
  PRIMARY KEY (rcvr_req_id, evt_seq),
  CONSTRAINT ck_bcm_whk_rcvr_evt_seq CHECK (evt_seq > 0),
  CONSTRAINT ck_bcm_whk_rcvr_evt_state CHECK (
    rcvr_stcd IN ('STATUS_INTENT','STATUS_OBSERVED','ACTIVATE_INTENT','ACTIVATED',
      'RESEND_INTENT','RESEND_ACCEPTED','FAILED')
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_call CHECK (
    call_dvcd IN ('STATUS_QUERY','ACTIVATE','RESEND_FAILED')
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_whk_state CHECK (
    whk_stcd IS NULL OR whk_stcd IN ('DISABLED','ENABLED','SUSPENDED')
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_hashes CHECK (
    (obs_evt_hash IS NULL OR obs_evt_hash ~ '^[0-9a-f]{64}$') AND
    (rsp_hash IS NULL OR rsp_hash ~ '^[0-9a-f]{64}$')
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_scope CHECK (
    (scope_fr_dttm IS NULL AND scope_to_dttm IS NULL) OR
    (scope_fr_dttm IS NOT NULL AND scope_to_dttm IS NOT NULL AND scope_fr_dttm <= scope_to_dttm)
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_count CHECK (schd_noti_cnt IS NULL OR schd_noti_cnt >= 0),
  CONSTRAINT ck_bcm_whk_rcvr_evt_fields CHECK (
    (rcvr_stcd IN ('STATUS_INTENT','ACTIVATE_INTENT') AND rslt_dttm IS NULL AND whk_stcd IS NULL AND
      obs_evt_payload IS NULL AND obs_evt_hash IS NULL AND scope_fr_dttm IS NULL AND scope_to_dttm IS NULL AND
      schd_noti_cnt IS NULL AND rsp_payload IS NULL AND rsp_hash IS NULL AND err_cd IS NULL) OR
    (rcvr_stcd = 'RESEND_INTENT' AND rslt_dttm IS NULL AND whk_stcd IS NULL AND
      obs_evt_payload IS NULL AND obs_evt_hash IS NULL AND scope_fr_dttm IS NOT NULL AND scope_to_dttm IS NOT NULL AND
      schd_noti_cnt IS NULL AND rsp_payload IS NULL AND rsp_hash IS NULL AND err_cd IS NULL) OR
    (rcvr_stcd IN ('STATUS_OBSERVED','ACTIVATED') AND rslt_dttm IS NOT NULL AND whk_stcd IS NOT NULL AND
      obs_evt_payload IS NOT NULL AND obs_evt_hash IS NOT NULL AND scope_fr_dttm IS NULL AND scope_to_dttm IS NULL AND
      schd_noti_cnt IS NULL AND rsp_payload IS NOT NULL AND rsp_hash IS NOT NULL AND err_cd IS NULL) OR
    (rcvr_stcd = 'RESEND_ACCEPTED' AND rslt_dttm IS NOT NULL AND whk_stcd IS NULL AND
      obs_evt_payload IS NULL AND obs_evt_hash IS NULL AND scope_fr_dttm IS NOT NULL AND scope_to_dttm IS NOT NULL AND
      schd_noti_cnt IS NOT NULL AND rsp_payload IS NOT NULL AND rsp_hash IS NOT NULL AND err_cd IS NULL) OR
    (rcvr_stcd = 'FAILED' AND rslt_dttm IS NOT NULL AND schd_noti_cnt IS NULL AND
      rsp_payload IS NULL AND rsp_hash IS NULL AND err_cd IS NOT NULL)
  ),
  CONSTRAINT ck_bcm_whk_rcvr_evt_actor CHECK (
    frst_reg_empno = last_chng_empno AND frst_reg_brcd = last_chng_brcd
  )
);

CREATE INDEX idx_bcm_whk_rcvr_evt_current
  ON bcm_whk_rcvr_evt_l(rcvr_req_id, evt_seq DESC);

CREATE FUNCTION bcm_whk_rcvr_event_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  previous_event bcm_whk_rcvr_evt_l%ROWTYPE;
  request_events JSONB;
BEGIN
  SELECT req_evt_payload INTO request_events
    FROM bcm_whk_rcvr_req_l
   WHERE rcvr_req_id = NEW.rcvr_req_id
   FOR UPDATE;

  SELECT * INTO previous_event
    FROM bcm_whk_rcvr_evt_l
   WHERE rcvr_req_id = NEW.rcvr_req_id
   ORDER BY evt_seq DESC
   LIMIT 1;

  IF previous_event.evt_seq IS NULL THEN
    IF NEW.evt_seq <> 1 OR NEW.rcvr_stcd <> 'STATUS_INTENT' OR NEW.call_dvcd <> 'STATUS_QUERY' THEN
      RAISE EXCEPTION 'webhook recovery must start with STATUS_INTENT' USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    IF NEW.evt_seq <> previous_event.evt_seq + 1 THEN
      RAISE EXCEPTION 'webhook recovery event sequence must be continuous' USING ERRCODE = 'check_violation';
    END IF;
    IF previous_event.rcvr_stcd IN ('FAILED', 'RESEND_ACCEPTED') THEN
      RAISE EXCEPTION 'webhook recovery terminal event cannot transition' USING ERRCODE = 'check_violation';
    END IF;
    IF previous_event.rcvr_stcd = 'STATUS_INTENT' AND NOT (
      NEW.rcvr_stcd IN ('STATUS_OBSERVED', 'FAILED') AND NEW.call_dvcd = 'STATUS_QUERY'
    ) THEN
      RAISE EXCEPTION 'invalid webhook status query result' USING ERRCODE = 'check_violation';
    END IF;
    IF previous_event.rcvr_stcd = 'STATUS_OBSERVED' THEN
      IF NOT request_events <@ previous_event.obs_evt_payload THEN
        IF NOT (NEW.rcvr_stcd = 'FAILED' AND NEW.call_dvcd = 'STATUS_QUERY') THEN
          RAISE EXCEPTION 'missing webhook events must fail recovery' USING ERRCODE = 'check_violation';
        END IF;
      ELSIF previous_event.whk_stcd = 'ENABLED' THEN
        IF NOT (NEW.rcvr_stcd = 'RESEND_INTENT' AND NEW.call_dvcd = 'RESEND_FAILED') THEN
          RAISE EXCEPTION 'enabled webhook must proceed to resend' USING ERRCODE = 'check_violation';
        END IF;
      ELSIF NOT (NEW.rcvr_stcd = 'ACTIVATE_INTENT' AND NEW.call_dvcd = 'ACTIVATE') THEN
        RAISE EXCEPTION 'inactive webhook must be activated' USING ERRCODE = 'check_violation';
      END IF;
    END IF;
    IF previous_event.rcvr_stcd = 'ACTIVATE_INTENT' AND NOT (
      NEW.rcvr_stcd IN ('ACTIVATED', 'FAILED') AND NEW.call_dvcd = 'ACTIVATE'
    ) THEN
      RAISE EXCEPTION 'invalid webhook activation result' USING ERRCODE = 'check_violation';
    END IF;
    IF previous_event.rcvr_stcd = 'ACTIVATED' AND NOT (
      previous_event.whk_stcd = 'ENABLED' AND request_events <@ previous_event.obs_evt_payload AND
      NEW.rcvr_stcd = 'RESEND_INTENT' AND NEW.call_dvcd = 'RESEND_FAILED'
    ) THEN
      RAISE EXCEPTION 'verified activation must proceed to resend' USING ERRCODE = 'check_violation';
    END IF;
    IF previous_event.rcvr_stcd = 'RESEND_INTENT' AND NOT (
      NEW.rcvr_stcd IN ('RESEND_ACCEPTED', 'FAILED') AND NEW.call_dvcd = 'RESEND_FAILED'
    ) THEN
      RAISE EXCEPTION 'invalid webhook resend result' USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_whk_rcvr_event_guard
  BEFORE INSERT ON bcm_whk_rcvr_evt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_whk_rcvr_event_guard();

CREATE TRIGGER trg_bcm_whk_rcvr_req_append_only
  BEFORE UPDATE OR DELETE ON bcm_whk_rcvr_req_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
CREATE TRIGGER trg_bcm_whk_rcvr_evt_append_only
  BEFORE UPDATE OR DELETE ON bcm_whk_rcvr_evt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();
