package com.whatto.bcm.domain.asset

/**
 * 등록 요청이 지정하는 토큰 표준 — 같은 mint 주소로 Token Program을 구분할 수 없어 Solana 토큰은 운영자가 명시한다(07).
 * EVM 토큰은 ERC-20 하나라 지정하지 않는다.
 */
enum class TokenStandard {
    SPL,
    SPL_2022,
}
