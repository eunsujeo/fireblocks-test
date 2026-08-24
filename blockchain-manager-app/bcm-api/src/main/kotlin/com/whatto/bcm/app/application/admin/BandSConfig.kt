package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.BandSExecutionBoundary
import com.whatto.bcm.domain.admin.BandSNetworkAsset
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("bcm.admin-band-s")
data class BandSBoundaryProperties(
    val omnibusVaults: Map<String, String> = emptyMap(),
    val withdrawalPoolVaults: Map<String, Set<String>> = emptyMap(),
    val fixedColdAddresses: Map<String, String> = emptyMap(),
    val treasuryEgressVaults: Map<String, String> = emptyMap(),
)

@Configuration
@EnableConfigurationProperties(BandSBoundaryProperties::class)
class BandSConfig {
    @Bean
    fun bandSExecutionBoundary(properties: BandSBoundaryProperties): BandSExecutionBoundary {
        require(properties.treasuryEgressVaults.isEmpty()) {
            "bcm.admin-band-s.treasury-egress-vaults was removed; configure omnibus-vaults instead"
        }
        val fixedColdAddresses =
            properties.fixedColdAddresses.mapKeys { (key, _) ->
                val parts = key.split(":", limit = 2)
                require(parts.size == 2) { "fixed cold address key must be NETWORK:TOKEN" }
                BandSNetworkAsset(parts[0], parts[1])
            }
        validateRegistry(properties, fixedColdAddresses)
        return BandSExecutionBoundary(
            properties.omnibusVaults,
            properties.withdrawalPoolVaults,
            fixedColdAddresses,
        )
    }

    private fun validateRegistry(
        properties: BandSBoundaryProperties,
        fixedColdAddresses: Map<BandSNetworkAsset, String>,
    ) {
        val configured =
            properties.omnibusVaults.isNotEmpty() ||
                properties.withdrawalPoolVaults.isNotEmpty() ||
                fixedColdAddresses.isNotEmpty()
        if (!configured) return

        require(properties.omnibusVaults.isNotEmpty()) { "omnibus-vaults must not be empty" }
        require(properties.withdrawalPoolVaults.isNotEmpty()) { "withdrawal-pool-vaults must not be empty" }
        require(fixedColdAddresses.isNotEmpty()) { "fixed-cold-addresses must not be empty" }
        require(properties.omnibusVaults.keys == properties.withdrawalPoolVaults.keys) {
            "omnibus-vaults and withdrawal-pool-vaults must cover the same networks"
        }
        require(fixedColdAddresses.keys.map(BandSNetworkAsset::network).toSet() == properties.omnibusVaults.keys) {
            "fixed-cold-addresses must cover every configured network"
        }
        properties.omnibusVaults.forEach { (network, omnibus) ->
            require(network.isNotBlank() && omnibus.isNotBlank()) { "omnibus-vaults contains a blank network or vault" }
            val pools = properties.withdrawalPoolVaults.getValue(network)
            require(pools.isNotEmpty() && pools.none(String::isBlank)) { "withdrawal-pool-vaults contains an empty pool" }
            require(omnibus !in pools) { "omnibus vault must not also be a withdrawal pool" }
        }
        require(
            fixedColdAddresses.all { (asset, address) ->
                asset.network.isNotBlank() && asset.tokenSymbol.isNotBlank() && address.isNotBlank()
            },
        ) { "fixed-cold-addresses contains a blank network, token, or address" }
    }
}
