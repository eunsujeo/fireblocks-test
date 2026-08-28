package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateRepository
import com.whatto.bcm.domain.admin.ExecutionGateType
import org.springframework.stereotype.Service

/** 다른 피처가 실행 게이트 저장소와 잠금 규칙을 직접 참조하지 않도록 캡슐화한다. */
@Service
class ExecutionGateQueryService(
    private val repository: ExecutionGateRepository,
) {
    fun lockAndFindCurrent(
        network: String,
        type: ExecutionGateType,
    ): ExecutionGateEvent? = repository.lockAndFindCurrent(network, type)
}
