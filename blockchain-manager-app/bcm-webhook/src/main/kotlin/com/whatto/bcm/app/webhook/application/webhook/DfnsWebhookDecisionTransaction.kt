package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.tx.FinalityPolicyConfigurationException
import com.whatto.bcm.domain.vendor.NetworkChainTransferStatus
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
 * Dfns 인박스 판단 — 한 건을 잠가 [DfnsChainEventDecision]에 넘기고 결과를 인박스 상태로 옮긴다(계약13).
 *
 * **입금만 판단한다.** 전송 알림(`wallet.transfer.*`)·발신 이동은 제출 원장 대조가 필요한데 그 경로가 아직 없어 처리 완료로 표시만 한다 —
 * 그래서 `BCM_PROVIDER=dfns`의 기동 차단 해제 조건에 "출금·발신 판단 구현"이 함께 걸려 있다(계약13). 이 제약을 모르고 출금을 열면 안 된다.
 *
 * 실패 처리는 Fireblocks 경로와 같다 — payload 결함은 재시도/격리, 설정 오류는 P로 남겨 복구 뒤 다시 처리, 그 밖의 오류는 워커가 기록한다.
 */
@Service
@ConditionalOnDfnsProtocol
class DfnsWebhookDecisionTransaction(
    private val inboxRepository: WebhookInboxRepository,
    private val transactionRunner: TransactionRunner,
    private val decision: DfnsChainEventDecision,
    private val clock: Clock,
    @param:Value("\${bcm.webhook-worker.max-attempts:3}") private val maxAttempts: Int,
) : WebhookDecisionWork {
    override fun processNext(): WebhookDecisionOutcome = transactionRunner.run { processNextInTransaction() }

    override fun recordUnexpectedFailure(notificationId: String): WebhookDecisionOutcome =
        transactionRunner.run {
            inboxRepository
                .recordFailure(notificationId, UNEXPECTED_FAILURE_REASON, maxAttempts)
                .toOutcome(notificationId)
        }

    private fun processNextInTransaction(): WebhookDecisionOutcome {
        val inboxItem = inboxRepository.findNextPendingForUpdate() ?: return WebhookDecisionOutcome.NoWork
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

    private fun process(inboxItem: WebhookInboxItem): WebhookDecisionOutcome =
        when (val outcome = decision.decide(inboxItem.notificationId, inboxItem.payload.toByteArray())) {
            is DfnsChainDecisionOutcome.Processed -> {
                markProcessed(inboxItem, outcome.observation.status)
                WebhookDecisionOutcome.Processed(inboxItem.notificationId, outcome.events.size)
            }

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

    private fun failed(
        inboxItem: WebhookInboxItem,
        safeReason: String,
    ): WebhookDecisionOutcome =
        inboxRepository.recordFailure(inboxItem.notificationId, safeReason, maxAttempts).toOutcome(inboxItem.notificationId)

    private fun WebhookFailureResult.toOutcome(notificationId: String): WebhookDecisionOutcome =
        if (quarantined) {
            WebhookDecisionOutcome.Quarantined(notificationId, retryCount)
        } else {
            WebhookDecisionOutcome.Retrying(notificationId, retryCount)
        }

    /** 벤더 종결 표식은 이동 상태 `Confirmed`다 — BCM 확정(블록 깊이)과는 별개의 보관·조회용 표식이다(03). */
    private fun markProcessed(
        inboxItem: WebhookInboxItem,
        status: NetworkChainTransferStatus? = null,
    ) {
        inboxRepository.markProcessed(
            inboxItem.notificationId,
            CoreDateTimes.now(clock),
            vendorCompleted = status == NetworkChainTransferStatus.CONFIRMED,
        )
    }

    private companion object {
        const val UNEXPECTED_FAILURE_REASON = "decision processing failed"
    }
}
