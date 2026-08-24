package com.whatto.bcm.infra.client.fireblocks

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Files
import java.nio.file.Path

/**
 * 벤더 접속 설정 — 시크릿(apiKey·privateKeyPem)은 env/시크릿 매니저로만 주입한다 (git 커밋 금지 — CLAUDE.md 0절).
 * 기본값은 컨텍스트 기동용 안전값 — 키 미설정 상태의 실호출은 서명 시점에 설정 오류로 실패한다.
 */
@ConfigurationProperties("bcm.fireblocks")
data class FireblocksProperties(
    /** 호스트만 — 경로는 클라이언트가 /v1 포함해 구성한다 (JWT uri 클레임과 일치 보장) */
    val baseUrl: String = "https://api.fireblocks.io",
    val apiKey: String = "",
    /** PKCS#8 PEM 본문 */
    val privateKeyPem: String = "",
    /** PKCS#8 PEM 파일. 로컬·서버 실행 스크립트는 본문 대신 이 경로를 주입한다. */
    val privateKeyFile: String = "",
    /** 429 재시도 최대 시도 횟수 (첫 호출 포함) */
    val maxAttempts: Int = 3,
    /** Retry-After 헤더 부재 시 지수 백오프 초기값 (attempt 마다 배증) */
    val retryBackoffMillis: Long = 500,
    /** 1회 대기 상한 — Retry-After·지수 백오프 공통. 벤더가 큰 값을 줘도 요청 스레드를 장기 점유하지 않는다 */
    val maxBackoffMillis: Long = 5_000,
    /** 벤더 API TCP 연결 상한. 소켓 레벨 시한이라 무응답이어도 커넥션이 남지 않는다. */
    val connectTimeoutMillis: Long = 3_000,
    /** 벤더 API 응답 대기 상한. 제출 소유권(claim) TTL 산정의 실제 근거가 되는 값이다. */
    val readTimeoutMillis: Long = 10_000,
    /** 웹훅 RS512 검증 공개키 카탈로그. 환경별 공식 URL을 외부 설정으로만 주입한다. */
    val webhookJwksUrl: String = "https://keys.fireblocks.io/.well-known/jwks.json",
    /** CONTRACT_CALL은 체인 native assetId가 필요하다. 벤더 식별자는 이 경계의 환경 설정에만 둔다. */
    val contractCallGasAssetIds: Map<String, String> = emptyMap(),
    /** JWKS connect/read 및 요청 대기 상한. 외부 통신 장애가 수신부를 잠그지 않게 짧게 둔다. */
    val webhookJwksTimeoutMillis: Long = 3_000,
    /** 낯선 kid 연속 입력이 JWKS 외부 호출을 증폭시키지 않게 하는 비동기 갱신 최소 간격. */
    val webhookJwksRefreshCooldownMillis: Long = 30_000,
) {
    init {
        require(privateKeyPem.isBlank() || privateKeyFile.isBlank()) {
            "privateKeyPem and privateKeyFile cannot be configured together"
        }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(retryBackoffMillis >= 0) { "retryBackoffMillis must not be negative" }
        require(maxBackoffMillis >= 0) { "maxBackoffMillis must not be negative" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(contractCallGasAssetIds.values.all(String::isNotBlank)) { "contractCallGasAssetIds must not be blank" }
        maximumSubmissionFlowMillis
    }

    internal fun resolvePrivateKeyPem(): String {
        if (privateKeyPem.isNotBlank()) return privateKeyPem
        if (privateKeyFile.isBlank()) return ""
        return try {
            Files.readString(Path.of(privateKeyFile))
        } catch (exception: Exception) {
            throw IllegalStateException("bcm.fireblocks.private-key-file을 읽을 수 없습니다", exception)
        }
    }

    /** 연결·응답·429 백오프를 모두 포함한 벤더 API 한 번의 보수적 최장 시간. */
    val maximumCallMillis: Long
        get() =
            Math.addExact(
                Math.multiplyExact(maxAttempts.toLong(), Math.addExact(connectTimeoutMillis, readTimeoutMillis)),
                Math.multiplyExact((maxAttempts - 1).toLong(), maxBackoffMillis),
            )

    /** POST가 400이면 externalTxId 조회가 이어지므로 제출 흐름은 API 호출 두 번까지 잡는다. */
    val maximumSubmissionFlowMillis: Long
        get() = Math.multiplyExact(maximumCallMillis, 2)
}
