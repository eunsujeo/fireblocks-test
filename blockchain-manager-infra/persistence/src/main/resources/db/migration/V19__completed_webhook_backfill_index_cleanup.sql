-- bcm:transaction=off
-- V18 backfill이 끝난 뒤 임시 NULL 후보 index를 온라인으로 제거한다. 준비 SQL을 생략한 빈 DB에서도 안전하다.
DROP INDEX CONCURRENTLY IF EXISTS idx_bcm_whk_completed_backfill;
