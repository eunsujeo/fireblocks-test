package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.monitoring.VendorCallMetricOutcome
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriBuilder
import java.net.URI

/**
 * Dfns HTTP 호출 공통 — Bearer 인증·선택적 사용자 행위 서명 헤더·응답 원문 바이트 보존.
 * 응답 본문은 상태와 무관하게 바이트 그대로 읽어 돌려주고, 오류로 변환할 때도 같은 바이트를 예외에 담아 증적 보관이 가능하게 한다.
 * URI는 RestClient의 UriBuilder에 경로/쿼리 변수를 넘겨 한 번만 인코딩한다 — opaque 페이지 토큰을 손상시키지 않는다.
 * 재시도는 두지 않는다. 429/지연 정책은 실제 Baseline 동작을 확인한 뒤 정한다(계약13).
 */
internal class DfnsHttp(
    private val restClient: RestClient,
    private val properties: DfnsProperties,
    private val metrics: OperationalMetricsPort,
) {
    fun call(
        operation: String,
        method: HttpMethod,
        body: ByteArray? = null,
        userAction: String? = null,
        uri: (UriBuilder) -> URI,
    ): DfnsHttpResponse {
        var spec: RestClient.RequestBodySpec =
            restClient
                .method(method)
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.authToken}")
        if (userAction != null) spec = spec.header(USER_ACTION_HEADER, userAction)
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body)
        val response =
            try {
                // exchange는 함수 반환 뒤 응답을 닫는다 — 본문은 여기서 전부 바이트로 읽는다.
                spec.exchange { _, clientResponse ->
                    DfnsHttpResponse(operation, clientResponse.statusCode.value(), clientResponse.body.readAllBytes())
                }
            } catch (exception: RestClientException) {
                metrics.recordVendorCall(operation, VendorCallMetricOutcome.ERROR)
                throw VendorApiException(operation, null, exception)
            }
        metrics.recordVendorCall(operation, if (response.successful) VendorCallMetricOutcome.SUCCESS else VendorCallMetricOutcome.ERROR)
        return response
    }

    companion object {
        const val USER_ACTION_HEADER = "X-DFNS-USERACTION"
    }
}

internal class DfnsHttpResponse(
    val operation: String,
    val status: Int,
    val body: ByteArray,
) {
    val successful: Boolean get() = status in 200..299

    /** 2xx가 아니면 상태와 수신 바이트를 담아 실패한다 — 호출 서비스가 그 바이트를 증적으로 보관한다. */
    fun requireSuccess(): ByteArray {
        if (!successful) throw failure("Dfns $operation responded HTTP $status")
        return body
    }

    /** 응답을 해석할 수 없을 때 — 같은 상태·바이트를 담아 실패한다. 메시지에는 필드 이름만 넣고 본문은 넣지 않는다. */
    fun failure(
        reason: String,
        cause: Throwable? = null,
    ): VendorApiException = VendorApiException(operation, status, cause ?: IllegalStateException(reason), body)
}
