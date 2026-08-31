-- bcm:transaction=off
-- 기존 데이터가 있는 DB에서 V17 직후·V18 직전에 실행한다.
-- 실패 후 재실행하면 invalid index도 먼저 제거하고 온라인으로 다시 만든다.
DROP INDEX CONCURRENTLY IF EXISTS idx_bcm_whk_completed_backfill;
CREATE INDEX CONCURRENTLY idx_bcm_whk_completed_backfill
  ON bcm_whk_l (noti_id)
  WHERE vndr_cmpl_yn IS NULL;
