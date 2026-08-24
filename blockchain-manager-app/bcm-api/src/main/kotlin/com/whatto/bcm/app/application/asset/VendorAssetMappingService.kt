package com.whatto.bcm.app.application.asset

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheRepository
import com.whatto.bcm.domain.asset.VendorAssetCatalogSearchResult
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.BcmException
import com.whatto.bcm.domain.exception.BulkAssetMappingException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.exception.VendorAssetMappingRegistrationConflictException
import com.whatto.bcm.domain.vendor.VendorAsset
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock

/** 07-asset-master의 벤더 중립 Admin 조회·채택·set-once 등록·안전 삭제 오케스트레이션. */
@Service
class VendorAssetMappingService(
    private val mappingRepository: VendorAssetMappingRepository,
    private val blockchainRepository: VendorBlockchainCatalogRepository,
    private val depositAddressQueryService: DepositAddressQueryService,
    private val assetCatalogCache: VendorAssetCatalogCacheRepository,
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
        query: String,
        network: String?,
    ): VendorAssetCatalogSearchResult =
        assetCatalogCache.search(
            query = query,
            network = network,
            staleBefore = CoreDateTimes.format(CoreDateTimes.current(clock).minusHours(CATALOG_STALE_HOURS)),
            limit = MAX_CANDIDATES,
        )

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
                    asset.id == command.fireblocksAssetId &&
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

    /** 최대 건수는 API 경계에서 제한하며, 여기서는 모든 후보를 검증한 뒤 한 번에 저장한다. */
    fun registerAll(commands: List<RegisterVendorAssetMappingCommand>): List<VendorAssetMapping> {
        if (commands.isEmpty()) return emptyList()
        val duplicateKeys = commands.groupingBy { it.network to it.symbol }.eachCount().filterValues { it > 1 }
        if (duplicateKeys.isNotEmpty()) {
            val (network, symbol) = duplicateKeys.keys.first()
            val index = commands.indexOfFirst { it.network == network && it.symbol == symbol }
            throw bulkFailure(index, commands[index], "duplicateAssetMapping", ConflictException("assetMapping", "$network:$symbol"))
        }
        val duplicateVendorAssets = commands.groupingBy { it.fireblocksAssetId }.eachCount().filterValues { it > 1 }
        if (duplicateVendorAssets.isNotEmpty()) {
            val vendorAssetId = duplicateVendorAssets.keys.first()
            val index = commands.indexOfFirst { it.fireblocksAssetId == vendorAssetId }
            throw bulkFailure(index, commands[index], "duplicateVendorAsset", ConflictException("vendorAsset", vendorAssetId))
        }
        commands.forEachIndexed { index, command ->
            mappingRepository.find(command.network, command.symbol)?.let {
                throw bulkFailure(
                    index,
                    command,
                    "assetMappingAlreadyExists",
                    ConflictException("assetMapping", "${command.network}:${command.symbol}"),
                )
            }
        }

        val assetsByNetwork =
            commands
                .map { it.network }
                .distinct()
                .associateWith { network ->
                    val index = commands.indexOfFirst { it.network == network }
                    val command = commands[index]
                    try {
                        val blockchain = adoptedBlockchain(network)
                        blockchain to allVendorAssets(blockchain.candidateId, symbol = null)
                    } catch (exception: BcmException) {
                        throw bulkFailure(index, command, failureReason(exception), exception)
                    }
                }
        val registeredAt = CoreDateTimes.now(clock)
        val mappings =
            commands.mapIndexed { index, command ->
                try {
                    val (blockchain, assets) = checkNotNull(assetsByNetwork[command.network])
                    val matches =
                        assets.filter { asset ->
                            asset.blockchainId == blockchain.candidateId &&
                                asset.id == command.fireblocksAssetId &&
                                when (command.contractAddress) {
                                    null -> asset.assetClass == NATIVE_ASSET_CLASS
                                    else -> asset.contractAddress?.equals(command.contractAddress, ignoreCase = true) == true
                                }
                        }
                    if (matches.isEmpty()) throw InvalidAssetMappingException(command.network, "assetNotFound")
                    if (matches.size > 1) throw ConflictException("assetCandidate", "${command.network}:ambiguous")
                    val vendorAsset = matches.single()
                    VendorAssetMapping(
                        network = command.network,
                        symbol = command.symbol,
                        vendorAssetId = vendorAsset.id,
                        contractAddress = command.contractAddress?.let { vendorAsset.contractAddress },
                        registeredAt = registeredAt,
                        registeredByEmployeeNo = command.employeeNo,
                        registeredByBranchCode = command.branchCode,
                    )
                } catch (exception: BcmException) {
                    throw bulkFailure(index, command, failureReason(exception), exception)
                }
            }
        return try {
            mappingRepository.saveAll(mappings, commands.first().requestId)
        } catch (exception: VendorAssetMappingRegistrationConflictException) {
            val index = commands.indexOfFirst { it.network == exception.network && it.symbol == exception.symbol }
            check(index >= 0) {
                "asset mapping conflict does not belong to request: network=${exception.network} symbol=${exception.symbol}"
            }
            throw bulkFailure(index, commands[index], "concurrentConflict", exception)
        }
    }

    private fun bulkFailure(
        index: Int,
        command: RegisterVendorAssetMappingCommand,
        reason: String,
        failure: BcmException,
    ) = BulkAssetMappingException(index, command.network, command.symbol, reason, failure)

    private fun failureReason(exception: BcmException): String =
        when (exception) {
            is InvalidAssetMappingException -> exception.reason
            is ConflictException -> exception.resource
            is VendorAssetMappingRegistrationConflictException -> "concurrentConflict"
            else -> "vendorValidationFailed"
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
        private const val CATALOG_STALE_HOURS = 48L
        private const val MAX_CANDIDATES = 50
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
    val fireblocksAssetId: String,
    val contractAddress: String?,
    val employeeNo: String,
    val branchCode: String,
    val requestId: String = "UNSPECIFIED",
)
