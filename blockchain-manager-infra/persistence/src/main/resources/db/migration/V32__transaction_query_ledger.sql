-- docs/design/03-bcm-db.md: V32 거래 조회 원장.
-- 공개 거래 조회는 BCM 원장만 읽는다(02 "거래 조회"). 그런데 bcm_tx_l 에는 공개 응답이 요구하는
-- 금액·발신 주소·수신 주소가 없고, 공개 노출 여부를 가를 거래 구분도 없다.
-- 네 컬럼 모두 nullable 추가 전용이라 기존 행을 건드리지 않는다 — 백필은 Q1 이 따로 한다.
ALTER TABLE bcm_tx_l
  ADD COLUMN IF NOT EXISTS trsf_amt  NUMERIC      NULL,
  ADD COLUMN IF NOT EXISTS src_addr  VARCHAR(256) NULL,
  ADD COLUMN IF NOT EXISTS dst_addr  VARCHAR(256) NULL,
  ADD COLUMN IF NOT EXISTS tx_dvcd   VARCHAR(16)  NULL;

-- 금액에 자릿수를 고정하지 않는다. 제출 API 는 18+18 범위지만 Dfns 자산 정밀도는 0..255 이고
-- base_amt 폭은 320 이다. NUMERIC(36,18) 로 제한하면 정상적으로 수용한 입금이 기록 단계에서 실패한다.

-- 제출 마감이 거래 행을 먼저 만들면 그 시점엔 벤더 시각을 모른다 — 제출 응답은 txId 만 준다.
-- BCM 수용 시각으로 대신 채우면 대사가 벤더 시각끼리 비교한다는 규칙이 깨지므로 NULL 을 허용하고
-- 첫 벤더 관찰이 NULL → 값으로 한 번만 채운다(set-once 예외).
ALTER TABLE bcm_tx_l
  ALTER COLUMN vndr_crt_dttm DROP NOT NULL;
