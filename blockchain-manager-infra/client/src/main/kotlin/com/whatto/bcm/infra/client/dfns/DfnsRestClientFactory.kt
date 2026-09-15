package com.whatto.bcm.infra.client.dfns

import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

fun interface DfnsRestClientFactory {
    fun create(
        builder: RestClient.Builder,
        properties: DfnsProperties,
    ): RestClient
}

/** Dfns 전용 풀링 HTTP 클라이언트. 실행 모듈이 조립하기 전까지 Spring 빈으로 등록하지 않는다. */
class PooledDfnsRestClientFactory : DfnsRestClientFactory {
    override fun create(
        builder: RestClient.Builder,
        properties: DfnsProperties,
    ): RestClient {
        properties.requireConnection()
        val httpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis))
                .build()
        val requestFactory =
            JdkClientHttpRequestFactory(httpClient).apply {
                setReadTimeout(Duration.ofMillis(properties.readTimeoutMillis))
            }
        return builder
            .baseUrl(properties.baseUrl)
            .requestFactory(requestFactory)
            .build()
    }
}
