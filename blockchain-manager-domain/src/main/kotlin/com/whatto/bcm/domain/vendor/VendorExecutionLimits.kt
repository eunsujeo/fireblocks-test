package com.whatto.bcm.domain.vendor

/** 선택한 벤더의 재시도·후속 조회를 포함한 최장 실행 시간. HTTP별 산식은 구현체가 소유한다. */
interface VendorExecutionLimits {
    /** 재시도·백오프를 포함한 API 한 호출의 양수 상한. */
    val maximumCallMillis: Long

    /** 제출과 응답 불명 회수에 필요한 전체 흐름의 양수 상한. 단일 호출의 배수로 가정하지 않는다. */
    val maximumSubmissionFlowMillis: Long
}
