-- docs/design/03-bcm-db.md: V25 Dfns 데이터셋의 계정·자산 모델과 자산 키 길이.
-- bcm_blkc_m.chain_mdl_dvcd — 채택 네트워크의 계정·자산 모델(EVM/SOLANA). Dfns 데이터셋의 DBA seed가 채우고
-- 벤더 카탈로그 동기화(Fireblocks)는 채우지 않는다(NULL). Dfns 등록 관문은 NULL을 미확정 모델로 거절한다.
ALTER TABLE bcm_blkc_m
  ADD COLUMN chain_mdl_dvcd VARCHAR(16) NULL
    CONSTRAINT ck_bcm_blkc_chain_mdl CHECK (chain_mdl_dvcd IN ('EVM', 'SOLANA'));

-- Dfns 자산 키 `<Network>:<Kind>:<locator>`는 Solana mint(base58 최대 44자)에서 64자를 넘는다. 자르지 않고 128자로 넓힌다.
-- 활성 매핑 UNIQUE 인덱스(V11 uk_bcm_vndr_ast_active_vendor)와 변경 snapshot(JSONB)은 그대로다. Fireblocks assetId는 기존 길이 안에 있다.
ALTER TABLE bcm_vndr_ast_m
  ALTER COLUMN vndr_ast_id TYPE VARCHAR(128);
