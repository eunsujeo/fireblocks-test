-- bcm:transaction=off
-- docs/design/03-bcm-db.md: V32 거래 조회 원장 — 계정별 목록 인덱스.
-- 02 가 목록 정렬을 (frst_dtct_dttm, vndr_tx_id) keyset 으로, 귀속을 계정으로 정했다.
--
-- 온라인 생성이다. 지금은 아직 운영 배포 전이라 원장이 비어 있지만(docs/runbooks/production-deployment-plan.md),
-- 일반 CREATE INDEX 는 만드는 동안 쓰기를 막아 원장이 커진 뒤에는 Fireblocks 경로까지 멈춘다.
-- 이 마이그레이션이 언제 적용될지는 배포 순서에 달려 있어 큰 원장을 전제로 둔다.
-- 컬럼 추가와 재시도 성질이 달라 V32 와 파일을 나눴다.
-- IF NOT EXISTS 는 실패로 남은 invalid index 를 "있음"으로 보고 건너뛰어, 마이그레이션은 성공했는데
-- 쓰이지 않는 인덱스가 남는다. 그래서 먼저 지운다(V26·V31 과 같은 패턴).
DROP INDEX CONCURRENTLY IF EXISTS idx_bcm_tx_account_listing;
CREATE INDEX CONCURRENTLY idx_bcm_tx_account_listing
  ON bcm_tx_l (acnt_id, frst_dtct_dttm, vndr_tx_id);
