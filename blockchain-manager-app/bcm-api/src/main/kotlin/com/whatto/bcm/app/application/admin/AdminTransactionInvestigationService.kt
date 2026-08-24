package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.TransactionInvestigation
import com.whatto.bcm.domain.admin.TransactionInvestigationRepository
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import org.springframework.stereotype.Service

@Service
class AdminTransactionInvestigationService(
    private val investigations: TransactionInvestigationRepository,
) {
    fun investigate(identifier: String): TransactionInvestigation =
        investigations.findByIdentifier(identifier)
            ?: throw ResourceNotFoundException("transactionInvestigation", identifier)
}
