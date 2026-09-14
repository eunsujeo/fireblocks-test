package com.whatto.bcm.infra.client.config

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/** 로컬 Stub도 기존 Fireblocks 전송·웹훅 프로토콜을 사용한다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(FireblocksProtocolCondition::class)
annotation class ConditionalOnFireblocksProtocol

class FireblocksProtocolCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = context.environment.getProperty("bcm.provider") in setOf("fireblocks", "local")
}
