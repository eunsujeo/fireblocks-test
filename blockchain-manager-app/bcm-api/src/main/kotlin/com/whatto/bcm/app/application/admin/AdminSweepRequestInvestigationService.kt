package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.SweepOperationsOverview
import com.whatto.bcm.domain.admin.SweepRequestInvestigation
import com.whatto.bcm.domain.admin.SweepRequestInvestigationRepository
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import org.springframework.stereotype.Service

@Service
class AdminSweepRequestInvestigationService(
    private val investigations: SweepRequestInvestigationRepository,
) {
    fun investigate(identifier: String): SweepRequestInvestigation =
        investigations.findByIdentifier(identifier)
            ?: throw ResourceNotFoundException("sweepRequestInvestigation", identifier)

    fun operationsOverview(): SweepOperationsOverview = investigations.operationsOverview()
}
