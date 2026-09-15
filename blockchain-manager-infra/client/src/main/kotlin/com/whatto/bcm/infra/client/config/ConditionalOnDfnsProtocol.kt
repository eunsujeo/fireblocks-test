package com.whatto.bcm.infra.client.config

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * `BCM_PROVIDER=dfns`에서만 Dfns 설정·서명기·클라이언트·계정/주소 유스케이스를 조립한다.
 * 선택만으로 기동이 열리지는 않는다 — `ProviderConfiguration`의 Dfns 기동 차단은 Baseline 수용 전까지 유지한다(계약13).
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(DfnsProtocolCondition::class)
annotation class ConditionalOnDfnsProtocol

class DfnsProtocolCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = context.environment.getProperty("bcm.provider") == "dfns"
}
