package com.whatto.bcm.domain.sweep

fun interface SweepExecutionAlertPort {
    fun alert(alert: SweepExecutionAlert)
}

data class SweepExecutionAlert(
    val stage: SweepExecutionStage,
    val target: SweepTargetKey,
    val cause: RuntimeException,
)

enum class SweepExecutionStage {
    SELECTION,
    SUBMISSION,
    RECONCILIATION,
}
