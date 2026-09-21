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
 * 발신 이동 사건은 그 `txHash`의 **우리 발신 거래에 블록 좌표를 적용**해 확정까지 낸다. 그 거래가 아직 없으면
 * **재시도로 남긴다** — 전송 알림이 늦을 수 있고, 처리 완료로 닫으면 그 출금은 영영 확정되지 않는다.
 * 수신 이동이 **우리 내부이체의 수신측**이면 입금을 만들지 않고 닫으며, 결속 전이면 같은 이유로 재시도로 남긴다(계약13).
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

            // 관찰이 이미 적힌 금액·주소와 다른 사실을 말한다 — 원장·이벤트를 쓰지 않았고 재시도가 값을 바꾸지 못한다.
            // **즉시 격리**해 원문을 남긴다(03 V32). 사유에는 어긋난 항목만 들어 있다.
            is DfnsTransferDecisionOutcome.ObservationConflict -> quarantineNow(inboxItem, transfer.safeReason)

            // 우리 지갑에서 **우리가 내지 않은 전송**이 나갔다는 뜻이다 — 그 자체로 이상 신호이고,
            // 처리 완료로 소거하면 원문이 인박스에서 사라져 나중에 같은 트랜잭션의 이동을 판단할 근거도 없어진다.
            // 원장·이벤트는 만들지 않되 **격리해 원문과 사실을 남긴다**(계약13).
            is DfnsTransferDecisionOutcome.UnknownSubmission -> quarantineNow(inboxItem, UNKNOWN_TRANSFER_REASON)
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

            // 이 hash의 우리 발신 거래에 블록 좌표를 적용했다 — 출금의 확정이 여기서 난다.
            is DfnsChainDecisionOutcome.OutgoingAdvanced -> {
                markProcessed(inboxItem)
                WebhookDecisionOutcome.Processed(inboxItem.notificationId, outcome.events.size)
            }

            // 그 hash의 발신 거래가 아직 없다 — 전송 알림이 늦게 올 수 있다. **처리 완료로 닫지 않는다**:
            // 닫으면 뒤늦은 알림이 거래를 만들어도 이 사건을 다시 실행할 트리거가 없어 그 출금은 영영 확정되지 않는다.
            // 재시도로 남겨 알림이 오면 적용되고, 상한까지 안 오면 격리돼 운영이 본다.
            is DfnsChainDecisionOutcome.OutgoingPending -> failed(inboxItem, PENDING_OUTGOING_REASON)

            // 우리 내부이체의 수신측이다 — 업무 이벤트는 제출 원장 쪽에서 이미 났다. 원장·이벤트를 만들지 않고 닫는다.
            is DfnsChainDecisionOutcome.InternalReceipt -> {
                markProcessed(inboxItem)
                WebhookDecisionOutcome.Ignored(inboxItem.notificationId)
            }

            // 발신이 우리 지갑인데 그 hash의 발신 거래가 아직 없다 — 입금으로 확정하면 되돌릴 수 없다. 재시도로 남긴다.
            is DfnsChainDecisionOutcome.IncomingUnresolved -> failed(inboxItem, UNRESOLVED_INCOMING_REASON)

            // 위와 같다 — 이 사건의 관찰이 원장과 어긋난다. 원장·이벤트를 쓰지 않았으므로 격리만 남긴다(03 V32).
            is DfnsChainDecisionOutcome.ObservationConflict -> quarantineNow(inboxItem, outcome.safeReason)

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

            // 아직 판단하지 않는 계열 — 미지원·미등록 자산이거나 이벤트를 만들 수 없는 관찰(정밀도 없음·발신 주소 없음)이다.
            // 발신은 위 분기에서 이미 갈렸다. 원장·이벤트를 만들지 않고 처리 완료로 남기며 경보 포트 연결은 후속이다(계약13).
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

        const val UNKNOWN_TRANSFER_REASON = "transfer notification has no submission ledger entry"

        const val PENDING_OUTGOING_REASON = "outgoing transaction is not recorded yet"

        const val UNRESOLVED_INCOMING_REASON = "incoming transfer from our own wallet is not linked yet"

        const val UNEXPECTED_FAILURE_REASON = "decision processing failed"
    }
}
