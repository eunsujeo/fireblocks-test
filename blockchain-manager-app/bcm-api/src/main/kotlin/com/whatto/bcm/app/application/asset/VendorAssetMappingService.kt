package com.whatto.bcm.app.application.asset

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.VendorAsset
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.Locale

/** 07-asset-master의 벤더 중립 Admin 조회·채택·set-once 등록·안전 삭제 오케스트레이션. */
@Service
class VendorAssetMappingService(
    private val mappingRepository: VendorAssetMappingRepository,
    private val blockchainRepository: VendorBlockchainCatalogRepository,
    private val depositAddressQueryService: DepositAddressQueryService,
    private val vendorCatalog: VendorAssetCatalogPort,
    private val clock: Clock,
) {
    fun networks(
        query: String?,
        chainId: Long?,
        adopted: Boolean?,
        testnet: Boolean?,
    ): List<VendorBlockchainCatalog> = blockchainRepository.findAll(query, chainId, adopted, testnet)

    fun adoptNetwork(command: AdoptNetworkCommand): VendorBlockchainCatalog {
        val candidate =
            blockchainRepository.findByCandidateId(command.candidateId)
                ?: throw InvalidAssetMappingException(command.network, "candidateNotFound")
        if (candidate.network == command.network) return candidate
        if (candidate.network != null || blockchainRepository.findByNetwork(command.network) != null) {
            throw ConflictException("network", command.network)
        }
        return blockchainRepository.adopt(
            command.candidateId,
            command.network,
            command.employeeNo,
            command.branchCode,
        )
    }

    fun releaseNetwork(
        network: String,
        audit: AuditActor,
    ) {
        blockchainRepository.findByNetwork(network)
            ?: throw ResourceNotFoundException("network", network)
        if (mappingRepository.existsByNetwork(network)) {
            throw ConflictException("networkInUse", network)
        }
        if (!blockchainRepository.release(network, audit.employeeNo, audit.branchCode)) {
            throw ResourceNotFoundException("network", network)
        }
    }

    fun assetCandidates(
        symbol: String,
        network: String?,
    ): List<AssetCandidate> {
        val blockchains =
            network?.let { listOf(adoptedBlockchain(it)) }
                ?: blockchainRepository.findAll(adopted = true)
        return blockchains.flatMap { blockchain ->
            allVendorAssets(blockchain.candidateId, symbol.uppercase(Locale.ROOT))
                .filter { it.blockchainId == blockchain.candidateId }
                .map { AssetCandidate.from(requireNotNull(blockchain.network), it) }
        }
    }

    fun mappings(
        network: String?,
        symbol: String?,
    ): List<VendorAssetMapping> = mappingRepository.findAll(network, symbol)

    fun register(command: RegisterVendorAssetMappingCommand): VendorAssetMapping {
        mappingRepository.find(command.network, command.symbol)?.let {
            throw ConflictException("assetMapping", "${command.network}:${command.symbol}")
        }
        val blockchain = adoptedBlockchain(command.network)
        val matches =
            allVendorAssets(blockchain.candidateId, symbol = null).filter { asset ->
                asset.blockchainId == blockchain.candidateId &&
                    when (command.contractAddress) {
                        null -> asset.assetClass == NATIVE_ASSET_CLASS
                        else -> asset.contractAddress?.equals(command.contractAddress, ignoreCase = true) == true
                    }
            }
        if (matches.isEmpty()) {
            throw InvalidAssetMappingException(command.network, "assetNotFound")
        }
        if (matches.size > 1) {
            throw ConflictException("assetCandidate", "${command.network}:ambiguous")
        }

        val vendorAsset = matches.single()
        val mapping =
            VendorAssetMapping(
                network = command.network,
                symbol = command.symbol,
                vendorAssetId = vendorAsset.id,
                contractAddress = command.contractAddress?.let { vendorAsset.contractAddress },
                registeredAt = CoreDateTimes.now(clock),
                registeredByEmployeeNo = command.employeeNo,
                registeredByBranchCode = command.branchCode,
            )
        return mappingRepository.save(mapping, command.requestId)
    }

    @Suppress("UNUSED_PARAMETER")
    fun delete(
        network: String,
        symbol: String,
        audit: AuditActor,
    ) {
        mappingRepository.find(network, symbol)
            ?: throw ResourceNotFoundException("assetMapping", "$network:$symbol")
        if (depositAddressQueryService.existsByAsset(network, symbol)) {
            throw ConflictException("assetMappingInUse", "$network:$symbol")
        }
        mappingRepository.deactivate(
            network,
            symbol,
            audit.employeeNo,
            audit.branchCode,
            audit.requestId,
            CoreDateTimes.now(clock),
        )
    }

    private fun adoptedBlockchain(network: String): VendorBlockchainCatalog =
        blockchainRepository.findByNetwork(network)
            ?: throw InvalidAssetMappingException(network, "networkNotAdopted")

    private fun allVendorAssets(
        candidateId: String,
        symbol: String?,
    ): List<VendorAsset> {
        val assets = mutableListOf<VendorAsset>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = vendorCatalog.assets(candidateId, symbol, cursor)
            assets += page.data
            cursor = page.next
            if (cursor != null && !seenCursors.add(cursor)) {
                throw VendorApiException("listAssetsPagination", null)
            }
        } while (cursor != null)
        return assets
    }

    companion object {
        private const val NATIVE_ASSET_CLASS = "NATIVE"
    }
}

data class AuditActor(
    val employeeNo: String,
    val branchCode: String,
    val requestId: String = "UNSPECIFIED",
)

data class AdoptNetworkCommand(
    val network: String,
    val candidateId: String,
    val employeeNo: String,
    val branchCode: String,
)

data class RegisterVendorAssetMappingCommand(
    val network: String,
    val symbol: String,
    val contractAddress: String?,
    val employeeNo: String,
    val branchCode: String,
    val requestId: String = "UNSPECIFIED",
)

data class AssetCandidate(
    val network: String,
    val symbol: String,
    val displayName: String?,
    val decimals: Int?,
    val contractAddress: String?,
    val native: Boolean,
) {
    companion object {
        private const val NATIVE_ASSET_CLASS = "NATIVE"

        fun from(
            network: String,
            asset: VendorAsset,
        ): AssetCandidate {
            val native = asset.assetClass == NATIVE_ASSET_CLASS
            return AssetCandidate(
                network = network,
                symbol = asset.displaySymbol,
                displayName = asset.displayName,
                decimals = asset.decimals,
                contractAddress = if (native) null else asset.contractAddress,
                native = native,
            )
        }
    }
}
