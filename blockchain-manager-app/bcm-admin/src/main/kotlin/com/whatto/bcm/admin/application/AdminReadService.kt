package com.whatto.bcm.admin.application

import com.whatto.bcm.admin.client.AdminAssetMapping
import com.whatto.bcm.admin.client.AdminBandS
import com.whatto.bcm.admin.client.AdminChangeRequest
import com.whatto.bcm.admin.client.AdminContract
import com.whatto.bcm.admin.client.AdminExecutionGateOverview
import com.whatto.bcm.admin.client.AdminExternalControlEvidence
import com.whatto.bcm.admin.client.AdminNetwork
import com.whatto.bcm.admin.client.AdminPolicy
import com.whatto.bcm.admin.client.AdminRuntimeReadiness
import com.whatto.bcm.admin.client.AdminSweepOperations
import com.whatto.bcm.admin.client.AdminSweepRequestInvestigation
import com.whatto.bcm.admin.client.AdminTransactionInvestigation
import com.whatto.bcm.admin.client.AdminVaultReconciliation
import com.whatto.bcm.admin.client.AdminVaultReconciliationRun
import com.whatto.bcm.admin.client.AdminWebhookRuntime
import com.whatto.bcm.admin.client.BcmAdminReadGateway
import com.whatto.bcm.admin.client.BcmWebhookHealthGateway
import com.whatto.bcm.admin.client.SourceFailure
import com.whatto.bcm.admin.config.AdminProperties
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
    val catalogNetworkCount: Int?,
    val adoptedNetworkCount: Int?,
    val availableNetworkCount: Int?,
    val catalogSyncedAt: String?,
    val networkCount: Int?,
    val testnetCount: Int?,
    val assetMappingCount: Int?,
    val webhook: AdminWebhookRuntime?,
    val preparationChecks: List<AdminPreparationCheck>,
    val issues: List<SourceIssue>,
)

enum class PreparationOwner {
    AUTO,
    DIRECT,
}

enum class PreparationStatus {
    READY,
    ACTION_REQUIRED,
    NOT_OBSERVED,
}

data class AdminPreparationCheck(
    val key: String,
    val label: String,
    val owner: PreparationOwner,
    val status: PreparationStatus,
    val detail: String,
    val action: AdminAction,
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
    val q: String? = null,
)

