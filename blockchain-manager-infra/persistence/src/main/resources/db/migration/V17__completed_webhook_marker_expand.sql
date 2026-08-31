-- 롤링 배포 중 구버전 writer와 공존하는 짧은 expand 단계다.
ALTER TABLE bcm_whk_l
  ADD COLUMN vndr_cmpl_yn VARCHAR(1) NULL;

ALTER TABLE bcm_whk_l
  ADD CONSTRAINT ck_bcm_whk_vendor_completed
  CHECK (vndr_cmpl_yn IN ('Y', 'N')) NOT VALID;

ALTER TABLE bcm_whk_l
  ADD CONSTRAINT ck_bcm_whk_vendor_completed_not_null
  CHECK (vndr_cmpl_yn IS NOT NULL) NOT VALID;

CREATE OR REPLACE FUNCTION bcm_whk_vendor_completed_compat()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.vndr_cmpl_yn := COALESCE(NEW.vndr_cmpl_yn, 'N');
  ELSIF OLD.prcs_stcd = 'P' AND NEW.prcs_stcd = 'S' AND NEW.vndr_cmpl_yn IS DISTINCT FROM 'Y' THEN
    NEW.vndr_cmpl_yn :=
      CASE
        WHEN NEW.evnt_typ IN (
          'transaction.created',
          'transaction.status.updated',
          'transaction.approval_status.updated',
          'transaction.network_records.processing_completed'
        ) THEN CASE WHEN NEW.payload::json #>> '{data,status}' = 'COMPLETED' THEN 'Y' ELSE 'N' END
        ELSE 'N'
      END;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_whk_vendor_completed_compat
BEFORE INSERT OR UPDATE OF prcs_stcd, vndr_cmpl_yn ON bcm_whk_l
FOR EACH ROW EXECUTE FUNCTION bcm_whk_vendor_completed_compat();

-- V18이 NULL 기존 행을 작은 transaction으로 backfill한다.
CREATE OR REPLACE PROCEDURE bcm_backfill_completed_webhook_marker()
LANGUAGE plpgsql
AS $$
DECLARE
  updated_count INTEGER;
BEGIN
  LOOP
    WITH batch AS MATERIALIZED (
      SELECT noti_id
      FROM bcm_whk_l
      WHERE vndr_cmpl_yn IS NULL
      ORDER BY noti_id
      LIMIT 1000
      FOR UPDATE SKIP LOCKED
    )
    UPDATE bcm_whk_l webhook
    SET vndr_cmpl_yn =
      CASE
        WHEN webhook.prcs_stcd = 'S' AND webhook.evnt_typ IN (
          'transaction.created',
          'transaction.status.updated',
          'transaction.approval_status.updated',
          'transaction.network_records.processing_completed'
        ) THEN CASE WHEN webhook.payload::json #>> '{data,status}' = 'COMPLETED' THEN 'Y' ELSE 'N' END
        ELSE 'N'
      END
    FROM batch
    WHERE webhook.noti_id = batch.noti_id;

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    COMMIT;
    EXIT WHEN updated_count = 0;
  END LOOP;
END;
$$;
