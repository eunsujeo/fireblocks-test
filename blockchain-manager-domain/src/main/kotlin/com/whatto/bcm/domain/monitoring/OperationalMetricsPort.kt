package com.whatto.bcm.domain.monitoring

enum class WebhookIngestionMetricOutcome(
    val tagValue: String,
) {
    ACCEPTED("accepted"),
    INVALID_SIGNATURE("invalid_signature"),
    ERROR("error"),
}

enum class VendorCallMetricOutcome(
    val tagValue: String,
) {
    SUCCESS("success"),
    RATE_LIMITED("rate_limited"),
    ERROR("error"),
}

interface OperationalMetricsPort {
    fun recordWebhookIngestion(
        outcome: WebhookIngestionMetricOutcome,
        receivedAt: String?,
    )

    fun recordVendorCall(
        operation: String,
        outcome: VendorCallMetricOutcome,
    )

    fun recordReconciliation(recoveredCount: Int)
}

object NoOpOperationalMetricsPort : OperationalMetricsPort {
    override fun recordWebhookIngestion(
        outcome: WebhookIngestionMetricOutcome,
        receivedAt: String?,
    ) = Unit

    override fun recordVendorCall(
        operation: String,
        outcome: VendorCallMetricOutcome,
    ) = Unit

    override fun recordReconciliation(recoveredCount: Int) = Unit
}