enum class SearchKind {
    TRANSACTION,
    SWEEP_REQUEST,
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
    private val webhookHealthGateway: BcmWebhookHealthGateway,
    private val clock: Clock,
    @param:Value("\${bcm.admin.stale-after-seconds:90000}") private val staleAfterSeconds: Long,
    private val properties: AdminProperties = AdminProperties(),
) {
    fun overview(): AdminOverview {
        val issues = mutableListOf<SourceIssue>()
        val catalog = capture("networks", issues) { gateway.networks(null, null, null, null) }
        val networks = catalog?.filter { it.code != null }
        val mappings = capture("assets", issues) { gateway.assetMappings(null, null) }
        val runtime = capture("runtimeReadiness", issues) { gateway.runtimeReadiness() }
        val webhookHealthy = capture("webhookHealth", issues) { webhookHealthGateway.isReady() }
        if (catalog == null && mappings == null && runtime == null) {
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
            catalogNetworkCount = catalog?.size,
            adoptedNetworkCount = networks?.size,
            availableNetworkCount = catalog?.count { it.code == null && !it.deprecated },
            catalogSyncedAt = catalog?.maxOfOrNull(AdminNetwork::syncedAt),
            networkCount = networks?.size,
            testnetCount = networks?.count(AdminNetwork::testnet),
            assetMappingCount = mappings?.size,
            webhook = runtime?.webhook,
            preparationChecks = preparationChecks(catalog, networks, mappings, runtime?.webhook, webhookHealthy),
            issues = issues,
        )
    }

    private fun preparationChecks(
        catalog: List<AdminNetwork>?,
        networks: List<AdminNetwork>?,
        mappings: List<AdminAssetMapping>?,
        webhook: AdminWebhookRuntime?,
        webhookHealthy: Boolean?,
    ): List<AdminPreparationCheck> {
        val localCatalog = properties.vendorMode == "STUB" && properties.chainMode == "LOCAL"
        val expectedNetworks = setOf("ETHEREUM", "BASE")
        val expectedMappings = expectedNetworks.flatMap { network -> setOf("USDC", "KRWK").map { network to it } }.toSet()
        val observedNetworks = networks.orEmpty().mapNotNull(AdminNetwork::code).toSet()
        val observedMappings = mappings.orEmpty().map { it.network to it.symbol }.toSet()
        val adoptedNetworkCount = expectedNetworks.count { it in observedNetworks }
        val assetMappingCount = expectedMappings.count { it in observedMappings }
        return listOf(
            bcmWebhookPreparationCheck(catalog, mappings, webhook, webhookHealthy),
            preparationCheck(
                key = "NETWORK_CATALOG",
                label = "네트워크 후보 동기화",
                owner = PreparationOwner.AUTO,
                ready = !catalog.isNullOrEmpty(),
                detail =
                    if (catalog.isNullOrEmpty()) {
                        "BAT는 기본 up에 포함되지 않습니다. 로컬 자산 준비 시나리오로 catalog sync를 한 번 실행하세요."
                    } else {
                        "BAT catalog sync 결과가 있습니다. 네트워크 화면의 마지막 동기화 시각을 확인하세요."
                    },
                href = if (catalog.isNullOrEmpty()) "/admin/test-runs" else "/admin/networks",
            ),
            preparationCheck(
                key = "NETWORK_ADOPTION",
                label = "네트워크 채택 확인",
                owner = PreparationOwner.DIRECT,
                ready = if (localCatalog) adoptedNetworkCount == expectedNetworks.size else !networks.isNullOrEmpty(),
                detail =
                    if (localCatalog) {
                        "로컬 기본 네트워크 채택 $adoptedNetworkCount/${expectedNetworks.size}: ETHEREUM, BASE"
                    } else {
                        "chainId와 testnet 여부를 확인해 사용할 네트워크를 채택합니다."
                    },
                href = "/admin/networks",
            ),
            preparationCheck(
                key = "ASSET_MAPPING",
                label = "자산 매핑 확인",
                owner = PreparationOwner.DIRECT,
                ready = if (localCatalog) assetMappingCount == expectedMappings.size else !mappings.isNullOrEmpty(),
                detail =
                    if (localCatalog) {
                        "로컬 기본 자산 매핑 $assetMappingCount/${expectedMappings.size}: 각 네트워크의 USDC, KRWK"
                    } else {
                        "네트워크·심볼·token contract 주소를 대조합니다."
                    },
                href = "/admin/assets",
            ),
            AdminPreparationCheck(
                key = "ACCOUNT_ADDRESS",
                label = "계정·입금 주소 준비",
                owner = PreparationOwner.DIRECT,
                status = PreparationStatus.NOT_OBSERVED,
                detail = "Admin은 DAW-CORE 입력을 기다리지 않습니다. 계정 API 또는 로컬 입금 점검을 사용하세요.",
                action = AdminAction("/admin/test-runs"),
            ),
        )
    }

    private fun bcmWebhookPreparationCheck(
        catalog: List<AdminNetwork>?,
        mappings: List<AdminAssetMapping>?,
        webhook: AdminWebhookRuntime?,
        healthy: Boolean?,
    ): AdminPreparationCheck {
        val status =
            when {
                catalog == null || mappings == null || webhook == null -> PreparationStatus.ACTION_REQUIRED
                healthy != true -> PreparationStatus.ACTION_REQUIRED
                webhook.state == "HEALTHY" -> PreparationStatus.READY
                webhook.state == "NEVER_RECEIVED" -> PreparationStatus.NOT_OBSERVED
                else -> PreparationStatus.ACTION_REQUIRED
            }
        val detail =
            when {
                catalog == null || mappings == null || webhook == null ->
                    "BCM 읽기 전용 조회가 일부 응답하지 않습니다. API 로그를 확인하세요."
                healthy != true -> "Webhook 프로세스 health가 응답하지 않습니다. 독립 프로세스와 로그를 확인하세요."
                webhook.state == "HEALTHY" -> "마지막 수신과 처리 원장이 정상입니다."
                webhook.state == "BACKLOG" -> "미처리 인박스 또는 미발행 outbox가 있습니다."
                webhook.state == "POISONED" -> "격리된 Webhook이 있어 원인 확인이 필요합니다."
                webhook.state == "NEVER_RECEIVED" -> "아직 수신 이력이 없습니다. 첫 입금 점검을 실행하세요."
                else -> "Webhook runtime 요약을 읽지 못했습니다."
            }
        return AdminPreparationCheck(
            key = "BCM_WEBHOOK",
            label = "BCM API·Webhook 연결",
            owner = PreparationOwner.AUTO,
            status = status,
            detail = detail,
            action = AdminAction("/admin/emergency"),
        )
    }

    private fun preparationCheck(
        key: String,
        label: String,
        owner: PreparationOwner,
        ready: Boolean,
        detail: String,
        href: String,
        observed: Boolean = true,
    ) = AdminPreparationCheck(
        key = key,
        label = label,
        owner = owner,
        status =
            when {
                ready -> PreparationStatus.READY
                !observed -> PreparationStatus.NOT_OBSERVED
                else -> PreparationStatus.ACTION_REQUIRED
            },
        detail = detail,
        action = AdminAction(href),
    )

    fun networks(filters: NetworkFilters): ViewResult<List<AdminNetwork>> {
        val data = gateway.networks(filters.q, filters.chainId, filters.adopted, filters.testnet)
        return ViewResult(data, if (data.any(::isStale)) ViewState.STALE else ViewState.FRESH, emptyList())
    }

    fun assets(filters: AssetFilters): ViewResult<List<AdminAssetMapping>> {
        val query = filters.q?.trim()?.takeIf(String::isNotEmpty)
        val data =
            gateway
                .assetMappings(filters.network.normalizedCode(), filters.symbol.normalizedCode())
                .filter { query == null || it.matches(query) }
        return ViewResult(data, ViewState.FRESH, emptyList())
    }

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

    fun sweepRequest(identifier: String): ViewResult<AdminSweepRequestInvestigation> {
        val investigation = gateway.sweepRequestInvestigation(identifier)
        val issues =
            investigation.truncatedSources.map { source ->
                SourceIssue(source, "DETAIL_TRUNCATED", "상세 조회 상한 100건을 초과해 일부만 표시합니다.")
            }
        return ViewResult(
            investigation,
            if (issues.isEmpty()) ViewState.FRESH else ViewState.PARTIAL,
            issues,
        )
    }

    fun sweepOperations(): ViewResult<AdminSweepOperations> {
        val overview = gateway.sweepOperations()
        val issues =
            buildList {
                if (overview.blockedRequestCount > 0) {
                    add(SourceIssue("sweepRequests", "BLOCKED", "실행 gate 해제를 기다리는 Sweep 요청이 있습니다."))
                }
                if (overview.failedRequestCount > 0 || overview.failedEventCount > 0) {
                    add(SourceIssue("sweepEvents", "FAILED", "운영 확인이 필요한 Sweep 요청 또는 event가 있습니다."))
                }
                if (overview.awaitingDawCompletionCount > 0) {
                    add(SourceIssue("dawCompletion", "WAITING", "DAW-CORE 완료 확인을 기다리는 Sweep event가 있습니다."))
                }
            }
        return ViewResult(overview, if (issues.isEmpty()) ViewState.FRESH else ViewState.PARTIAL, issues)
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

    fun startVaultReconciliation(query: String?): ViewResult<AdminVaultReconciliationRun> =
        ViewResult(
            gateway.startVaultReconciliation(query?.trim()?.takeIf { it.isNotEmpty() }),
            ViewState.STALE,
            emptyList(),
        )

    fun vaultReconciliation(
        runId: String,
        cursor: String?,
        limit: Int,
    ): ViewResult<AdminVaultReconciliation> {
        val data = gateway.vaultReconciliation(runId, cursor, limit)
        val issues =
            data.items
                .filter { it.reconciliationStatus != "MANAGED" }
                .map { vault ->
                    SourceIssue(
                        source = vault.vendorVaultId,
                        code = vault.reconciliationStatus,
                        message =
                            if (vault.reconciliationStatus == "UNMANAGED") {
                                "Fireblocks vault에 대응하는 BCM 계정이 없습니다."
                            } else {
                                "BCM 계정의 Fireblocks vault를 찾지 못했습니다."
                            },
                    )
                }.toMutableList()
        data.run.failureCode?.let { issues += SourceIssue(data.run.runId, it, "Vault 전체 대사가 완주하지 못했습니다.") }
        val state =
            when (data.run.status) {
                "ACCEPTED", "RUNNING" -> ViewState.STALE
                "PARTIAL", "FAILED" -> ViewState.PARTIAL
                "COMPLETED" -> if (issues.isEmpty()) ViewState.FRESH else ViewState.PARTIAL
                else -> ViewState.PARTIAL
            }
        return ViewResult(data, state, issues)
    }

    fun policies(): ViewResult<List<AdminPolicy>> = ViewResult(gateway.policies(), ViewState.FRESH, emptyList())

    fun runtimeReadiness(): ViewResult<AdminRuntimeReadiness> = ViewResult(gateway.runtimeReadiness(), ViewState.FRESH, emptyList())

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
        val sweepRequest = optionalSweepRequest(query, issues)
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
                sweepRequest?.let { investigation ->
                    add(
                        SearchResult(
                            SearchKind.SWEEP_REQUEST,
                            investigation.sweepRequestId,
                            "Sweep request · ${investigation.status} · ${investigation.network}/${investigation.symbol}",
                            AdminAction("/admin/sweeps/${encode(investigation.sweepRequestId)}"),
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
                    .filter { it.matches(normalized) }
                    .forEach { mapping ->
                        add(
                            SearchResult(
                                SearchKind.ASSET,
                                "${mapping.network} / ${mapping.symbol}",
                                "자산 매핑 · ${mapping.contractAddress ?: "네이티브 자산"}",
                                AdminAction("/admin/assets?q=${encode(query)}"),
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

    private fun optionalSweepRequest(
        identifier: String,
        issues: MutableList<SourceIssue>,
    ): AdminSweepRequestInvestigation? =
        try {
            gateway.sweepRequestInvestigation(identifier)
        } catch (failure: SourceFailure) {
            if (failure.status == 404) {
                null
            } else {
                issues += SourceIssue(failure.source, "SOURCE_UNAVAILABLE", "Sweep 요청 검색 소스를 사용할 수 없습니다.")
                null
            }
        }

    private fun String?.normalizedCode(): String? =
        this
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase()

    private fun AdminAssetMapping.matches(query: String): Boolean =
        network.contains(query, ignoreCase = true) ||
            symbol.contains(query, ignoreCase = true) ||
            contractAddress?.contains(query, ignoreCase = true) == true

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
