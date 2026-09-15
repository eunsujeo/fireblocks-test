package com.whatto.bcm.app.api.wallet

import com.whatto.bcm.infra.persistence.config.PersistenceConfig
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Import

/**
 * 내부 생성 유스케이스와 실제 PostgreSQL 원장을 결합하는 슬라이스 앵커.
 * API 조립 지점(BcmApiApplication)을 쓰지 않는 이유 — 기본 실행 빈에는 아직 Dfns 서비스가 없고 Dfns 기동은 계속 차단된다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(PersistenceConfig::class)
class NetworkWalletDatasetTestConfiguration
