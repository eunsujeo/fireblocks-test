package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.asset.TokenStandard
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.BcmException

/**
 * Admin 자산 등록의 자산 지정을 벤더/체인 원천에서 **정확한 물리 자산 하나**로 해소하는 출력 포트(07 관문 넷의 "벤더 재해소").
 * 제공자별 구현이 어휘를 소유한다 — Fireblocks는 카탈로그의 assetId·컨트랙트 주소를, Dfns는 채택 명세의 network·자산 kind·locator를 대조한다.
 * 유스케이스는 어느 벤더인지 알지 않고 결과의 vendorAssetId·contractAddress만 현재 매핑에 저장한다.
 *
 * - 한 네트워크의 여러 locator를 한 번에 해소한다. 구현은 벤더 카탈로그를 네트워크마다 한 번만 읽을 수 있다.
 * - 항목별 실패는 예외로 던지지 않고 [ChainAssetResolution.Rejected]로 돌려준다 — 일괄 등록이 실패한 항목의 index를 정확히 표시한다.
 *   네트워크 전체에 해당하는 실패(벤더 조회 실패 등)만 예외로 전파한다.
 * - 벤더 호출 없이 판정할 수 있는 실패는 [inspect]로 먼저 드러내 필수값 누락이 외부 호출·벤더 장애에 가려지지 않게 한다.
 */
interface ChainAssetResolver {
    /**
     * 벤더 호출 없이 판정할 수 있는 항목 실패(필수값 누락·형식 오류 등) — 유스케이스가 외부 호출 전에 index 순서대로 먼저 거절한다.
     * null이면 이 단계에서는 거절 사유가 없다는 뜻이며 해소 결과를 보장하지 않는다.
     */
    fun inspect(
        blockchain: VendorBlockchainCatalog,
        locator: ChainAssetLocator,
    ): BcmException? = null

    fun resolveAll(
        blockchain: VendorBlockchainCatalog,
        locators: List<ChainAssetLocator>,
    ): List<ChainAssetResolution>
}

/**
 * 등록 요청의 자산 지정. `contractAddress == null`은 그 네트워크의 네이티브 자산이다(Solana에서는 mint 주소 자리).
 * `fireblocksAssetId`는 Fireblocks 후보 목록에서 고른 assetId이며 Fireblocks 원천에서는 필수, 다른 원천에서는 없어야 한다(있으면 거절).
 * `tokenStandard`는 Solana 토큰의 Token Program 구분이며 EVM·Fireblocks 원천에서는 없어야 한다.
 */
data class ChainAssetLocator(
    val network: String,
    val fireblocksAssetId: String?,
    val contractAddress: String?,
    val tokenStandard: TokenStandard? = null,
)

/** 해소된 물리 자산 — vendorAssetId는 벤더 호출·잔액 관찰의 대조 키이고 contractAddress는 대조한 근거의 사본이다(네이티브는 null). */
data class ResolvedChainAsset(
    val vendorAssetId: String,
    val contractAddress: String?,
    val decimals: Int?,
)

sealed interface ChainAssetResolution {
    data class Resolved(
        val asset: ResolvedChainAsset,
    ) : ChainAssetResolution

    /** 그 항목만의 실패 — 사유는 유스케이스가 공개 오류로 번역한다(`InvalidAssetMappingException`·`ConflictException` 등). */
    data class Rejected(
        val failure: BcmException,
    ) : ChainAssetResolution
}
