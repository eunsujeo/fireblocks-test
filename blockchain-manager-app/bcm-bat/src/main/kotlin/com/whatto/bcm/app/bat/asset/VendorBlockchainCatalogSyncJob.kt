package com.whatto.bcm.app.bat.asset

import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.domain.vendor.VendorBlockchain
import com.whatto.bcm.domain.vendor.VendorBlockchainCatalogAlertPort
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock

/** 매일 벤더 블록체인 목록을 로컬 카탈로그에 동기화한다. */
@Component
class VendorBlockchainCatalogSyncJob(
    private val service: VendorBlockchainCatalogSyncService,
) {
    @Scheduled(cron = "\${bcm.catalog-sync.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    fun run() {
        service.sync()
    }
}

@Service
class VendorBlockchainCatalogSyncService(
    private val vendorCatalog: VendorAssetCatalogPort,
    private val repository: VendorBlockchainCatalogRepository,
    private val alertPort: VendorBlockchainCatalogAlertPort,
    private val clock: Clock,
) {
    fun sync() {
        val syncedAt = CoreDateTimes.now(clock)
        allVendorBlockchains().forEach { vendor ->
            val snapshot = vendor.toCatalog(syncedAt)
            val stored = repository.findByCandidateId(snapshot.candidateId)
            when {
                stored == null -> repository.insert(snapshot)
                stored.chainId != null && stored.chainId != snapshot.chainId ->
                    alertPort.chainIdChanged(snapshot.candidateId, stored.chainId, snapshot.chainId)
                else -> repository.updateSnapshot(snapshot.copy(network = stored.network))
            }
        }
        // 이번 응답에서 사라진 기존 행은 의도적으로 조회·삭제하지 않는다.
    }

    private fun allVendorBlockchains(): List<VendorBlockchain> {
        val blockchains = mutableListOf<VendorBlockchain>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = vendorCatalog.blockchains(cursor)
            blockchains += page.data
            cursor = page.next
            if (cursor != null && !seenCursors.add(cursor)) {
                throw VendorApiException("listBlockchainsPagination", null)
            }
        } while (cursor != null)
        return blockchains
    }

    private fun VendorBlockchain.toCatalog(syncedAt: String) =
        VendorBlockchainCatalog(
            candidateId = id,
            network = null,
            chainId = onchain?.chainId?.toLongOrNull(),
            displayName = displayName,
            testnet = onchain?.test ?: false,
            deprecated = deprecated,
            syncedAt = syncedAt,
        )
}

@Component
class OperationalVendorBlockchainCatalogAlertAdapter(
    private val channel: OperationalAlertChannel,
) : VendorBlockchainCatalogAlertPort {
    override fun chainIdChanged(
        candidateId: String,
        storedChainId: Long?,
        observedChainId: Long?,
    ) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.ASSET,
                type = "asset.alert.chain-id-changed",
                identifiers = mapOf("candidateId" to candidateId),
                context =
                    buildMap {
                        storedChainId?.let { put("storedChainId", it.toString()) }
                        observedChainId?.let { put("observedChainId", it.toString()) }
                    },
            ),
        )
    }
}
