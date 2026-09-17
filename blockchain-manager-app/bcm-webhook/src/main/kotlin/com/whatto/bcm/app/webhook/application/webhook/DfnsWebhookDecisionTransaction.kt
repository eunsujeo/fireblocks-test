package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.tx.FinalityPolicyConfigurationException
import com.whatto.bcm.domain.webhook.UnattributedDepositAlert
import com.whatto.bcm.domain.webhook.WebhookFailureResult
import com.whatto.bcm.domain.webhook.WebhookInboxItem
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import com.whatto.bcm.infra.client.config.ConditionalOnDfnsProtocol
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Dfns 인박스 판단 — 한 건을 잠가 판단에 넘기고 결과를 인박스 상태로 옮긴다(계약13).
 *
 * **전송 알림([DfnsTransferEventDecision])을 먼저 부르고, 전송 사건이 아니면 온체인 이동([DfnsChainEventDecision])으로 넘긴다** —
 * 두 파서는 서로 다른 `kind` 집합만 읽으므로 한쪽이 null이면 다른 쪽 차례다.
 *
 * 발신 이동은 기존 거래에 붙여 확정까지 낸다. 붙이지 못한 발신은 갈라서 다룬다 —
 * **후보 없음은 재시도**(전송 알림이 늦을 수 있다), **후보 여럿·대응 없음·관찰 불일치는 즉시 격리**(시간이 지나도 해소되지 않는다).
 * 미지원/미등록 자산·정밀도 없음·발신 주소 없음만 원장을 쓰지 않고 처리 완료로 남긴다(계약13).
 *
 * 실패 처리는 Fireblocks 경로와 같다 — payload 결함은 재시도/격리, 설정 오류는 P로 남겨 복구 뒤 다시 처리, 그 밖의 오류는 워커가 기록한다.
 */
