package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.asset.ChainModel

/**
 * 주소 동일성 비교(03 V34·V32 "주소 동일성 비교"). **전역 `lowercase()` 한 규칙으로 만들지 않는다** —
 * 체인마다 대소문자의 뜻이 다르다.
 */
object TxAddresses {
    /**
     * 두 주소가 같은 주소인가. 모르는 체인 모델은 **정규화하지 않고 fail-closed**다 —
     * 임의로 맞춰 보면 다른 주소를 같다고 할 수 있다.
     */
    fun sameAddress(
        left: String,
        right: String,
        chainModel: ChainModel?,
    ): Boolean =
        when (chainModel) {
            // 16진수라 대소문자에 정보가 없다. 벤더가 사건과 응답에서 다른 표기를 줘도 같은 주소다.
            // **형식을 먼저 본다**(03 V32 "형식 검증 뒤 20바이트 값") — 20바이트 hex가 아닌 문자열까지
            // 대소문자만 무시하고 같다고 하면, 주소가 아닌 값끼리도 같다고 하게 된다.
            ChainModel.EVM -> if (isEvmAddress(left) && isEvmAddress(right)) left.equals(right, ignoreCase = true) else left == right
            // base58은 대소문자가 값의 일부다.
            ChainModel.SOLANA -> left == right
            // 모델을 모르면 정확히 같을 때만 같다고 한다.
            null -> left == right
        }

    /** `0x` + 20바이트 hex. 체크섬 대문자 여부는 보지 않는다 — 그건 표기이지 값이 아니다. */
    private fun isEvmAddress(value: String): Boolean = EVM_ADDRESS.matches(value)

    private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
}
