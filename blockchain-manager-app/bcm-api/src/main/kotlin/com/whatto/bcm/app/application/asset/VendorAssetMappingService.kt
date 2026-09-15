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
import com.whatto.bcm.domain.exception.VendorAssetMappingRegistrationConflictException
import com.whatto.bcm.domain.vendor.ChainAssetLocator
import com.whatto.bcm.domain.vendor.ChainAssetResolution
import com.whatto.bcm.domain.vendor.ChainAssetResolver
import com.whatto.bcm.domain.vendor.ResolvedChainAsset
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * 07-asset-master의 벤더 중립 Admin 조회·채택·set-once 등록·안전 삭제 오케스트레이션.
 * 등록의 벤더 재해소는 제공자별 `ChainAssetResolver`(Fireblocks 카탈로그 대조 / Dfns 데이터셋 네트워크·자산 모델 대조)가 맡고,
 * 여기서는 채택 네트워크·중복·한 자산 한 매핑 관문과 현재 행+변경 snapshot 저장만 다룬다.
 */
@Service
class VendorAssetMappingService(
    private val mappingRepository: VendorAssetMappingRepository,
    private val blockchainRepository: VendorBlockchainCatalogRepository,
    private val depositAddressQueryService: DepositAddressQueryService,
    private val assetCatalogCache: VendorAssetCatalogCacheRepository,
    private val resolver: ChainAssetResolver,
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
        val resolved =
            when (val resolution = resolver.resolveAll(blockchain, listOf(command.locator())).single()) {
                is ChainAssetResolution.Resolved -> resolution.asset
                is ChainAssetResolution.Rejected -> throw resolution.failure
            }
        return mappingRepository.save(mapping(command, resolved, CoreDateTimes.now(clock)), command.requestId)
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
        val duplicateVendorAssets =
            commands
                .mapNotNull { it.fireblocksAssetId }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
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

        // 네트워크마다 한 번 해소한다 — 네트워크 전체 실패(채택 안 함·벤더 조회 실패)는 그 네트워크의 첫 항목에 표시한다.
        val resolutions = mutableMapOf<Int, ChainAssetResolution>()
        commands.map { it.network }.distinct().forEach { network ->
            val indexes = commands.indices.filter { commands[it].network == network }
            val first = indexes.first()
            val resolved =
                try {
                    resolver.resolveAll(adoptedBlockchain(network), indexes.map { commands[it].locator() })
                } catch (exception: BcmException) {
                    throw bulkFailure(first, commands[first], failureReason(exception), exception)
                }
            check(resolved.size == indexes.size) { "asset resolver returned ${resolved.size} results for ${indexes.size} locators" }
            indexes.forEachIndexed { position, index -> resolutions[index] = resolved[position] }
        }
        val registeredAt = CoreDateTimes.now(clock)
        val mappings =
            commands.mapIndexed { index, command ->
                when (val resolution = checkNotNull(resolutions[index])) {
                    is ChainAssetResolution.Resolved -> mapping(command, resolution.asset, registeredAt)
                    is ChainAssetResolution.Rejected ->
                        throw bulkFailure(index, command, failureReason(resolution.failure), resolution.failure)
                }
            }
        // 해소된 벤더 자산이 요청 안에서 겹치면(예: 같은 컨트랙트를 두 심볼로) 한 자산 한 매핑 관문에서 미리 거절한다.
        mappings.groupingBy { it.vendorAssetId }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let { vendorAssetId ->
            val index = mappings.indexOfFirst { it.vendorAssetId == vendorAssetId }
            throw bulkFailure(index, commands[index], "duplicateVendorAsset", ConflictException("vendorAsset", vendorAssetId))
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

    private fun mapping(
        command: RegisterVendorAssetMappingCommand,
        resolved: ResolvedChainAsset,
        registeredAt: String,
    ) = VendorAssetMapping(
        network = command.network,
        symbol = command.symbol,
        vendorAssetId = resolved.vendorAssetId,
        contractAddress = resolved.contractAddress,
        registeredAt = registeredAt,
        registeredByEmployeeNo = command.employeeNo,
        registeredByBranchCode = command.branchCode,
    )

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

    companion object {
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

/** 등록 명령 — `fireblocksAssetId`는 Fireblocks 원천의 후보 assetId(필수)이고 Dfns 원천에서는 없어야 한다(관문이 거절). */
data class RegisterVendorAssetMappingCommand(
    val network: String,
    val symbol: String,
    val fireblocksAssetId: String?,
    val contractAddress: String?,
    val employeeNo: String,
    val branchCode: String,
    val requestId: String = "UNSPECIFIED",
) {
    fun locator() = ChainAssetLocator(network, fireblocksAssetId, contractAddress)
}
