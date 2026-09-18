package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.id.UuidV7EventIdGenerator
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.sweep.SweepInvalidationService
import com.whatto.bcm.app.application.sweep.SweepObservationService
import com.whatto.bcm.app.application.sweep.SweepOutboxEventPublisher
import com.whatto.bcm.app.application.tx.BoostObservationService
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.app.application.wallet.NetworkWalletQueryService
import com.whatto.bcm.app.config.ClockConfig
import com.whatto.bcm.app.config.ProviderOriginConfiguration
import com.whatto.bcm.app.webhook.application.event.OutboxRelayJob
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/** Webhook 전용 실행 경계와 필요한 공용 유스케이스만 조립한다. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = [WebhookDecisionJob::class, OutboxRelayJob::class])
@Import(
    DepositAddressQueryService::class,
    VendorAssetMappingQueryService::class,
    OutboxEventService::class,
    UuidV7EventIdGenerator::class,
    SubmissionObservationService::class,
    SweepInvalidationService::class,
    SweepObservationService::class,
    SweepOutboxEventPublisher::class,
    BoostObservationService::class,
    TxStateService::class,
    NetworkWalletQueryService::class,
    ClockConfig::class,
    ProviderOriginConfiguration::class,
)
class WebhookRuntimeConfiguration
