package com.whatto.bcm.domain.asset

/**
 * 채택 네트워크의 계정·자산 모델(03 `bcm_blkc_m.chain_mdl_dvcd`). **제공자 정보가 아니라 체인의 속성이다** —
 * 채택한 네트워크는 어느 제공자로 쓰든 이 값을 가진다(V35). 동기화가 만든 미채택 후보만 비어 있을 수 있다.
 * 자산 등록 관문이 이 값으로 자산 키를 만들고, 거래 관찰의 주소 동일성 비교가 이 값으로 규칙을 고른다([TxAddresses]).
 * - EVM: 환경/chainId + ERC-20 contract. 지갑 주소가 곧 토큰 수신 주소다.
 * - SOLANA: cluster + Token Program + mint. 지갑 주소는 owner이고 토큰 잔액은 mint별 token account에 있다(07·계약13).
 */
enum class ChainModel {
    EVM,
    SOLANA,
}
