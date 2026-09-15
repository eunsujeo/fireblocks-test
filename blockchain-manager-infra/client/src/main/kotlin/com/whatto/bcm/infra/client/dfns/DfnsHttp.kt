package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.monitoring.VendorCallMetricOutcome
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Dfns HTTP 호출 공통 — Bearer 인증·선택적 사용자 행위 서명 헤더·응답 원문 바이트 보존.
 * 응답 본문은 상태와 무관하게 바이트 그대로 읽어 돌려준다 — 호출자가 그 바이트로 증적 보관·해석을 한다.
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
        path: String,
        body: ByteArray? = null,
        userAction: String? = null,
    ): DfnsHttpResponse {
        var spec: RestClient.RequestBodySpec =
            restClient
                .method(method)
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.authToken}")
        if (userAction != null) spec = spec.header(USER_ACTION_HEADER, userAction)
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body)
        val response =
            try {
                spec.exchange({ _, clientResponse ->
                    DfnsHttpResponse(clientResponse.statusCode.value(), clientResponse.body.readAllBytes())
                }, false)
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
    val status: Int,
    val body: ByteArray,
) {
    val successful: Boolean get() = status in 200..299

    fun requireSuccess(operation: String): ByteArray {
        if (!successful) throw VendorApiException(operation, status, IllegalStateException("Dfns $operation responded HTTP $status"))
        return body
    }
}
