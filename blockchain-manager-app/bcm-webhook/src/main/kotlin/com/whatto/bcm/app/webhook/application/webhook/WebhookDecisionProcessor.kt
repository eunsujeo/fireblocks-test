package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.domain.webhook.PoisonWebhookAlertPort
import com.whatto.bcm.domain.webhook.UnattributedDepositAlertPort
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlertPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class WebhookDecisionProcessor(
    private val transaction: WebhookDecisionWork,
    private val unattributedAlertPort: UnattributedDepositAlertPort,
    private val unregisteredVaultTransferAlertPort: UnregisteredVaultTransferAlertPort,
    private val poisonAlertPort: PoisonWebhookAlertPort,
) {
    fun processNext(): WebhookDecisionOutcome {
        val outcome =
            try {
                processWithConflictRetry()
            } catch (exception: WebhookDecisionProcessingException) {
                logger.error(
                    "Webhook decision processing failed: notificationId={}",
                    exception.notificationId,
                    exception,
                )
                transaction.recordUnexpectedFailure(exception.notificationId)
            }
        when (outcome) {
            is WebhookDecisionOutcome.Unattributed -> unattributedAlertPort.alert(outcome.alert)
            is WebhookDecisionOutcome.UnregisteredVaultTransfer ->
                unregisteredVaultTransferAlertPort.alert(outcome.alert)
            is WebhookDecisionOutcome.Quarantined -> poisonAlertPort.alert(outcome.notificationId, outcome.retryCount)
            else -> Unit
        }
        return outcome
    }

    private fun processWithConflictRetry(): WebhookDecisionOutcome =
        try {
            transaction.processNext()
        } catch (_: WebhookDecisionConflictException) {
            // 신규 tx insert 경합 트랜잭션은 롤백됐다. 새 트랜잭션에서 이긴 행을 잠가 다시 판정한다.
            try {
                transaction.processNext()
            } catch (exception: WebhookDecisionConflictException) {
                throw WebhookDecisionProcessingException(exception.notificationId, exception)
            }
        }

    private companion object {
        val logger = LoggerFactory.getLogger(WebhookDecisionProcessor::class.java)
    }
}
