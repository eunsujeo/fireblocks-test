-- docs/design/03-bcm-db.md: V27 등록 자산의 정밀도 보관.
-- 07은 "소수 자릿수는 현재 매핑에 보관하지 않는다"였다. Fireblocks가 사람 단위 금액을 보내 환산이 필요 없었기 때문이다.
-- Dfns 관찰은 최소 단위 정수라 이벤트 금액을 만들려면 정밀도가 필요하고, 제공자마다 같은 `amount` 필드의 단위가 달라지면 안 된다.
-- 값의 출처는 등록 시점이다 — Fireblocks는 카탈로그 해소값, Dfns는 운영자가 발행사 자료와 대조해 넣은 값(07의 "온체인 대조는 운영자 몫").
-- NULL 허용 추가 전용이라 기존 행은 그대로 둔다. NULL인 자산은 최소 단위 관찰을 이벤트 금액으로 환산하지 않는다.
-- 상한 255는 ERC-20 `decimals`(uint8)·SPL mint decimals(u8)의 모델 한계다.
ALTER TABLE bcm_vndr_ast_m
  ADD COLUMN dcml_cnt SMALLINT NULL
    CONSTRAINT ck_bcm_vndr_ast_dcml CHECK (dcml_cnt BETWEEN 0 AND 255);
