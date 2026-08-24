package com.whatto.bcm.admin.application

import com.whatto.bcm.admin.client.AdminAssetMapping
import com.whatto.bcm.admin.client.AdminBandS
import com.whatto.bcm.admin.client.AdminChangeRequest
import com.whatto.bcm.admin.client.AdminContract
import com.whatto.bcm.admin.client.AdminExecutionGateOverview
import com.whatto.bcm.admin.client.AdminExternalControlEvidence
import com.whatto.bcm.admin.client.AdminNetwork
import com.whatto.bcm.admin.client.AdminPolicy
import com.whatto.bcm.admin.client.AdminTransactionInvestigation
import com.whatto.bcm.admin.client.BcmAdminReadGateway
import com.whatto.bcm.admin.client.SourceFailure
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class ViewState {
    FRESH,
    STALE,
    PARTIAL,
}

data class SourceIssue(
    val source: String,
    val code: String,
    val message: String,
)

data class ViewResult<T>(
    val data: T,
    val state: ViewState,
    val issues: List<SourceIssue>,
)

data class AdminOverview(
    val generatedAt: String,
    val state: ViewState,
    val networkCount: Int?,
    val testnetCount: Int?,
    val assetMappingCount: Int?,
    val issues: List<SourceIssue>,
)

data class NetworkFilters(
    val q: String? = null,
    val chainId: Long? = null,
    val adopted: Boolean? = null,
    val testnet: Boolean? = null,
)

data class AssetFilters(
    val network: String? = null,
    val symbol: String? = null,
)

enum class SearchKind {
    TRANSACTION,
    NETWORK,
    ASSET,
}

data class AdminAction(
    val href: String,
    val disabledReason: String? = null,
)

data class SearchResult(
    val kind: SearchKind,
    val primary: String,
    val secondary: String,
    val action: AdminAction,
)

