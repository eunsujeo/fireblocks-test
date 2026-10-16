-- docs/design/03-bcm-db.md: V36 거래가 판정에 쓴 계정·자산 모델을 스스로 들고 있는다.
-- V32의 주소 동일성 비교는 체인의 계정 모델로 규칙을 고르는데(EVM 대소문자 무관 / base58 정확 일치),
-- 그 값을 **비교 시점에 bcm_blkc_m 에서 네트워크 코드로 다시 읽고 있었다.**
--
-- 코드가 가리키는 후보가 바뀌면(해제 후 다른 계열 후보를 같은 코드로 채택) 과거 거래가 새 규칙으로 비교된다.
-- 거래는 그대로인데 판정만 뒤에서 바뀌어, 전에 같다고 본 주소가 달라지거나 그 반대가 된다.
-- V34가 금액 환산 근거를 행에 남긴 것과 같은 이유다 — **현재 마스터를 다시 읽지 않고 그때 쓴 근거를 쓴다.**
ALTER TABLE bcm_tx_l
  ADD COLUMN IF NOT EXISTS chain_mdl_dvcd VARCHAR(16) NULL;

ALTER TABLE bcm_tx_l
  ADD CONSTRAINT ck_bcm_tx_chain_mdl CHECK (chain_mdl_dvcd IS NULL OR chain_mdl_dvcd IN ('EVM', 'SOLANA'));
