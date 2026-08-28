package com.whatto.bcm.domain.sweep

interface SweepTargetRepository {
    /** 복합키가 이미 있으면 최초 마킹을 그대로 보존한다. */
    fun insertIfAbsent(target: SweepTarget): Boolean

    fun findByKey(key: SweepTargetKey): SweepTarget?

    /** 웹훅과 제출 마감이 같은 대상을 같은 순서로 잠그기 위한 행 잠금. */
    fun findByKeyForUpdate(key: SweepTargetKey): SweepTarget?

    fun findPending(
        networks: Set<String>,
        limit: Int,
    ): List<SweepTarget>

    /** 제출 준비 트랜잭션에서 같은 대상의 열린 원장 생성을 직렬화한다. */
    fun findPendingForUpdate(key: SweepTargetKey): SweepTarget?

    /** target 삭제 전에 같은 계정·자산에 남은 DAW 요청 항목이 있는지 확인한다. */
    fun hasUnfinishedRequest(key: SweepTargetKey): Boolean

    /** 가용 잔액이 0인 가장 오래된 요청 항목을 온체인 실행 없이 완료한다. 호출자는 target 행 잠금을 보유해야 한다. */
    fun completeOldestPendingWithoutExecution(
        key: SweepTargetKey,
        completedAt: String,
    ): SweepNoSweepRequiredCompletion?

    /** 실패·잔액 잔존 항목만 같은 실행 claim에서 다시 선정 가능하게 푼다. */
    fun releaseClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean

    /** 성공 항목의 잔액이 임계 미만이고 새 확정 입금도 없을 때 현재 claim 행을 삭제한다. */
    fun deleteClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean

    /** 잔액 미달 정리는 미제출 대상에만 허용한다. */
    fun deletePending(key: SweepTargetKey): Boolean
}
