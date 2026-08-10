package com.whatto.bcm.app.application.event

import com.whatto.bcm.domain.event.PoisonOutboxAlertPort
import org.springframework.stereotype.Service

@Service
class OutboxRelayProcessor(
    private val transaction: OutboxRelayTransaction,
    private val poisonAlert: PoisonOutboxAlertPort,
) {
    fun relayNext(): OutboxRelayOutcome {
        val outcome = transaction.relayNext()
        if (outcome is OutboxRelayOutcome.Quarantined) {
            poisonAlert.alert(outcome.eventId, outcome.retryCount)
        }
        return outcome
    }
}
