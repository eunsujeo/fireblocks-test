package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.tx.TxStatus

/**
 * 벤더 상태 원어를 공통 어휘 [TxStatus] 로 번역한다 (02-bcm-flow 상태 표).
 *
 * 번역은 비즈니스 판단이라 계약이 domain 에 있고, 어느 원어가 어느 값으로 가는지는
 * 벤더를 아는 infra 가 구현한다 — application 은 이 인터페이스에만 의존한다.
 */
interface VendorStatusTranslator {
    fun translate(
        observation: VendorStatusObservation,
        network: String,
    ): TxStatus
}

/** 번역에 필요한 최소 관찰값 — 벤더 응답이든 웹훅 알림이든 같은 형태로 넘긴다. */
data class VendorStatusObservation(
    val rawStatus: String,
    val subStatus: String?,
    val confirmationCount: Int,
)

/** RBF 계열의 물리 승자 증거 — 벤더 경로마다 같은 판정을 쓰도록 한 곳에 둔다. */
object PhysicalTransactionEvidence {
    fun hasSucceeded(observation: VendorStatusObservation): Boolean =
        observation.confirmationCount > 0 || observation.rawStatus == "COMPLETED"
}
