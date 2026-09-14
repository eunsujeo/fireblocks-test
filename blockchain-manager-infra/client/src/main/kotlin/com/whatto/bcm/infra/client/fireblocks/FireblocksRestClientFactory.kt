package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

fun interface FireblocksRestClientFactory {
    fun create(
        builder: RestClient.Builder,
        properties: FireblocksProperties,
    ): RestClient
}

/** Fireblocks 전용 풀링 HTTP 클라이언트. 다른 RestClient.Builder의 전역 정책을 바꾸지 않는다. */
@Component
@ConditionalOnFireblocksProtocol
class PooledFireblocksRestClientFactory : FireblocksRestClientFactory {
    override fun create(
        builder: RestClient.Builder,
        properties: FireblocksProperties,
    ): RestClient {
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
