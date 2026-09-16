package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.NetworkChainTransferStatus
import com.whatto.bcm.domain.vendor.NetworkTransferStatus
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.webhook.WebhookPayloadException

/**
 * Dfns 상태 원어를 공통 어휘 [TxStatus]로 번역한다(02 상태 표·계약13 "상태 번역").
 * 받는 원어는 전송 요청 상태 여섯(`Pending`·`Executing`·`Broadcasted`·`Confirmed`·`Failed`·`Rejected`)과
 * 온체인 이동 상태 둘(`Included`·`Confirmed`)이며, 목록 밖 원어는 임의 상태로 바꾸지 않고 거절한다.
 *
 * **`Confirmed`만으로 확정하지 않는다**(CLAUDE.md 3절) — reorg로 뒤집힐 수 있으므로 관찰의 컨펌 수(블록 깊이)를
 * 네트워크 임계와 비교해 [TxStatus.FINALIZED]를 낸다. 깊이를 모르는 관찰(전송 응답에는 `blockNumber`가 없다)은
 * [TxStatus.CONFIRMED]에 머문다 — 출금의 확정도 같은 거래의 온체인 이동 사건에서 판정한다.
 *
 * **내부 대역이며 실행 빈으로 등록하지 않았다.** 판단 워커 조립은 후속이다.
 */
class DfnsStatusTranslator(
    private val finalityPolicy: FinalityPolicy,
) : VendorStatusTranslator {
    override fun translate(
        observation: VendorStatusObservation,
        network: String,
    ): TxStatus {
        require(observation.confirmationCount >= 0) { "confirmationCount must not be negative" }
        NetworkTransferStatus.ofVendorStatus(observation.rawStatus)?.let { status ->
            return when (status) {
                NetworkTransferStatus.PENDING, NetworkTransferStatus.EXECUTING, NetworkTransferStatus.BROADCASTED ->
                    TxStatus.SUBMITTED
                NetworkTransferStatus.CONFIRMED -> confirmedOrFinalized(observation, network)
                NetworkTransferStatus.FAILED -> TxStatus.FAILED
                NetworkTransferStatus.REJECTED -> TxStatus.REJECTED
            }
        }
        return when (NetworkChainTransferStatus.ofVendorValue(observation.rawStatus)) {
            // 블록에 포함됐지만 벤더 확인 전이다 — 미확정.
            NetworkChainTransferStatus.INCLUDED -> TxStatus.CONFIRMED
            NetworkChainTransferStatus.CONFIRMED -> confirmedOrFinalized(observation, network)
            null -> throw WebhookPayloadException("unsupported Dfns status")
        }
    }

    /**
     * 대사 종결 판정을 만들지 않는다.
     *
     * Dfns 대사 경로(`VendorTransactionPort`의 목록 조회)가 아직 없고, 확정은 블록 깊이로만 내므로 `Confirmed`를
     * 종결로 돌려주면 이 결정(CLAUDE.md 3절)을 우회하게 된다. 포트 계약대로 "대상 밖"은 null이다.
     */
    override fun terminalStatusForReconciliation(
        observation: VendorStatusObservation,
        sourceType: String,
    ): TxStatus? = null

    /** 벤더가 확인했다고 해도 BCM 임계 깊이에 못 미치면 미확정이다. */
    private fun confirmedOrFinalized(
        observation: VendorStatusObservation,
        network: String,
    ): TxStatus =
        if (observation.confirmationCount >= finalityPolicy.requiredConfirmations(network)) {
            TxStatus.FINALIZED
        } else {
            TxStatus.CONFIRMED
        }
}
