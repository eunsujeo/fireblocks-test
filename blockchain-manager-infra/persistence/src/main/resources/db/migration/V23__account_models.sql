-- docs/design/03-bcm-db.md: 계정 ID/기존 vault 매핑을 보존하면서 논리 계정 모델을 추가한다.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM bcm_prvd_bndg_m WHERE prtc_prvd = 'dfns') AND EXISTS (SELECT 1 FROM bcm_acnt_m) THEN
    RAISE EXCEPTION 'Existing vault accounts cannot be converted to a Dfns logical account dataset';
  END IF;
END $$;

ALTER TABLE bcm_acnt_m ADD COLUMN acnt_mdl VARCHAR(16) NOT NULL DEFAULT 'VAULT';
ALTER TABLE bcm_acnt_m ADD CONSTRAINT ck_bcm_acnt_model CHECK (
  (acnt_mdl = 'VAULT' AND vndr_vlt_id IS NOT NULL AND vndr_vlt_id <> '' AND vndr_vlt_id !~ '^\s|\s$')
  OR (acnt_mdl = 'LOGICAL' AND vndr_vlt_id IS NULL)
);
ALTER TABLE bcm_acnt_m ALTER COLUMN vndr_vlt_id DROP NOT NULL;

CREATE FUNCTION guard_bcm_account_model() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.acnt_mdl IS DISTINCT FROM OLD.acnt_mdl THEN
    RAISE EXCEPTION 'Account model is immutable';
  END IF;
  IF NEW.acnt_mdl = 'LOGICAL' AND NOT EXISTS (SELECT 1 FROM bcm_prvd_bndg_m WHERE prtc_prvd = 'dfns') THEN
    RAISE EXCEPTION 'Logical accounts require a Dfns origin binding';
  END IF;
  IF NEW.acnt_mdl = 'VAULT' AND EXISTS (SELECT 1 FROM bcm_prvd_bndg_m WHERE prtc_prvd = 'dfns') THEN
    RAISE EXCEPTION 'Vault accounts cannot be stored in a Dfns dataset';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER guard_bcm_account_model BEFORE INSERT OR UPDATE ON bcm_acnt_m
  FOR EACH ROW EXECUTE FUNCTION guard_bcm_account_model();
