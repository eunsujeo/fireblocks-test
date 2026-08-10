package com.whatto.bcm.infra.client.config

import com.whatto.bcm.infra.client.fireblocks.FireblocksJwtSigner
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * client 모듈 배선 — 조립은 app 모듈 스캔이 담당한다 (architecture.md).
 * Clock 빈은 app 조립부(ClockConfig) 소유 — 여기서는 주입만 받는다.
 */
@Configuration
@EnableConfigurationProperties(FireblocksProperties::class)
class ClientConfig {
    @Bean
    fun fireblocksJwtSigner(
        properties: FireblocksProperties,
        clock: Clock,
    ): FireblocksJwtSigner = FireblocksJwtSigner(properties.apiKey, properties.privateKeyPem, clock)
}
