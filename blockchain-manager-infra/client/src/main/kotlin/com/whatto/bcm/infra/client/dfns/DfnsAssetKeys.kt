package com.whatto.bcm.infra.client.dfns

/**
 * Dfns 자산의 대조 키 — 자산 매핑의 `vendorAssetId`(등록)와 지갑 자산 관찰(잔액)이 같은 규칙으로 만든다(계약13 "Dfns 데이터셋의 자산 매핑").
 * 채택 명세 1.1018.3 `GET /wallets/{walletId}/assets`의 자산 kind와 locator 필드를 따른다. Dfns에는 벤더 assetId가 없으므로
 * `<Network>:Native` / `<Network>:<Kind>:<locator>` 형식의 결정적 문자열이 그 자리를 대신한다. 현재 모델링한 kind는 EVM(`Native`·`Erc20`)과
 * Solana(`Spl`·`Spl2022`)이며 그 밖의 kind는 등록할 수 없고 잔액 관찰에서도 대조 대상이 아니다.
 * EVM 컨트랙트 주소는 소문자 hex로 정규화한다(주소 동일성은 대소문자와 무관). Solana mint는 base58 그대로다.
 */
internal object DfnsAssetKeys {
    const val NATIVE_KIND = "Native"
    const val ERC20_KIND = "Erc20"
    private val SPL_KINDS = setOf("Spl", "Spl2022")

    /** 명세 Call Function·자산 `contract`의 EVM 주소 형식 — `^0x[0-9a-fA-F]{40}$`. */
    val EVM_CONTRACT_PATTERN: Regex = Regex("0x[0-9a-fA-F]{40}")

    /** 이 kind의 locator를 담는 명세 필드 이름. 모델링하지 않은 kind는 null이다. */
    fun locatorField(kind: String): String? =
        when (kind) {
            NATIVE_KIND -> null
            ERC20_KIND -> "contract"
            in SPL_KINDS -> "mint"
            else -> null
        }

    fun isModeled(kind: String): Boolean = kind == NATIVE_KIND || kind == ERC20_KIND || kind in SPL_KINDS

    fun native(vendorNetwork: String): String = "${requireNetwork(vendorNetwork)}:$NATIVE_KIND"

    fun erc20(
        vendorNetwork: String,
        contract: String,
    ): String {
        require(EVM_CONTRACT_PATTERN.matches(contract)) { "Invalid EVM contract address" }
        return "${requireNetwork(vendorNetwork)}:$ERC20_KIND:${contract.lowercase()}"
    }

    /** 관찰한 자산 항목의 키 — kind가 모델 밖이면 null. locator는 kind가 요구하는 필드 값이다(`Native`는 null). */
    fun of(
        vendorNetwork: String,
        kind: String,
        locator: String?,
    ): String? =
        when {
            kind == NATIVE_KIND -> native(vendorNetwork)
            kind == ERC20_KIND -> erc20(vendorNetwork, requireNotNull(locator) { "Erc20 contract missing" })
            kind in SPL_KINDS -> "${requireNetwork(vendorNetwork)}:$kind:${requireLocator(locator)}"
            else -> null
        }

    private fun requireNetwork(vendorNetwork: String): String {
        require(
            vendorNetwork.isNotBlank() && vendorNetwork == vendorNetwork.trim() && !vendorNetwork.contains(':'),
        ) { "Invalid Dfns network" }
        return vendorNetwork
    }

    private fun requireLocator(locator: String?): String {
        require(!locator.isNullOrBlank() && locator == locator.trim() && !locator.contains(':')) { "Invalid Dfns asset locator" }
        return locator
    }
}
