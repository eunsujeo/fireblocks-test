-- docs/design/03-bcm-db.md: V35 채택 네트워크는 계정·자산 모델을 반드시 가진다.
-- V25는 `chain_mdl_dvcd`를 Dfns 데이터셋 seed 전용으로 두고 Fireblocks 동기화 행은 NULL로 남겼다.
-- 그런데 이 값은 제공자 정보가 아니라 **체인의 속성**이고, 거래 관찰의 주소 동일성 비교(V32)가 이 값으로 규칙을 고른다.
-- NULL이면 정확 문자열 일치만 허용하므로, 같은 EVM 주소의 체크섬 표기와 소문자 표기가 충돌로 격리된다.
--
-- 동기화가 만든 **미채택 후보는 계속 NULL**이다 — 쓸지 정하지 않은 체인의 모델을 요구하지 않는다.
-- 채택(`ntwk_cd` 부여)하는 순간부터 모델이 있어야 한다.
ALTER TABLE bcm_blkc_m
  ADD CONSTRAINT ck_bcm_blkc_adopted_chain_mdl
    CHECK (ntwk_cd IS NULL OR chain_mdl_dvcd IS NOT NULL);
