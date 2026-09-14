package com.whatto.bcm.domain.provider

/** 데이터셋 전체의 불변 실행 원천. URL이나 자격증명은 식별자로 사용하지 않는다. */
data class ProviderOrigin(
    val originId: String,
    val executionMode: String,
    val protocolProvider: String,
    val platformInstanceId: String,
    val vendorOrganizationId: String,
    val chainMode: String,
) {
    init {
        mapOf("originId" to originId, "platformInstanceId" to platformInstanceId, "vendorOrganizationId" to vendorOrganizationId)
            .forEach { (field, value) ->
                require(value.length in 1..64 && value.isNotBlank() && value == value.trim()) { "Invalid provider origin field: $field" }
            }
        require(
            when (executionMode) {
                "fireblocks", "dfns" -> protocolProvider == executionMode && chainMode in setOf("TESTNET", "MAINNET")
                "local" -> protocolProvider == "fireblocks" && chainMode == "LOCAL"
                else -> false
            },
        ) { "Invalid provider origin execution/protocol/chain combination" }
    }

    fun requireMatch(stored: ProviderOrigin?) {
        check(stored != null) { "Provider origin binding is missing; automatic registration is not allowed" }
        check(this == stored) { "Provider origin mismatch; verify the selected dataset and origin configuration" }
    }
}
