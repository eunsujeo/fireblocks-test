package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.vendor.VendorExecutionLimits
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dfns 접속 설정 — `DfnsClientConfig`가 `BCM_PROVIDER=dfns`에서만 바인딩한다. API 전체 컨텍스트의 Dfns 기동 차단은 별개로 유지된다(계약13).
 * 시크릿(authToken·credential 개인키)은 env/시크릿 매니저로만 주입한다 (CLAUDE.md 0절). 기본 URL을 두지 않는다 —
 * Baseline은 고객 환경 배포이므로 공개 SaaS 주소를 기본값으로 호출하지 않는다.
 * 근거: 공식 OpenAPI 1.1018.3의 securitySchemes(authenticationToken Bearer·X-DFNS-USERACTION)와 서버 목록.
 */
@ConfigurationProperties("bcm.dfns")
data class DfnsProperties(
    /** Baseline API origin. 경로 없이 scheme+host(+port)만 — 요청 path는 명세 경로를 그대로 쓴다. */
    val baseUrl: String = "",
    /** 서비스 계정 Bearer 토큰 (authenticationToken). */
    val authToken: String = "",
    /** 사용자 행위 서명에 쓰는 Key credential ID (`cr-…`). allowCredentials.key 목록에 있어야 서명한다. */
    val credentialId: String = "",
    /** Key credential의 PKCS#8 개인키 PEM 본문. */
    val credentialPrivateKeyPem: String = "",
    /** Key credential의 PKCS#8 개인키 PEM 파일. 본문과 함께 설정할 수 없다. */
    val credentialPrivateKeyFile: String = "",
    val connectTimeoutMillis: Long = 3_000,
    val readTimeoutMillis: Long = 10_000,
    /** GET /wallets 한 페이지 크기 — 명세 limit 1..500. */
    val candidatePageSize: Int = 100,
    /** BCM 네트워크 코드 → 명세 `network` enum 값. 역방향 변환에도 쓰므로 값이 중복되면 안 된다. */
    val networks: Map<String, String> = emptyMap(),
    /**
     * 지갑 주소가 곧 토큰 수신 주소라고 운영자가 확인한 BCM 네트워크 코드(EVM 계정 모델·tag/memo 없음). `networks`의 키여야 한다.
     * Solana는 owner 주소 SPL 수신이 Baseline 수용 항목(계약13)이라 수용 전에는 넣지 않는다 — 넣는 순간 주소 발급 경로가 열린다.
     * 여기 없는 네트워크의 토큰 주소 발급은 지원하지 않는 자산으로 거절한다 — 수신 주소 모델 확인은 운영 결정이며 코드가 추정하지 않는다.
     */
    val accountAddressNetworks: Set<String> = emptySet(),
    /** 네트워크 지갑 준비가 진행 중일 때 호출자에게 안내하는 재시도 초. BCM 폴링 정책이며 벤더 보장이 아니다. */
    val provisioningRetryAfterSeconds: Long = 5,
) : VendorExecutionLimits {
    init {
        require(credentialPrivateKeyPem.isBlank() || credentialPrivateKeyFile.isBlank()) {
            "credentialPrivateKeyPem and credentialPrivateKeyFile cannot be configured together"
        }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(candidatePageSize in 1..MAX_CANDIDATE_PAGE_SIZE) { "candidatePageSize must be within 1..$MAX_CANDIDATE_PAGE_SIZE" }
        require(networks.keys.all { it.isNotBlank() && it == it.trim() } && networks.values.all { it.isNotBlank() && it == it.trim() }) {
            "networks must not contain blank codes"
        }
        require(networks.values.toSet().size == networks.size) { "networks must map each vendor network to a single BCM code" }
        require(accountAddressNetworks.all { it in networks.keys }) { "accountAddressNetworks must be a subset of configured networks" }
        require(provisioningRetryAfterSeconds >= 1) { "provisioningRetryAfterSeconds must be positive" }
    }

    /** 실제 호출 직전에만 요구한다 — 설정 클래스 생성만으로 자격을 강제하지 않는다. */
    fun requireConnection() {
        val uri = runCatching { URI(baseUrl) }.getOrElse { throw IllegalStateException("bcm.dfns.base-url is not a valid URI", it) }
        check(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null) {
            "bcm.dfns.base-url must be an http(s) origin without path or query"
        }
        check(authToken.isNotBlank()) { "bcm.dfns.auth-token is required" }
        check(credentialId.isNotBlank()) { "bcm.dfns.credential-id is required" }
        check(credentialPrivateKeyPem.isNotBlank() || credentialPrivateKeyFile.isNotBlank()) {
            "bcm.dfns.credential-private-key-pem or credential-private-key-file is required"
        }
    }

    internal fun resolveCredentialPrivateKeyPem(): String {
        if (credentialPrivateKeyPem.isNotBlank()) return credentialPrivateKeyPem
        if (credentialPrivateKeyFile.isBlank()) return ""
        return try {
            Files.readString(Path.of(credentialPrivateKeyFile))
        } catch (exception: Exception) {
            throw IllegalStateException("bcm.dfns.credential-private-key-file을 읽을 수 없습니다", exception)
        }
    }

    /** 재시도를 두지 않으므로 한 호출은 연결+응답 대기 상한이다. 429/지연 정책은 실제 Baseline 동작 확인 뒤 정한다. */
    override val maximumCallMillis: Long
        get() = Math.addExact(connectTimeoutMillis, readTimeoutMillis)

    /** 생성 한 번 = 챌린지 생성 + 서명 제출 + 지갑 생성의 HTTP 세 번. */
    override val maximumSubmissionFlowMillis: Long
        get() = Math.multiplyExact(maximumCallMillis, USER_ACTION_CALLS_PER_MUTATION)

    companion object {
        const val MAX_CANDIDATE_PAGE_SIZE = 500
        private const val USER_ACTION_CALLS_PER_MUTATION = 3L
    }
}
