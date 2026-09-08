package com.whatto.bcm.app.bat

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.sweep.SweepInvalidationService
import com.whatto.bcm.app.application.sweep.SweepOutboxEventPublisher
import com.whatto.bcm.app.config.ClockConfig
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling

/** 조립 지점 — bcm-api 와 동일한 배선 규칙 (architecture.md) */
@EnableScheduling
@SpringBootApplication
@Import(
    ClockConfig::class,
    OutboxEventService::class,
    SweepOutboxEventPublisher::class,
    SweepInvalidationService::class,
    AccountQueryService::class,
    DepositAddressQueryService::class,
    VendorAssetMappingQueryService::class,
)
@ComponentScan(
    basePackages = ["com.whatto.bcm.app.bat", "com.whatto.bcm.infra", "com.whatto.bcm.support"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
    ],
)
class BcmBatApplication

fun main(args: Array<String>) {
    runApplication<BcmBatApplication>(*args)
}
