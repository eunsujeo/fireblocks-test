package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.asset.TokenStandard

/**
 * Dfns 자산의 대조 키 — 자산 매핑의 `vendorAssetId`(등록)와 지갑 자산 관찰(잔액)이 같은 규칙으로 만든다(계약13 "Dfns 데이터셋의 자산 매핑").
 * 채택 명세 1.1018.3 `GET /wallets/{walletId}/assets`의 자산 kind와 locator 필드를 따른다. Dfns에는 벤더 assetId가 없으므로
 * `<Network>:Native` / `<Network>:<Kind>:<locator>` 형식의 결정적 문자열이 그 자리를 대신한다. 현재 모델링한 kind는 EVM(`Native`·`Erc20`)과
 * Solana(`Spl`·`Spl2022`)이며 그 밖의 kind는 등록할 수 없고 잔액 관찰에서도 대조 대상이 아니다.
 * EVM 컨트랙트 주소는 소문자 hex로 정규화한다(주소 동일성은 대소문자와 무관). Solana mint는 base58 32바이트 공개키를 그대로 둔다(대소문자 구분).
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
            // 관찰 경로도 등록 경로와 같은 mint 검사(base58 32바이트)를 거친다 — 형식이 깨진 mint를 키로 만들어 미보유 0으로 축소하지 않는다.
            kind in SPL_KINDS ->
                spl(
                    vendorNetwork,
                    if (kind ==
                        SPL_KIND
                    ) {
                        TokenStandard.SPL
                    } else {
                        TokenStandard.SPL_2022
                    },
                    requireNotNull(locator) { "mint missing" },
                )
            else -> null
        }

    /** Solana SPL/Token-2022 토큰 키 — mint는 base58 32바이트 공개키여야 한다. */
    fun spl(
        vendorNetwork: String,
        standard: TokenStandard,
        mint: String,
    ): String {
        require(isSolanaPublicKey(mint)) { "Invalid Solana mint address" }
        val kind = if (standard == TokenStandard.SPL) SPL_KIND else SPL_2022_KIND
        return "${requireNetwork(vendorNetwork)}:$kind:$mint"
    }

    /** base58(비트코인 알파벳)로 32바이트가 되는 문자열인지 — Solana 공개키/mint 형식. */
    fun isSolanaPublicKey(value: String): Boolean = decodeBase58(value)?.size == SOLANA_PUBLIC_KEY_BYTES

    private fun decodeBase58(value: String): ByteArray? {
        if (value.isEmpty() || value.length > SOLANA_PUBLIC_KEY_MAX_CHARS) return null
        var number = java.math.BigInteger.ZERO
        for (char in value) {
            val digit = BASE58_ALPHABET.indexOf(char)
            if (digit < 0) return null
            number = number.multiply(BASE58_RADIX).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        val leadingZeros = value.takeWhile { it == BASE58_ALPHABET[0] }.length
        val magnitude =
            number.toByteArray().let { bytes ->
                if (bytes.size > 1 &&
                    bytes[0] == 0.toByte()
                ) {
                    bytes.copyOfRange(1, bytes.size)
                } else {
                    bytes
                }
            }
        val digits = if (number == java.math.BigInteger.ZERO) ByteArray(0) else magnitude
        return ByteArray(leadingZeros) + digits
    }

    const val SPL_KIND = "Spl"
    const val SPL_2022_KIND = "Spl2022"
    private const val SOLANA_PUBLIC_KEY_BYTES = 32
    private const val SOLANA_PUBLIC_KEY_MAX_CHARS = 44
    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE58_RADIX = java.math.BigInteger.valueOf(58)

    /**
     * 등록된 자산 키를 벤더 network·kind·locator로 되돌린다(전송 본문 구성용). 모델링한 kind가 아니거나 형식이 다르면 null이다 —
     * 키를 만든 규칙과 같은 검사(EVM 주소 형식·Solana base58 32바이트)를 다시 적용해 저장된 값이 손상됐으면 전송하지 않는다.
     */
    fun parse(vendorAssetId: String): DfnsAssetKey? {
        val segments = vendorAssetId.split(':')
        val network = segments.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val kind = segments.getOrNull(1) ?: return null
        val locator = segments.getOrNull(2)
        if (segments.size > 3) return null
        if (!isModeled(kind)) return null
        val expectedLocatorField = locatorField(kind)
        if ((expectedLocatorField == null) != (locator == null)) return null
        val rebuilt = runCatching { of(network, kind, locator) }.getOrNull() ?: return null
        if (rebuilt != vendorAssetId) return null
        return DfnsAssetKey(network, kind, expectedLocatorField, locator)
    }

    private fun requireNetwork(vendorNetwork: String): String {
        require(
            vendorNetwork.isNotBlank() && vendorNetwork == vendorNetwork.trim() && !vendorNetwork.contains(':'),
        ) { "Invalid Dfns network" }
        return vendorNetwork
    }
}

/** 자산 키의 구성 요소 — `locatorField`가 null이면 네이티브(추가 필드 없음)다. */
internal data class DfnsAssetKey(
    val vendorNetwork: String,
    val kind: String,
    val locatorField: String?,
    val locator: String?,
)
