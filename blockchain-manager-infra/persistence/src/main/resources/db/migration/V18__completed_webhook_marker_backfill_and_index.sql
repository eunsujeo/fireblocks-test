-- bcm:transaction=off
-- V17 호환 trigger가 동시 P→S를 보호하는 동안 기존 행을 1,000건씩 커밋한다.
CALL bcm_backfill_completed_webhook_marker();

ALTER TABLE bcm_whk_l ALTER COLUMN vndr_cmpl_yn SET DEFAULT 'N';
ALTER TABLE bcm_whk_l VALIDATE CONSTRAINT ck_bcm_whk_vendor_completed;
ALTER TABLE bcm_whk_l VALIDATE CONSTRAINT ck_bcm_whk_vendor_completed_not_null;
ALTER TABLE bcm_whk_l ALTER COLUMN vndr_cmpl_yn SET NOT NULL;

-- 실패 후 재실행하면 invalid index도 먼저 제거하고 온라인으로 다시 만든다.
DROP INDEX CONCURRENTLY IF EXISTS idx_bcm_whk_completed_archive;
CREATE INDEX CONCURRENTLY idx_bcm_whk_completed_archive
  ON bcm_whk_l (vndr_tx_id, rcv_dttm DESC, noti_id DESC)
  WHERE prcs_stcd = 'S' AND vndr_cmpl_yn = 'Y' AND vndr_tx_id IS NOT NULL;
