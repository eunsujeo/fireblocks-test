package com.whatto.bcm.domain.asset

/**
 * 채택 네트워크의 계정·자산 모델(03 `bcm_blkc_m.chain_mdl_dvcd`). Dfns 데이터셋의 DBA seed가 채우며 벤더 카탈로그 동기화(Fireblocks)는 채우지 않는다.
 * - EVM: 환경/chainId + ERC-20 contract. 지갑 주소가 곧 토큰 수신 주소다.
 * - SOLANA: cluster + Token Program + mint. 지갑 주소는 owner이고 토큰 잔액은 mint별 token account에 있다(07·계약13).
 */
enum class ChainModel {
    EVM,
    SOLANA,
}