@Service
class AdminReadService(
    private val gateway: BcmAdminReadGateway,
    private val clock: Clock,
    @param:Value("\${bcm.admin.stale-after-seconds:90000}") private val staleAfterSeconds: Long,
) {
    fun overview(): AdminOverview {
        val issues = mutableListOf<SourceIssue>()
        val networks = capture("networks", issues) { gateway.networks(null, null, true, null) }
        val mappings = capture("assets", issues) { gateway.assetMappings(null, null) }
        if (networks == null && mappings == null) {
            throw SourceFailure("overview", 502, "all BCM Admin sources unavailable")
        }
        val state =
            when {
                issues.isNotEmpty() -> ViewState.PARTIAL
                networks.orEmpty().any(::isStale) -> ViewState.STALE
                else -> ViewState.FRESH
            }
        return AdminOverview(
            generatedAt = Instant.now(clock).toString(),
            state = state,
            networkCount = networks?.size,
            testnetCount = networks?.count(AdminNetwork::testnet),
            assetMappingCount = mappings?.size,
            issues = issues,
        )
    }

    fun networks(filters: NetworkFilters): ViewResult<List<AdminNetwork>> {
        val data = gateway.networks(filters.q, filters.chainId, filters.adopted, filters.testnet)
        return ViewResult(data, if (data.any(::isStale)) ViewState.STALE else ViewState.FRESH, emptyList())
    }

    fun assets(filters: AssetFilters): ViewResult<List<AdminAssetMapping>> =
        ViewResult(gateway.assetMappings(filters.network, filters.symbol), ViewState.FRESH, emptyList())

    fun transaction(identifier: String): ViewResult<AdminTransactionInvestigation> {
        val investigation = gateway.transactionInvestigation(identifier)
        val issues = truncationIssues(investigation)
        val state =
            when {
                issues.isNotEmpty() -> ViewState.PARTIAL
                isStale(investigation) -> ViewState.STALE
                else -> ViewState.FRESH
            }
        return ViewResult(investigation, state, issues)
    }

    fun contracts(): ViewResult<List<AdminContract>> {
        val data = gateway.contracts()
        val issues =
            data
                .filter { it.evidenceStatus in setOf("INVALID", "ERROR") }
                .map { SourceIssue(it.scopeId, "CONTRACT_DRIFT", "컨트랙트 검증 증적을 다시 확인해야 합니다.") }
        val stale = data.any(::evidenceExpired)
        return ViewResult(
            data,
            when {
                issues.isNotEmpty() -> ViewState.PARTIAL
                stale -> ViewState.STALE
                else -> ViewState.FRESH
            },
            issues,
        )
    }

    fun policies(): ViewResult<List<AdminPolicy>> = ViewResult(gateway.policies(), ViewState.FRESH, emptyList())

    fun bandS(): ViewResult<List<AdminBandS>> {
        val data = gateway.bandS()
        val issues =
            data
                .filter { it.state in setOf("BLOCKED", "PARTIAL", "FAILED") }
                .map { SourceIssue(it.proposalId, "BAND_S_${it.state}", "밴드S 제안 또는 실행 상태를 확인해야 합니다.") }
        return ViewResult(
            data,
            when {
                issues.isNotEmpty() -> ViewState.PARTIAL
                data.any { it.state == "STALE" } -> ViewState.STALE
                else -> ViewState.FRESH
            },
            issues,
        )
    }

    fun emergency(): ViewResult<AdminExecutionGateOverview> {
        val received = gateway.executionGates()
        val data =
            received.copy(
                externalControls = received.externalControls.map { it.asOf(Instant.now(clock)) },
            )
        val issues =
            buildList {
                if (data.truncated) {
                    add(
                        SourceIssue(
                            "executionGates",
                            "EXECUTION_GATE_RESULT_TRUNCATED",
                            "실행 게이트 일부만 반환되었습니다.",
                        ),
                    )
                }
                data.externalControls.filterNot { it.completionReady }.forEach { evidence ->
                    add(
                        SourceIssue(
                            "externalControls",
                            "EXTERNAL_CONTROL_${evidence.status}",
                            "${evidence.network} 외부 통제 상태를 완료로 확인하지 못했습니다.",
                        ),
                    )
                }
                data.allowanceRevocations.filterNot { it.status == "COMPLETED" }.forEach { revocation ->
                    add(
                        SourceIssue(
                            "allowanceRevocations",
                            "ALLOWANCE_REVOCATION_${revocation.status}",
                            "${revocation.network} allowance 회수가 아직 완료되지 않았습니다.",
                        ),
                    )
                }
                data.webhookRecoveries.filterNot { it.state == "COMPLETED" }.forEach { recovery ->
                    add(
                        SourceIssue(
                            "webhookRecoveries",
                            "WEBHOOK_RECOVERY_${recovery.state}",
                            "웹훅 복구 ${recovery.requestId} 상태를 확인해야 합니다.",
                        ),
                    )
                }
                data.resumes.filterNot { it.state == "RESUMED" }.forEach { resume ->
                    add(
                        SourceIssue(
                            "executionGateResumes",
                            "EXECUTION_GATE_RESUME_${resume.state}",
                            "${resume.network} ${resume.type} 재개 요청 상태를 확인해야 합니다.",
                        ),
                    )
                }
            }
        val state =
            when {
                data.truncated -> ViewState.PARTIAL
                data.externalControls.any { it.status == "STALE" } &&
                    data.externalControls.none { it.status in setOf("DRIFT", "UNCONFIRMED", "ERROR") } -> ViewState.STALE
                issues.isNotEmpty() -> ViewState.PARTIAL
                else -> ViewState.FRESH
            }
        return ViewResult(data, state, issues)
    }

    private fun AdminExternalControlEvidence.asOf(now: Instant): AdminExternalControlEvidence =
        if (now.isBefore(Instant.parse(validUntil))) {
            this
        } else {
            copy(
                status = "STALE",
                completionReady = false,
                issues = (issues + "EVIDENCE_EXPIRED").distinct(),
            )
        }

    fun changeRequest(requestId: String): ViewResult<AdminChangeRequest> =
        ViewResult(gateway.changeRequest(requestId), ViewState.FRESH, emptyList())

    fun search(rawQuery: String): ViewResult<List<SearchResult>> {
        val query = rawQuery.trim()
        val issues = mutableListOf<SourceIssue>()
        val transaction = optionalTransaction(query, issues)
        val networks = capture("networks", issues) { gateway.networks(query, null, null, null) }
        val mappings = capture("assets", issues) { gateway.assetMappings(null, null) }
        if (networks == null && mappings == null) {
            throw SourceFailure("search", 502, "all BCM Admin search sources unavailable")
        }
        val normalized = query.uppercase()
        val results =
            buildList {
                transaction?.let { investigation ->
                    add(
                        SearchResult(
                            SearchKind.TRANSACTION,
                            investigation.summary.rootTransactionId,
                            "거래 · ${investigation.summary.status} · ${investigation.summary.network}/${investigation.summary.symbol}",
                            AdminAction("/admin/transactions/${encode(investigation.summary.rootTransactionId)}"),
                        ),
                    )
                }
                networks.orEmpty().forEach { network ->
                    add(
                        SearchResult(
                            SearchKind.NETWORK,
                            network.code ?: network.displayName,
                            "네트워크 · ${network.displayName}",
                            AdminAction("/admin/networks?q=${encode(network.code ?: network.displayName)}"),
                        ),
                    )
                }
                mappings
                    .orEmpty()
                    .filter { it.network.contains(normalized, true) || it.symbol.contains(normalized, true) }
                    .forEach { mapping ->
                        add(
                            SearchResult(
                                SearchKind.ASSET,
                                "${mapping.network} / ${mapping.symbol}",
                                "자산 매핑",
                                AdminAction("/admin/assets?network=${encode(mapping.network)}&symbol=${encode(mapping.symbol)}"),
                            ),
                        )
                    }
            }
        val state =
            when {
                issues.isNotEmpty() -> ViewState.PARTIAL
                transaction?.let(::isStale) == true -> ViewState.STALE
                networks.orEmpty().any(::isStale) -> ViewState.STALE
                else -> ViewState.FRESH
            }
        return ViewResult(results, state, issues)
    }

    private fun optionalTransaction(
        identifier: String,
        issues: MutableList<SourceIssue>,
    ): AdminTransactionInvestigation? =
        try {
            gateway.transactionInvestigation(identifier).also { issues += truncationIssues(it) }
        } catch (failure: SourceFailure) {
            if (failure.status == 404) {
                null
            } else {
                issues +=
                    SourceIssue(
                        "transaction",
                        if (failure.status == 403) "FORBIDDEN" else "UPSTREAM_UNAVAILABLE",
                        "거래 조회를 불러오지 못했습니다.",
                    )
                null
            }
        }

    private fun truncationIssues(investigation: AdminTransactionInvestigation): List<SourceIssue> =
        investigation.truncatedSources.map { source ->
            SourceIssue(source, "TRUNCATED", "상세 조회 상한을 넘어 일부 데이터만 표시합니다.")
        }

    private fun <T> capture(
        source: String,
        issues: MutableList<SourceIssue>,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (failure: SourceFailure) {
            issues += SourceIssue(source, if (failure.status == 403) "FORBIDDEN" else "UPSTREAM_UNAVAILABLE", "일부 데이터를 불러오지 못했습니다.")
            null
        }

    private fun isStale(network: AdminNetwork): Boolean {
        val observedAt = parseCoreTime(network.syncedAt) ?: return true
        return observedAt.plusSeconds(staleAfterSeconds).isBefore(Instant.now(clock))
    }

    private fun isStale(investigation: AdminTransactionInvestigation): Boolean {
        if (investigation.summary.status !in PENDING_TRANSACTION_STATUSES) return false
        val observedAt = runCatching { Instant.parse(investigation.summary.lastChangedAt) }.getOrNull() ?: return true
        return observedAt.plusSeconds(staleAfterSeconds).isBefore(Instant.now(clock))
    }

    private fun evidenceExpired(contract: AdminContract): Boolean {
        if (contract.evidenceStatus != "VALID") return false
        val validUntil = contract.evidenceValidUntil?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
        return !validUntil.isAfter(Instant.now(clock))
    }

    private fun parseCoreTime(value: String): Instant? =
        try {
            val formatter = if (value.length == 12) CORE_MINUTE else CORE_SECOND
            LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC)
        } catch (_: RuntimeException) {
            null
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private val CORE_SECOND: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val CORE_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        private val PENDING_TRANSACTION_STATUSES = setOf("SUBMITTED", "CONFIRMED")
    }
}
