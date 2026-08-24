package com.whatto.bcm.app.bat.sweep

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

@ConfigurationProperties("bcm.sweep")
data class SweepProperties(
    val enabled: Boolean = false,
    val fixedDelayMillis: Long = 60_000,
    val omnibusAccountId: String = "",
    val operatorAccountId: String = "",
    /** 한 주기에 실제 제출할 최대 대상 수(M). */
    val batchSize: Int = 100,
    /** 한 주기에 벤더 잔액을 조회할 최대 대상 수. */
    val scanLimit: Int = 1_000,
    val reconciliationBatchSize: Int = 50,
    val claimTtlSeconds: Long = 120,
    val thresholds: List<SweepAssetThreshold> = emptyList(),
    val contracts: List<SweepNetworkContract> = emptyList(),
    val security: SweepSecurityProperties = SweepSecurityProperties(),
) {
    private val minimumAmounts: Map<Pair<String, String>, BigDecimal>
    private val allowanceCaps: Map<Pair<String, String>, BigDecimal>
    private val contractAddresses: Map<String, String>

    init {
        require(fixedDelayMillis > 0) { "fixedDelayMillis must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(scanLimit >= batchSize) { "scanLimit must be greater than or equal to batchSize" }
        require(reconciliationBatchSize > 0) { "reconciliationBatchSize must be positive" }
        require(claimTtlSeconds > 0) { "claimTtlSeconds must be positive" }
        val entries = thresholds.map { (it.network to it.symbol) to it.minimumAmount() }
        require(entries.map { it.first }.distinct().size == entries.size) {
            "sweep thresholds must not contain duplicate network and symbol"
        }
        minimumAmounts = entries.toMap()
        allowanceCaps =
            thresholds
                .filter { it.allowanceCap.isNotBlank() }
                .associate { (it.network to it.symbol) to it.allowanceCap() }
        require(contracts.map { it.network }.distinct().size == contracts.size) {
            "sweep contracts must not contain duplicate network"
        }
        contractAddresses = contracts.associate { it.network to it.address }
    }

    fun minimumAmount(
        network: String,
        symbol: String,
    ): BigDecimal? = minimumAmounts[network to symbol]

    fun requiredAllowanceCap(
        network: String,
        symbol: String,
    ): String =
        checkNotNull(allowanceCaps[network to symbol]) {
            "sweep allowance cap is not configured: network=$network symbol=$symbol"
        }.stripTrailingZeros().toPlainString()

    fun requiredContractAddress(network: String): String =
        checkNotNull(contractAddresses[network]) { "sweep contract is not configured: network=$network" }

    val claimTtlMillis: Long
        get() = Math.multiplyExact(claimTtlSeconds, 1_000)
}

data class SweepAssetThreshold(
    val network: String = "",
    val symbol: String = "",
    val minimumAmount: String = "",
    val allowanceCap: String = "",
) {
    init {
        require(network.isNotBlank()) { "threshold network must not be blank" }
        require(symbol.isNotBlank()) { "threshold symbol must not be blank" }
    }

    fun minimumAmount(): BigDecimal =
        BigDecimal(minimumAmount).also {
            require(it.signum() > 0) { "threshold minimumAmount must be positive: network=$network symbol=$symbol" }
        }

    fun allowanceCap(): BigDecimal =
        BigDecimal(allowanceCap).also {
            require(it.signum() > 0) { "threshold allowanceCap must be positive: network=$network symbol=$symbol" }
            require(it >= minimumAmount()) {
                "threshold allowanceCap must be greater than or equal to minimumAmount: network=$network symbol=$symbol"
            }
        }
}

data class SweepNetworkContract(
    val network: String = "",
    val address: String = "",
) {
    init {
        require(network.isNotBlank()) { "sweep contract network must not be blank" }
        require(address.isNotBlank()) { "sweep contract address must not be blank" }
    }
}

data class SweepSecurityProperties(
    val normalApprovalEnabled: Boolean = false,
    val emergencyRevocationEnabled: Boolean = false,
    val batchSubmissionEnabled: Boolean = false,
    val tapApprovalPolicyVerified: Boolean = false,
    val tapRevocationPolicyVerified: Boolean = false,
    val tapBatchPolicyVerified: Boolean = false,
    val callbackVerified: Boolean = false,
    val universalGaslessVerified: Boolean = false,
    val sweepContractVerified: Boolean = false,
    val normalApprovalEnabledNetworks: Set<String> = emptySet(),
    val emergencyRevocationEnabledNetworks: Set<String> = emptySet(),
    val batchSubmissionEnabledNetworks: Set<String> = emptySet(),
) {
    init {
        requireNoBlankNetwork(normalApprovalEnabledNetworks, "normal approval")
        requireNoBlankNetwork(emergencyRevocationEnabledNetworks, "emergency revocation")
        requireNoBlankNetwork(batchSubmissionEnabledNetworks, "batch submission")
    }

    fun requireNormalApprovalReady(network: String) {
        check(normalApprovalEnabled) { "normal sweep approval gate is disabled" }
        requireNetworkEnabled(network, normalApprovalEnabledNetworks, "normal sweep approval")
        check(tapApprovalPolicyVerified) { "TAP approval policy is not verified" }
        requireCommonGates()
    }

    fun requireEmergencyRevocationReady(network: String) {
        check(emergencyRevocationEnabled) { "emergency sweep revocation gate is disabled" }
        requireNetworkEnabled(network, emergencyRevocationEnabledNetworks, "emergency sweep revocation")
        check(tapRevocationPolicyVerified) { "TAP revocation policy is not verified" }
        requireCommonGates()
    }

    fun requireBatchSubmissionEnabled() {
        check(batchSubmissionEnabled) { "sweep batch submission gate is disabled" }
    }

    fun requireBatchSubmissionReady(network: String) {
        requireBatchSubmissionEnabled()
        requireNetworkEnabled(network, batchSubmissionEnabledNetworks, "sweep batch submission")
        check(tapBatchPolicyVerified) { "TAP batch policy is not verified" }
        check(sweepContractVerified) { "sweep contract is not verified" }
        requireCommonGates()
    }

    fun isBatchSubmissionNetworkEnabled(network: String): Boolean = network in batchSubmissionEnabledNetworks

    private fun requireCommonGates() {
        check(callbackVerified) { "Co-signer Callback is not verified" }
        check(universalGaslessVerified) { "Universal Gasless is not verified" }
    }

    private fun requireNetworkEnabled(
        network: String,
        enabledNetworks: Set<String>,
        operation: String,
    ) {
        check(network in enabledNetworks) { "$operation is not released for network=$network" }
    }

    private fun requireNoBlankNetwork(
        networks: Set<String>,
        operation: String,
    ) {
        require(networks.none(String::isBlank)) { "$operation enabled networks must not contain blank values" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SweepProperties::class)
class SweepConfig
