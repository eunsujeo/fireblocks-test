package com.whatto.bcm.infra.persistence.wallet

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.provider.ProviderOriginRepository
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.vendor.NetworkWalletRecoveryDecision
import com.whatto.bcm.domain.vendor.NetworkWalletRecoveryPolicy
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.wallet.NetworkWalletCreationIntent
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.domain.wallet.NetworkWalletCreationStatus
import com.whatto.bcm.domain.wallet.NetworkWalletProvisioningRepository
import com.whatto.bcm.domain.wallet.NetworkWalletRecoveryPage
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
class NetworkWalletProvisioningJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val origins: ProviderOriginRepository,
) : NetworkWalletProvisioningRepository {
    private val policy = NetworkWalletRecoveryPolicy()

    override fun reserve(
        seed: NetworkWalletCreationSeed,
        now: String,
    ): NetworkWalletCreationIntent {
        verifyOrigin(seed.request.scope)
        jdbc.update(
            """
            INSERT INTO bcm_ntwk_wlt_crtn_l
              (crtn_id, orgn_id, acnt_id, ntwk_cd, corr_id, req_hash, req_vrsn, vndr_ntwk,
               crtn_stcd, rvsn, scan_done_yn, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (:intentId, :originId, :accountId, :network, :correlationId, :requestHash, :requestVersion, :vendorNetwork,
                    'PREPARED', 0, 'N', :now, :now, :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (orgn_id, acnt_id, ntwk_cd) DO NOTHING
            """.trimIndent(),
            parameters(seed.request.scope) +
                mapOf(
                    "intentId" to seed.intentId,
                    "correlationId" to seed.request.correlationId,
                    "requestHash" to seed.submission.requestHash,
                    "requestVersion" to seed.submission.requestVersion,
                    "vendorNetwork" to seed.submission.vendorNetwork,
                    "now" to now,
                ),
        )
        val stored = requireIntent(seed.request.scope)
        if (stored.submission != seed.submission) throw ConflictException("networkWalletCreationRequest", stored.intentId)
        return stored
    }

    override fun find(scope: NetworkWalletScope): NetworkWalletCreationIntent? {
        verifyOrigin(scope)
        return readIntent(scope)
    }

    override fun claimSubmission(
        scope: NetworkWalletScope,
        expectedRevision: Long,
        now: String,
    ): NetworkWalletCreationIntent? {
        verifyOrigin(scope)
        val intent = requireIntent(scope)
        if (!intent.canClaimSubmission(expectedRevision)) return null
        val changed =
            jdbc.update(
                """
                UPDATE bcm_ntwk_wlt_crtn_l SET crtn_stcd = 'SUBMITTING', post_prep_dttm = :now,
                  rvsn = rvsn + 1, last_chng_dttm = :now, last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                WHERE crtn_id = :intentId AND rvsn = :revision AND crtn_stcd = 'PREPARED'
                """.trimIndent(),
                parameters(scope) + mapOf("intentId" to intent.intentId, "revision" to expectedRevision, "now" to now),
            )
        check(changed == 1) { "Network wallet submission claim was not stored" }
        return requireIntent(scope)
    }

    override fun startRecovery(
        scope: NetworkWalletScope,
        expectedRevision: Long,
        scanId: String,
        now: String,
    ): NetworkWalletCreationIntent {
        require(scanId.isNotBlank() && scanId == scanId.trim() && scanId.length <= 64) { "Invalid wallet scan identifier" }
        verifyOrigin(scope)
        val intent = requireIntent(scope)
        intent.requireRecovery(expectedRevision)
        val params = parameters(scope) + mapOf("intentId" to intent.intentId, "scanId" to scanId, "now" to now)
        val used =
            jdbc.queryForObject(
                "SELECT count(*) FROM bcm_ntwk_wlt_obs_l WHERE crtn_id = :intentId AND scan_id = :scanId",
                params,
                Int::class.java,
            )
        if (scanId == intent.scanId || used != 0) throw ConflictException("networkWalletScanIdentifier", intent.intentId)
        jdbc.update(
            """
            UPDATE bcm_ntwk_wlt_crtn_l SET crtn_stcd = 'RECOVERING', scan_id = :scanId, next_crsr = NULL,
              scan_done_yn = 'N', last_rsn = NULL, rvsn = rvsn + 1, last_chng_dttm = :now,
              last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
            WHERE crtn_id = :intentId
            """.trimIndent(),
            params,
        )
        return requireIntent(scope)
    }

    override fun recordPage(
        scope: NetworkWalletScope,
        expectedRevision: Long,
        page: NetworkWalletRecoveryPage,
        now: String,
    ): NetworkWalletCreationIntent {
        verifyOrigin(scope)
        val intent = requireIntent(scope)
        intent.requirePage(expectedRevision, page)
        val params = parameters(scope) + mapOf("intentId" to intent.intentId, "scanId" to page.scanId)
        val previousCursors =
            jdbc.query(
                "SELECT qry_crsr FROM bcm_ntwk_wlt_obs_l WHERE crtn_id = :intentId AND scan_id = :scanId ORDER BY page_no",
                params,
            ) { row, _ -> row.getString("qry_crsr") }
        if (page.next != null && (page.next == page.cursor || page.next in previousCursors)) {
            throw ConflictException("networkWalletRecoveryCursor", intent.intentId)
        }
        storePage(intent, page, previousCursors.size)
        val candidates =
            jdbc.query(
                """
                SELECT i.* FROM bcm_ntwk_wlt_obs_item_l i JOIN bcm_ntwk_wlt_obs_l p USING (page_id, crtn_id)
                WHERE p.crtn_id = :intentId AND p.scan_id = :scanId ORDER BY p.page_no, i.item_no
                """.trimIndent(),
                params,
                observationMapper,
            )
        val decision = policy.evaluate(intent.request, candidates, page.next == null, intent.knownWalletId)
        val walletAvailable = decision !is NetworkWalletRecoveryDecision.Ready || storeWallet(intent, page, decision.wallet, now)
        val result = intent.recoveryResult(decision, candidates, walletAvailable)
        jdbc.update(
            """
            UPDATE bcm_ntwk_wlt_crtn_l SET crtn_stcd = :status, vndr_wlt_id = :walletId, next_crsr = :next,
              scan_done_yn = :complete, last_rsn = :reason, rvsn = rvsn + 1, last_chng_dttm = :now,
              last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
            WHERE crtn_id = :intentId
            """.trimIndent(),
            params +
                mapOf(
                    "status" to result.status.name,
                    "walletId" to result.knownWalletId,
                    "next" to page.next,
                    "complete" to if (page.next == null) "Y" else "N",
                    "reason" to result.reason,
                    "now" to now,
                ),
        )
        return requireIntent(scope)
    }

    override fun findWallet(scope: NetworkWalletScope): NetworkWalletObservation? {
        verifyOrigin(scope)
        return jdbc
            .query(
                """
                SELECT w.vndr_wlt_id, w.wlt_addr, c.corr_id FROM bcm_ntwk_wlt_m w
                  JOIN bcm_ntwk_wlt_crtn_l c USING (crtn_id)
                WHERE w.orgn_id = :originId AND w.acnt_id = :accountId AND w.ntwk_cd = :network
                  AND c.crtn_stcd = 'COMPLETED'
                """.trimIndent(),
                parameters(scope),
            ) { row, _ ->
                NetworkWalletObservation(
                    scope.origin,
                    scope.network,
                    row.getString("vndr_wlt_id"),
                    row.getString("corr_id"),
                    NetworkWalletOwnership.ORGANIZATION,
                    row.getString("wlt_addr"),
                )
            }.singleOrNull()
    }

    private fun storePage(
        intent: NetworkWalletCreationIntent,
        page: NetworkWalletRecoveryPage,
        pageNumber: Int,
    ) {
        val params =
            parameters(intent.request.scope) +
                mapOf(
                    "intentId" to intent.intentId,
                    "pageId" to page.pageId,
                    "scanId" to page.scanId,
                    "pageNumber" to pageNumber,
                    "cursor" to page.cursor,
                    "next" to page.next,
                    "reference" to page.evidenceReference,
                    "hash" to page.evidenceHash,
                    "observedAt" to page.observedAt,
                )
        jdbc.update(
            """
            INSERT INTO bcm_ntwk_wlt_obs_l
              (page_id, crtn_id, scan_id, page_no, qry_crsr, next_crsr, evdc_ref, evdc_hash, obs_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (:pageId, :intentId, :scanId, :pageNumber, :cursor, :next, :reference, :hash, :observedAt,
                    :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            params,
        )
        page.candidates.forEachIndexed { index, observation ->
            jdbc.update(
                """
                INSERT INTO bcm_ntwk_wlt_obs_item_l
                  (page_id, crtn_id, item_no, orgn_id, exec_mode, prtc_prvd, pltfrm_inst_id, vndr_org_id, chain_mode,
                   ntwk_cd, vndr_wlt_id, corr_id, ownr_dvcd, wlt_addr,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (:pageId, :intentId, :index, :observedOrigin, :mode, :protocol, :instance, :organization, :chain,
                        :observedNetwork, :walletId, :correlationId, :ownership, :address,
                        :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                params +
                    mapOf(
                        "index" to index,
                        "observedOrigin" to observation.origin.originId,
                        "mode" to observation.origin.executionMode,
                        "protocol" to observation.origin.protocolProvider,
                        "instance" to observation.origin.platformInstanceId,
                        "organization" to observation.origin.vendorOrganizationId,
                        "chain" to observation.origin.chainMode,
                        "observedNetwork" to observation.network,
                        "walletId" to observation.vendorWalletId,
                        "correlationId" to observation.correlationId,
                        "ownership" to observation.ownership.name,
                        "address" to observation.address,
                    ),
            )
        }
    }

    private fun storeWallet(
        intent: NetworkWalletCreationIntent,
        page: NetworkWalletRecoveryPage,
        wallet: NetworkWalletObservation,
        now: String,
    ): Boolean =
        jdbc.update(
            """
            INSERT INTO bcm_ntwk_wlt_m
              (crtn_id, orgn_id, acnt_id, ntwk_cd, vndr_wlt_id, wlt_addr, page_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (:intentId, :originId, :accountId, :network, :walletId, :address, :pageId, :now,
                    :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (orgn_id, vndr_wlt_id) DO NOTHING
            """.trimIndent(),
            parameters(intent.request.scope) +
                mapOf(
                    "intentId" to intent.intentId,
                    "walletId" to wallet.vendorWalletId,
                    "address" to wallet.address,
                    "pageId" to page.pageId,
                    "now" to now,
                ),
        ) == 1

    override fun ownsWalletAddress(
        origin: ProviderOrigin,
        network: String,
        address: String,
    ): Boolean {
        origin.requireMatch(origins.findBinding())
        // 계정·자산을 묻지 않는다 — 소유권만 본다. 완료되지 않은 의도의 지갑도 주소가 있으면 우리 것이다.
        return jdbc
            .query(
                """
                SELECT 1 FROM bcm_ntwk_wlt_m
                 WHERE orgn_id = :originId AND ntwk_cd = :network AND lower(wlt_addr) = lower(:address)
                 LIMIT 1
                """.trimIndent(),
                mapOf("originId" to origin.originId, "network" to network, "address" to address),
            ) { _, _ -> true }
            .isNotEmpty()
    }

    private fun verifyOrigin(scope: NetworkWalletScope) = scope.origin.requireMatch(origins.findBinding())

    private fun requireIntent(scope: NetworkWalletScope): NetworkWalletCreationIntent =
        readIntent(scope, lock = true) ?: throw ConflictException("networkWalletIntent", scope.accountId)

    private fun readIntent(
        scope: NetworkWalletScope,
        lock: Boolean = false,
    ): NetworkWalletCreationIntent? =
        jdbc
            .query(
                "SELECT * FROM bcm_ntwk_wlt_crtn_l WHERE orgn_id = :originId AND acnt_id = :accountId AND ntwk_cd = :network" +
                    if (lock) " FOR UPDATE" else "",
                parameters(scope),
            ) { row, _ ->
                NetworkWalletCreationIntent(
                    row.getString("crtn_id"),
                    NetworkWalletCreationRequest(scope, row.getString("corr_id")),
                    NetworkWalletSubmissionSpec(row.getString("req_hash"), row.getString("req_vrsn"), row.getString("vndr_ntwk")),
                    NetworkWalletCreationStatus.valueOf(row.getString("crtn_stcd")),
                    row.getLong("rvsn"),
                    row.getString("post_prep_dttm"),
                    row.getString("vndr_wlt_id"),
                    row.getString("scan_id"),
                    row.getString("next_crsr"),
                    row.getString("scan_done_yn") == "Y",
                    row.getString("last_rsn"),
                    row.getString("reg_dttm"),
                    row.getString("last_chng_dttm"),
                )
            }.singleOrNull()

    private fun parameters(scope: NetworkWalletScope): Map<String, Any?> =
        mapOf(
            "originId" to scope.origin.originId,
            "accountId" to scope.accountId,
            "network" to scope.network,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private val observationMapper =
        RowMapper { row, _ ->
            NetworkWalletObservation(
                ProviderOrigin(
                    row.getString("orgn_id"),
                    row.getString("exec_mode"),
                    row.getString("prtc_prvd"),
                    row.getString("pltfrm_inst_id"),
                    row.getString("vndr_org_id"),
                    row.getString("chain_mode"),
                ),
                row.getString("ntwk_cd"),
                row.getString("vndr_wlt_id"),
                row.getString("corr_id"),
                NetworkWalletOwnership.valueOf(row.getString("ownr_dvcd")),
                row.getString("wlt_addr"),
            )
        }
}
