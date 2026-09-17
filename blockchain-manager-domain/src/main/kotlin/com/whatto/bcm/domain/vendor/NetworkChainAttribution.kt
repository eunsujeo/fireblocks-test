package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.asset.AssetDecimals

/**
 * 온체인 이동 관찰을 BCM 원장에 귀속시키는 **순수 판정**(계약13 "온체인 이동의 귀속").
 * 저장·발행·상태 번역을 하지 않는다 — 어떤 업무 대상인지만 가른다. 상태 번역은 `VendorStatusTranslator`, 발행은 판단 워커의 몫이다.
 *
 * Fireblocks 경로는 등록하지 않은 자산 알림을 원문 결함으로 거절하지만, Dfns의 조직 지갑은 등록하지 않은 토큰도 받을 수 있다.
 * 그래서 미지원·미등록 자산과 미귀속 주소는 **예외가 아니라 결과로** 가른다 — 운영이 보고 판단할 신호이지 수신 실패가 아니다.
 */
object NetworkChainAttribution {
    fun attribute(
        observation: NetworkChainTransfer,
        ledger: NetworkChainLedgerLookup,
    ): NetworkChainAttributionResult {
        // 우리 지갑에서 나간 이동은 주소로 가르지 않는다(02 웹훅 계열 분류) — 블록 좌표를 어느 거래에 적용할지는 `NetworkChainOutgoingCoordinate`가 정한다.
        if (observation.direction == NetworkChainDirection.OUT) return NetworkChainAttributionResult.Outgoing(observation)
        val vendorAssetId =
            observation.vendorAssetId
                ?: return NetworkChainAttributionResult.UnsupportedAsset(observation)
        val asset =
            ledger.assetOf(vendorAssetId)
                ?: return NetworkChainAttributionResult.UnmappedAsset(observation)
        // 자산 키는 벤더 network를 포함하므로 등록 매핑의 네트워크와 어긋날 수 없다 — 어긋나면 등록 데이터 결함이다.
        check(asset.network == observation.network) {
            "registered asset network drift: vendorAssetId=$vendorAssetId observed=${observation.network} registered=${asset.network}"
        }
        val destination =
            observation.toAddress
                ?: return unattributed(observation, asset, NetworkChainAttributionMiss.MISSING_DESTINATION)
        val accountId =
            ledger.accountOfDepositAddress(destination, asset.network, asset.symbol)
                ?: return unattributed(observation, asset, NetworkChainAttributionMiss.UNKNOWN_ADDRESS)
        return NetworkChainAttributionResult.Deposit(observation, accountId, asset.network, asset.symbol, asset.decimals)
    }

    private fun unattributed(
        observation: NetworkChainTransfer,
        asset: LedgerAsset,
        miss: NetworkChainAttributionMiss,
    ) = NetworkChainAttributionResult.Unattributed(observation, miss, asset.network, asset.symbol)
}

/** 귀속에 필요한 BCM 원장 조회. 구현은 실행 모듈이 기존 조회 서비스로 연결한다. */
interface NetworkChainLedgerLookup {
    /** 등록한 자산 매핑. 없으면 null이다. */
    fun assetOf(vendorAssetId: String): LedgerAsset?

    /** 발급한 입금 주소의 계정. 없으면 null이다. */
    fun accountOfDepositAddress(
        address: String,
        network: String,
        symbol: String,
    ): String?
}

data class LedgerAsset(
    val network: String,
    val symbol: String,
    /**
     * 등록 시점에 확정한 정밀도(03 V27). 최소 단위만 오는 관찰을 이벤트 금액으로 환산할 때 쓴다 —
     * 값이 없는 매핑(V27 이전 등록)은 환산하지 않는다.
     */
    val decimals: Int? = null,
) {
    init {
        require(network.isNotBlank()) { "network must not be blank" }
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        require(decimals == null || AssetDecimals.isValid(decimals)) { "Invalid asset decimals" }
    }
}

sealed interface NetworkChainAttributionResult {
    val observation: NetworkChainTransfer

    /** 발급 주소로 들어온 관리 입금. */
    data class Deposit(
        override val observation: NetworkChainTransfer,
        val accountId: String,
        val network: String,
        val symbol: String,
        /** 등록 정밀도. 없으면 이벤트 금액을 만들 수 없다(03 V27 이전 등록 행) — 판단 워커가 그 경우를 가른다. */
        val decimals: Int?,
    ) : NetworkChainAttributionResult

    /** 우리 지갑 발신 — 사건의 블록 좌표를 어느 거래에 적용할지는 `NetworkChainOutgoingCoordinate`가 정한다. */
    data class Outgoing(
        override val observation: NetworkChainTransfer,
    ) : NetworkChainAttributionResult

    /** BCM이 모델링하지 않은 이동 종류(NFT·UTXO 등) — 등록할 수 있는 자산이 아니다. */
    data class UnsupportedAsset(
        override val observation: NetworkChainTransfer,
    ) : NetworkChainAttributionResult

    /** 모델링했지만 등록하지 않은 자산 — 운영이 등록 여부를 판단한다. */
    data class UnmappedAsset(
        override val observation: NetworkChainTransfer,
    ) : NetworkChainAttributionResult

    /** 등록 자산인데 우리 주소로 귀속되지 않는다 — 경보 대상이다. 자산은 이미 해소됐으므로 경보에 우리 어휘(network·symbol)를 싣는다. */
    data class Unattributed(
        override val observation: NetworkChainTransfer,
        val miss: NetworkChainAttributionMiss,
        val network: String,
        val symbol: String,
    ) : NetworkChainAttributionResult
}

enum class NetworkChainAttributionMiss {
    /** 사건에 목적지 주소가 없다 — 명세상 일부 이동 종류에서 선택 필드다. */
    MISSING_DESTINATION,

    /** 우리가 발급한 입금 주소가 아니다. */
    UNKNOWN_ADDRESS,
}
