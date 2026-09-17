package com.whatto.bcm.domain.submission

import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.UnprocessableRequestException
import com.whatto.bcm.domain.vendor.NetworkTransferObservation
import com.whatto.bcm.domain.vendor.NetworkTransferRequest

/**
 * 네트워크 전송(Dfns) 제출 유스케이스의 판단 규칙(계약13 "출금 제출 계약"). 02의 선기록·소유권·`FAILED` 판정 기준은 그대로 쓰고
 * **회수 수단만 다르다** — 벤더에 `externalId` 조회가 없으므로 같은 본문 재제출이 회수다.
 *
 * 벤더 호출·원장 접근은 하지 않는 순수 판단이다.
 */
object NetworkTransferSubmissionPolicy {
    /**
     * 제출 키를 원장에 적기 **전에** 검사한다. 원장 `ext_tx_id`는 VARCHAR(128)인데 벤더 `externalId`는 1~50자라,
     * 그 사이 길이의 키는 원장에는 들어가지만 벤더에는 영영 나갈 수 없다. 자르면 서로 다른 지시가 한 키로 합쳐지므로
     * **자르지 않고 입구에서 거절**한다 — 원장에만 남고 제출되지 않는 `REQUESTED` 행을 만들지 않기 위해서다.
     */
    fun requireSubmittableKey(externalTransactionId: String) {
        if (externalTransactionId.length > NetworkTransferRequest.EXTERNAL_ID_MAX_LENGTH) {
            throw InvalidRequestException("externalTxId")
        }
    }

    /**
     * 기존 원장 행을 보고 이번 요청이 무엇을 해야 하는지 정한다. 요청 내용이 같다는 것은 호출자가 `req_hash`로 이미 확인했다.
     *
     * `FAILED`에서 02는 "소유권을 잡고 다시 제출한다"지만 Dfns에서는 그렇게 할 수 없다 — 같은 키 재제출은 멱등 계약상
     * **기존 실패 엔티티를 그대로 돌려줄 뿐**이라 새 전송이 만들어지지 않는다. 새 키를 발급하는 길은 열지 않는다:
     * 벤더 `Failed`는 시스템 실패와 **온체인 실행 실패**를 함께 뜻해(`onChainSubmitted = null`) 체인에 이미 나갔을 수 있고,
     * 그 상태에서 새 키로 보내면 같은 자금이 두 번 나간다. 체인 미제출을 확인할 수단(발신 이동 대조)이 생긴 뒤에 연다.
     */
    fun decide(current: SubmissionRecord?): NetworkTransferSubmissionAction =
        when (current?.status) {
            null -> NetworkTransferSubmissionAction.Submit
            SubmissionStatus.SUBMITTED -> NetworkTransferSubmissionAction.AlreadySubmitted
            SubmissionStatus.REQUESTED -> NetworkTransferSubmissionAction.Recover
            SubmissionStatus.FAILED -> NetworkTransferSubmissionAction.RetryNotAllowed
        }

    /** `FAILED` 재시도를 거절한다. 호출자가 [decide]의 결과를 그대로 던지도록 한 곳에 모아 둔다. */
    fun rejectRetry(externalTransactionId: String): Nothing = throw UnprocessableRequestException("submission", externalTransactionId)

    /**
     * 회수·제출로 받은 관찰이 **내 요청 그대로인지** 확인한다. 멱등 재제출은 벤더가 본문을 대조해 다르면 `409`를 주지만,
     * 그 판정을 벤더에만 맡기지 않고 돌려받은 값으로 다시 본다 — 원장에 남길 값은 관찰에서 읽기 때문이다.
     *
     * `externalId`는 벤더가 돌려주지 않을 수도 있어(`null`) 있을 때만 본다 — 없음을 불일치로 바꾸지 않는다.
     */
    fun matches(
        observation: NetworkTransferObservation,
        request: NetworkTransferRequest,
    ): Boolean =
        observation.vendorWalletId == request.vendorWalletId &&
            observation.vendorAssetId == request.vendorAssetId &&
            observation.destinationAddress == request.destinationAddress &&
            observation.amountBaseUnits == request.amountBaseUnits &&
            (observation.externalId == null || observation.externalId == request.externalId)
}

/** [NetworkTransferSubmissionPolicy.decide]의 결과. */
enum class NetworkTransferSubmissionAction {
    /** 원장에 행이 없다 — 선기록하고 벤더에 제출한다. */
    Submit,

    /** 이미 제출을 마쳤다 — 기록된 벤더 전송 ID로 응답한다. 벤더를 부르지 않는다. */
    AlreadySubmitted,

    /** 앞선 시도의 결말을 모른다 — 같은 본문 재제출로 회수한다(멱등이라 이중 전송이 아니다). */
    Recover,

    /** 종결 실패다 — 같은 키로는 새 전송을 만들 수 없고 새 키 발급은 아직 열지 않았다. */
    RetryNotAllowed,
}
