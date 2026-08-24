package com.whatto.bcm.domain.monitoring

fun interface OperationalAlertChannel {
    /** 운영 채널 장애는 호출한 자금·웹훅 처리를 실패시키지 않는다. 구현체가 실패 신호를 별도로 남긴다. */
    fun publish(alert: OperationalAlert)
}

data class OperationalAlert(
    val route: OperationalAlertRoute,
    val type: String,
    val identifiers: Map<String, String>,
    val context: Map<String, String> = emptyMap(),
) {
    init {
        require(TYPE.matches(type)) { "operational alert type must be a lower-case dotted name" }
        require(identifiers.isNotEmpty()) { "operational alert requires at least one identifier" }
        require(identifiers.size + context.size <= MAX_FIELDS) { "operational alert has too many fields" }
        (identifiers + context).forEach { (key, value) ->
            require(FIELD_NAME.matches(key)) { "invalid operational alert field name" }
            require(value.isNotBlank() && value.length <= MAX_FIELD_VALUE_LENGTH) {
                "operational alert field value must be non-blank and bounded"
            }
        }
    }

    private companion object {
        const val MAX_FIELDS = 20
        const val MAX_FIELD_VALUE_LENGTH = 256
        val TYPE = Regex("^[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+$")
        val FIELD_NAME = Regex("^[a-z][A-Za-z0-9]*$")
    }
}

enum class OperationalAlertRoute(
    val value: String,
) {
    WEBHOOK("webhook"),
    EVENT_DELIVERY("event-delivery"),
    TRANSACTION("transaction"),
    SWEEP("sweep"),
    RECONCILIATION("reconciliation"),
    ASSET("asset"),
}