@Service
@ConditionalOnDfnsProtocol
class DfnsWebhookDecisionTransaction(
    private val inboxRepository: WebhookInboxRepository,
    private val transactionRunner: TransactionRunner,
    private val decision: DfnsChainEventDecision,
    private val transferDecision: DfnsTransferEventDecision,
    private val clock: Clock,
    @param:Value("\${bcm.webhook-worker.max-attempts:3}") private val maxAttempts: Int,
    @param:Value("\${bcm.webhook-worker.retry-base-seconds:30}") private val retryBaseSeconds: Long,
) : WebhookDecisionWork {
    override fun processNext(): WebhookDecisionOutcome = transactionRunner.run { processNextInTransaction() }

    override fun recordUnexpectedFailure(notificationId: String): WebhookDecisionOutcome =
        transactionRunner.run {
            inboxRepository
                .recordFailure(notificationId, UNEXPECTED_FAILURE_REASON, maxAttempts, CoreDateTimes.now(clock), retryBaseSeconds)
                .toOutcome(notificationId)
        }

    private fun processNextInTransaction(): WebhookDecisionOutcome {
        val inboxItem = inboxRepository.findNextPendingForUpdate(CoreDateTimes.now(clock)) ?: return WebhookDecisionOutcome.NoWork
        return try {
            process(inboxItem)
        } catch (exception: WebhookPayloadException) {
            failed(inboxItem, exception.safeReason)
        } catch (exception: FinalityPolicyConfigurationException) {
            // payload poison이 아니라 운영 설정 오류다. inbox를 P로 남겨 설정 복구 후 다시 처리한다.
            throw exception
        } catch (exception: ConflictException) {
            throw WebhookDecisionConflictException(inboxItem.notificationId, exception)
        } catch (exception: RuntimeException) {
            throw WebhookDecisionProcessingException(inboxItem.notificationId, exception)
        }
    }

    private fun process(inboxItem: WebhookInboxItem): WebhookDecisionOutcome {
        val payload = inboxItem.payload.toByteArray()
        // 전송 알림(우리가 낸 발신)을 먼저 본다 — 두 파서는 서로 다른 `kind` 집합만 읽으므로 한쪽이 null이면 다른 쪽 차례다.
        return when (val transfer = transferDecision.decide(inboxItem.notificationId, payload)) {
            is DfnsTransferDecisionOutcome.NotTransferEvent -> processChainEvent(inboxItem, payload)

            is DfnsTransferDecisionOutcome.Processed -> {
                markProcessed(inboxItem)
                WebhookDecisionOutcome.Processed(inboxItem.notificationId, transfer.events.size)
            }

            // 한 제출 키에 전송이 둘 붙었다 — 이중 제출 신호다. 재시도가 결과를 바꾸지 못하므로
            // 상한을 기다리지 않고 **즉시 격리**한다(03 `sbmt_stcd` 전이 표·계약13). 처리 완료로 소거하면 신호가 사라진다.
            is DfnsTransferDecisionOutcome.Conflicting -> quarantineNow(inboxItem, CONFLICTING_TRANSFER_REASON)

            // 제출 원장에 없는 전송은 우리가 만든 게 아니다 — 원장·이벤트를 만들지 않는다.
            // 경보 포트 연결은 후속이라 지금은 처리 완료로 남긴다(계약13 범위 밖).
            is DfnsTransferDecisionOutcome.UnknownSubmission -> {
                markProcessed(inboxItem)
                WebhookDecisionOutcome.Ignored(inboxItem.notificationId)
            }
        }
    }

    private fun processChainEvent(
        inboxItem: WebhookInboxItem,
        payload: ByteArray,
    ): WebhookDecisionOutcome =
        when (val outcome = decision.decide(inboxItem.notificationId, payload)) {
            is DfnsChainDecisionOutcome.Processed -> {
                markProcessed(inboxItem)
                WebhookDecisionOutcome.Processed(inboxItem.notificationId, outcome.events.size)
            }

            // 발신 이동을 기존 거래에 붙였다 — 출금의 확정이 여기서 난다.
            is DfnsChainDecisionOutcome.OutgoingAttached -> {
                markProcessed(inboxItem)
                WebhookDecisionOutcome.Processed(inboxItem.notificationId, outcome.events.size)
            }

            // 같은 (ntwk_cd, tx_hash)에 거래가 여럿이다 — 하나를 고르는 규칙을 지어내면 다른 거래에 남의 확정이 붙는다.
            // 재시도가 결과를 바꾸지 못하므로 **즉시 격리**한다.
            is DfnsChainDecisionOutcome.OutgoingAmbiguous -> quarantineNow(inboxItem, AMBIGUOUS_OUTGOING_REASON)

            // 붙일 거래가 아직 없다 — 전송 알림이 늦게 올 수 있다. **처리 완료로 닫지 않는다**:
            // 닫으면 뒤늦은 알림이 거래를 만들어도 이 사건을 다시 실행할 트리거가 없어 그 출금은 영영 확정되지 않는다.
            // 재시도로 남겨 알림이 오면 붙고, 상한까지 안 오면 격리돼 운영이 본다.
            is DfnsChainDecisionOutcome.OutgoingPending -> failed(inboxItem, PENDING_OUTGOING_REASON)

            // 시간이 지나도 해소되지 않는 이상 신호다 — 재시도 예산을 태우지 않고 즉시 격리한다.
            is DfnsChainDecisionOutcome.OutgoingUnattachable ->
                quarantineNow(inboxItem, "outgoing transfer cannot be attached: ${outcome.miss}")

            is DfnsChainDecisionOutcome.Unattributed -> {
                markProcessed(inboxItem)
                WebhookDecisionOutcome.Unattributed(
                    UnattributedDepositAlert(
                        notificationId = inboxItem.notificationId,
                        // 파생 거래 ID를 만들 수 없는 관찰도 있으므로 경보에는 온체인 hash를 싣는다.
                        vendorTransactionId = outcome.observation.transactionHash,
                        network = outcome.network,
                        symbol = outcome.symbol,
                    ),
                )
            }

            // 아직 판단하지 않는 계열 — 입금이 아니거나(발신·미지원·미등록) 이벤트를 만들 수 없는 관찰이다.
            // 원장·이벤트를 만들지 않고 처리 완료로 남긴다. 무엇을 경보로 올릴지는 계약 확정 뒤 붙인다(계약13).
            else -> {
                markProcessed(inboxItem)
                WebhookDecisionOutcome.Ignored(inboxItem.notificationId)
            }
        }

    /** 재시도가 결과를 바꾸지 못하는 영구 충돌은 상한을 기다리지 않고 한 번에 격리한다(Fireblocks 워커와 같은 규율). */
    private fun quarantineNow(
        inboxItem: WebhookInboxItem,
        safeReason: String,
    ): WebhookDecisionOutcome =
        inboxRepository
            .recordFailure(inboxItem.notificationId, safeReason, IMMEDIATE_QUARANTINE, CoreDateTimes.now(clock), retryBaseSeconds)
            .toOutcome(inboxItem.notificationId)

    private fun failed(
        inboxItem: WebhookInboxItem,
        safeReason: String,
    ): WebhookDecisionOutcome =
        inboxRepository
            .recordFailure(
                inboxItem.notificationId,
                safeReason,
                maxAttempts,
                CoreDateTimes.now(clock),
                retryBaseSeconds,
            ).toOutcome(inboxItem.notificationId)

    private fun WebhookFailureResult.toOutcome(notificationId: String): WebhookDecisionOutcome =
        if (quarantined) {
            WebhookDecisionOutcome.Quarantined(notificationId, retryCount)
        } else {
            WebhookDecisionOutcome.Retrying(notificationId, retryCount)
        }

    /**
     * `vndr_cmpl_yn`은 **남기지 않는다**(항상 `N`). 03이 정의한 이 표식은 Fireblocks `data.status=COMPLETED`이며
     * 원본 보관(`bcm_raw_tx_l`)의 보관·미보관 메트릭과 `vndr_tx_id` 부분 index를 위한 값이다. Dfns 온체인 사건은
     * 인박스 `vndr_tx_id`가 null이라 그 index의 대상이 아니고 Dfns 원본 보관 경로도 아직 없다 —
     * 의미 없는 표식을 남기지 않는다. Dfns 보관 계약은 후속이다(계약13).
     */
    private fun markProcessed(inboxItem: WebhookInboxItem) {
        inboxRepository.markProcessed(inboxItem.notificationId, CoreDateTimes.now(clock), vendorCompleted = false)
    }

    private companion object {
        const val IMMEDIATE_QUARANTINE = 1

        /** 격리 사유는 원문·주소·금액을 담지 않는다 — 인박스에 남는 값이다. */
        const val CONFLICTING_TRANSFER_REASON = "submission key linked to another transfer"

        const val AMBIGUOUS_OUTGOING_REASON = "outgoing transfer matches multiple transactions"

        const val PENDING_OUTGOING_REASON = "outgoing transfer has no matching transaction yet"

        const val UNEXPECTED_FAILURE_REASON = "decision processing failed"
    }
}
