package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec

/**
 * 논리 계정에 속한 네트워크 지갑의 생성·조회 경계. 벤더 HTTP schema가 아닌 BCM 내부 계약이다.
 * 기존 Fireblocks vault/asset 주소 생성 포트와 별개이며 아직 실행 어댑터를 조립하지 않는다.
 */
interface NetworkWalletProvisioningPort {
    /**
     * 영속 생성 의도와 최초 제출 권한을 원자적으로 확보한 호출자만 실행한다.
     * correlationId는 추적 값이며 멱등 보장이 아니다. timeout/응답 유실 뒤 이 메서드를 자동 재호출하지 않는다.
     */
    fun create(
        request: NetworkWalletCreationRequest,
        submission: NetworkWalletSubmissionSpec,
    ): NetworkWalletResponse<NetworkWalletObservation>

    /** 기록된 ID를 해당 원천에서 조회한다. null은 현재 미관찰이며 자원 부재나 재생성 허가가 아니다. */
    fun read(
        scope: NetworkWalletScope,
        vendorWalletId: String,
    ): NetworkWalletResponse<NetworkWalletObservation?>

    /**
     * 생성 의도와 관련된 회수 후보를 조회한다. 서버의 exact 검색 지원을 가정하지 않는다.
     * 어댑터는 검증된 조회 방식으로 후보를 좁히고 원천·소유·상관관계 증거를 보존한다.
     * 미지원 조회/오류를 빈 성공 페이지로 바꾸지 않는다. 호출자는 반복 cursor를 거절하고 끝까지 조회해야 한다.
     */
    fun candidates(
        request: NetworkWalletCreationRequest,
        pageCursor: String? = null,
    ): NetworkWalletResponse<VendorPage<NetworkWalletObservation>>
}

/** 어댑터가 같은 실제 원문에서 해석한 정규화 값. 원문을 재직렬화하지 않고 보관 입구로 전달한다. */
class NetworkWalletResponse<T>(
    val value: T,
    body: ByteArray,
) {
    private val originalBody = body.copyOf()

    fun bodyBytes(): ByteArray = originalBody.copyOf()
}

data class NetworkWalletScope(
    val origin: ProviderOrigin,
    val accountId: String,
    /** BCM 네트워크 코드. mainnet/testnet은 별도 코드이고 벤더 network 값은 어댑터에서 변환한다. */
    val network: String,
) {
    init {
        requireWalletIdentifier("accountId", accountId)
        requireWalletIdentifier("network", network)
    }
}

data class NetworkWalletCreationRequest(
    val scope: NetworkWalletScope,
    /** 생성 의도에 한 번 저장한 불변 값. 벤더별 길이/매핑 검증은 어댑터 채택 시 추가한다. */
    val correlationId: String,
) {
    init {
        requireWalletIdentifier("correlationId", correlationId)
    }
}

data class NetworkWalletObservation(
    /** 조회 자격·대상 플랫폼/조직을 검증한 원천. 설정 복사만으로 소유 증거를 만들어서는 안 된다. */
    val origin: ProviderOrigin,
    val network: String,
    /** 지갑 자원 ID이며 signing key ID 또는 온체인 주소가 아니다. */
    val vendorWalletId: String,
    val correlationId: String?,
    val ownership: NetworkWalletOwnership,
    /** 지갑 주소의 준비 여부. 토큰 수신 계정의 준비 완료를 뜻하지 않는다. */
    val address: String?,
) {
    init {
        requireWalletIdentifier("network", network)
        requireWalletIdentifier("vendorWalletId", vendorWalletId)
        correlationId?.let { requireWalletIdentifier("correlationId", it) }
        address?.let { requireWalletIdentifier("address", it) }
    }
}

enum class NetworkWalletOwnership {
    /** 해당 origin의 조직 소유임을 확인함. */
    ORGANIZATION,
    OTHER,
    UNVERIFIED,
}

internal fun requireWalletIdentifier(
    field: String,
    value: String,
) {
    require(value.isNotBlank() && value == value.trim()) { "Invalid network wallet field: $field" }
}
