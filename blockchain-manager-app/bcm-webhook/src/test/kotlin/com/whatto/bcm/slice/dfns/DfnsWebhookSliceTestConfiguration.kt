package com.whatto.bcm.slice.dfns

import com.whatto.bcm.infra.persistence.config.PersistenceConfig
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Import

/**
 * Dfns 판단 경로와 실제 PostgreSQL 원장을 결합하는 슬라이스 앵커.
 * Webhook 조립 지점(BcmWebhookApplication)을 쓰지 않는 이유 — `ProviderConfiguration`의 Dfns 기동 차단은 계속 유지되므로
 * 전체 컨텍스트를 여기서 열지 않는다(계약13).
 *
 * 패키지가 `com.whatto.bcm.app.webhook` 밖인 것은 의도다 — 실행 앱은 그 패키지를 훑으므로 슬라이스 전용 빈이 거기 있으면
 * **전체 컨텍스트에 섞인다**. `@TestConfiguration`으로도 걸러지지 않아 Fireblocks 조립의 단일 후보 주입이 깨진 적이 있다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(PersistenceConfig::class)
class DfnsWebhookSliceTestConfiguration
