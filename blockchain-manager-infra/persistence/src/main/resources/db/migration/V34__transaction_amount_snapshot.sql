-- docs/design/03-bcm-db.md: V34 관찰 금액의 환산 근거.
-- V32 의 trsf_amt 는 사람 단위 정규화 값이다. 입금은 벤더가 최소 단위 정수로만 주므로 등록 매핑의
-- 정밀도로 환산하는데, 매핑은 제자리에서 바뀐다(07). 같은 관찰을 재처리하면 다른 금액이 나올 수 있고,
-- 덮으면 조용한 금액 사고, 덮지 않고 격리하면 잘못 없는 정상 재처리가 막힌다.
--
-- 그래서 환산에 쓴 근거를 함께 남긴다. 제출 원장은 V28 로 이미 같은 값을 보관하고 있어 입금만 예외였다.
-- 이름·폭·제약을 bcm_sbmt_l 과 같게 둔다.
ALTER TABLE bcm_tx_l
  ADD COLUMN IF NOT EXISTS base_amt VARCHAR(320) NULL,
  ADD COLUMN IF NOT EXISTS dcml_cnt SMALLINT     NULL;

ALTER TABLE bcm_tx_l DROP CONSTRAINT IF EXISTS ck_bcm_tx_base_amt;
ALTER TABLE bcm_tx_l
  ADD CONSTRAINT ck_bcm_tx_base_amt CHECK (base_amt IS NULL OR base_amt ~ '^(0|[1-9][0-9]*)$');

ALTER TABLE bcm_tx_l DROP CONSTRAINT IF EXISTS ck_bcm_tx_dcml;
ALTER TABLE bcm_tx_l
  ADD CONSTRAINT ck_bcm_tx_dcml CHECK (dcml_cnt IS NULL OR dcml_cnt BETWEEN 0 AND 255);

-- 둘은 한 벌이다 — 하나만 있으면 재환산할 수 없다.
ALTER TABLE bcm_tx_l DROP CONSTRAINT IF EXISTS ck_bcm_tx_amt_snapshot;
ALTER TABLE bcm_tx_l
  ADD CONSTRAINT ck_bcm_tx_amt_snapshot CHECK ((base_amt IS NULL) = (dcml_cnt IS NULL));
