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
            ChainModel.EVM -> left.equals(right, ignoreCase = true)
            // base58은 대소문자가 값의 일부다.
            ChainModel.SOLANA -> left == right
            // 모델을 모르면 정확히 같을 때만 같다고 한다.
            null -> left == right
        }
}
