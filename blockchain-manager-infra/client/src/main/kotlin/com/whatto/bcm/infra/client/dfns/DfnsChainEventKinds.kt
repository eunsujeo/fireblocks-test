package com.whatto.bcm.infra.client.dfns

/**
 * 명세 `WalletHistoryEvent`의 이동 종류 목록과 변형별 필수 필드(계약13 "웹훅 온체인 이동 사건 관찰").
 *
 * oneOf 변형마다 필수 필드가 다르므로 "문서화된 종류인지"와 "그 변형이 요구하는 필드가 있는지"를 나눠서 판단한다.
 * 문서에 없는 종류는 명세를 만족하지 않는 본문이므로 미지원 종류로 받아들이지 않는다.
 */
internal object DfnsChainEventKinds {
    /** 채택 명세 1.1018.3 `WalletHistoryEvent` oneOf가 정의한 이동 종류 전체. */
    val DOCUMENTED: Set<String> =
        setOf(
            "NativeTransfer",
            "Aip21Transfer",
            "AsaTransfer",
            "AssetTransfer",
            "Cip56Transfer",
            "CoinTransfer",
            "Cis2Transfer",
            "Cis7Transfer",
            "Erc20Transfer",
            "Erc721Transfer",
            "Erc7984Transfer",
            "HederaErc20Transfer",
            "HederaErc721Transfer",
            "Hip17Transfer",
            "HtsTransfer",
            "IouTransfer",
            "LockedCoinTransfer",
            "Xls33Transfer",
            "Sep41Transfer",
            "Snip2Transfer",
            "Snip3Transfer",
            "SplTransfer",
            "Spl2022Transfer",
            "Tep74Transfer",
            "Trc10Transfer",
            "Trc20Transfer",
            "Trc721Transfer",
            "UtxoTransfer",
        )

    /** 모든 변형의 required 교집합 중 문자열 필드. `blockNumber`(number)와 `metadata`(object)는 형식이 달라 따로 검사한다. */
    val COMMON_REQUIRED_TEXTS: List<String> = listOf("walletId", "network", "txHash", "timestamp", "status", "direction")

    /**
     * BCM이 등록·잔액·전송에서 모델링한 자산에 대응하는 이동 종류. 이름이 다르므로(`Erc20Transfer` vs `Erc20`) 목록으로 고정하고
     * 접미사를 잘라 추정하지 않는다. 값의 `requiredTexts`·`requiredNumbers`는 그 변형의 required 목록에서 공통 교집합을 뺀 나머지다 —
     * **관찰값에 담지 않는 필드도 결손이면 명세를 만족하지 않으므로 검사한다**(폐기 예정 표기가 붙은 `symbol`·`decimals` 포함).
     */
    fun modeled(historyEventKind: String): ModeledChainAsset? =
        when (historyEventKind) {
            "NativeTransfer" ->
                ModeledChainAsset(DfnsAssetKeys.NATIVE_KIND, listOf("value", "symbol"), listOf("decimals"))
            "Erc20Transfer" ->
                ModeledChainAsset(DfnsAssetKeys.ERC20_KIND, listOf("contract", "from", "to", "value"), listOf("decimals"))
            "SplTransfer" -> ModeledChainAsset(DfnsAssetKeys.SPL_KIND, listOf("mint", "value"), emptyList())
            "Spl2022Transfer" -> ModeledChainAsset(DfnsAssetKeys.SPL_2022_KIND, listOf("mint", "value"), emptyList())
            else -> null
        }
}

/** 모델링한 이동 종류의 자산 kind와 그 변형이 추가로 요구하는 필드. */
internal data class ModeledChainAsset(
    val assetKind: String,
    val requiredTexts: List<String>,
    val requiredNumbers: List<String>,
)
