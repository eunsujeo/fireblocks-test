package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepInvalidation
import com.whatto.bcm.domain.sweep.SweepItem
import com.whatto.bcm.domain.sweep.SweepItemReconciliation
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Repository
class SweepExecutionJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : SweepExecutionRepository {
    @Transactional
    override fun createAndClaim(
        execution: SweepExecution,
        items: List<SweepItem>,
    ) {
        validate(execution, items)
        try {
            requireExecutionGateOpen(execution.network)
            lockAdminSnapshot(execution)
            val requestIds = lockAndValidateRequestItems(execution, items)
            insertExecution(execution)
            lockAndValidateAuthorizations(execution, items)
            items.forEach(::insertItem)
            items.sortedBy { it.accountId }.forEach { item ->
                val claimed =
                    jdbc.update(
                        """
                        UPDATE bcm_swp_trgt
                        SET actv_swp_exec_id = :executionId,
                            actv_item_seq = :sequence,
                            try_cnt = try_cnt + 1,
                            last_try_dttm = :attemptedAt,
                            last_chng_empno = :employeeNo,
                            last_chng_brcd = :branchCode
                        WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
                          AND actv_swp_exec_id IS NULL AND actv_item_seq IS NULL
                        """.trimIndent(),
                        itemParameters(item) +
                            mapOf(
                                "network" to execution.network,
                                "symbol" to execution.symbol,
                                "attemptedAt" to execution.requestedAt,
                            ),
                    )
                if (claimed != 1) throw ConflictException("sweepTarget", item.accountId)
            }
            markRequestItemsProcessing(items, requestIds)
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("sweepExecution", execution.executionId, exception)
        }
    }

    override fun findById(executionId: String): SweepExecution? =
        jdbc
            .query(
                "$EXECUTION_COLUMNS WHERE swp_exec_id = :executionId",
                mapOf("executionId" to executionId),
                EXECUTION_MAPPER,
            ).firstOrNull()

    override fun findByIdForUpdate(executionId: String): SweepExecution? =
        jdbc
            .query(
                "$EXECUTION_COLUMNS WHERE swp_exec_id = :executionId FOR UPDATE",
                mapOf("executionId" to executionId),
                EXECUTION_MAPPER,
            ).firstOrNull()

    override fun findItems(executionId: String): List<SweepItem> =
        jdbc.query(
            ITEM_WITH_REQUEST_COLUMNS + " WHERE item.swp_exec_id = :executionId ORDER BY item.item_seq",
            mapOf("executionId" to executionId),
            ITEM_MAPPER,
        )

    override fun findPendingSubmission(operatorAccountId: String): SweepExecution? =
        jdbc
            .query(
                "$EXECUTION_COLUMNS WHERE opr_acnt_id = :operatorAccountId " +
                    "AND swp_exec_stcd IN ('READY', 'SUBMITTING') ORDER BY req_dttm, swp_exec_id",
                mapOf("operatorAccountId" to operatorAccountId),
                EXECUTION_MAPPER,
            ).singleOrNull()

    override fun findReconciling(limit: Int): List<SweepExecution> {
        require(limit > 0) { "sweep reconciliation limit must be positive" }
        return jdbc.query(
            "$EXECUTION_COLUMNS WHERE swp_exec_stcd = 'RECONCILING' ORDER BY req_dttm, swp_exec_id LIMIT :limit",
            mapOf("limit" to limit),
            EXECUTION_MAPPER,
        )
    }

    @Transactional
    override fun markSubmitting(executionId: String): SweepExecution {
        val current = findByIdForUpdate(executionId) ?: throw ResourceNotFoundException("sweepExecution", executionId)
        if (current.status == SweepExecutionStatus.SUBMITTING) return current
        if (current.status != SweepExecutionStatus.READY) throw ConflictException("sweepExecution", executionId)
        requireExecutionGateOpen(current.network)
        lockAdminSnapshot(current)
        val updated =
            jdbc.update(
                """
                UPDATE bcm_swp_exec_l
                SET swp_exec_stcd = 'SUBMITTING',
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE swp_exec_id = :executionId AND swp_exec_stcd = 'READY'
                """.trimIndent(),
                lifecycleParameters(executionId),
            )
        if (updated != 1) throw ConflictException("sweepExecution", executionId)
        return required(executionId)
    }

    @Transactional
    override fun recordSubmissionRetry(
        executionId: String,
        attemptedAt: String,
    ): SweepExecution {
        val current = findByIdForUpdate(executionId) ?: throw ResourceNotFoundException("sweepExecution", executionId)
        if (current.status != SweepExecutionStatus.SUBMITTING) throw ConflictException("sweepExecution", executionId)
        val retried =
            jdbc.update(
                """
                UPDATE bcm_swp_trgt
                SET try_cnt = try_cnt + 1,
                    last_try_dttm = :attemptedAt,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE actv_swp_exec_id = :executionId
                """.trimIndent(),
                lifecycleParameters(executionId) + mapOf("attemptedAt" to attemptedAt),
            )
        if (retried != current.itemCount) throw ConflictException("sweepExecutionTargets", executionId)
        return required(executionId)
    }

    private fun requireExecutionGateOpen(network: String) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) IS NULL",
            mapOf("key" to "BCM:EXECUTION_GATE:$network:SWEEP"),
            Boolean::class.java,
        )
        val status =
            jdbc
                .queryForList(
                    """
                    SELECT gate_stcd
                      FROM bcm_exec_gate_evt_l
                     WHERE ntwk_cd = :network AND gate_dvcd = 'SWEEP'
                     ORDER BY evt_seq DESC
                     LIMIT 1
                    """.trimIndent(),
                    mapOf("network" to network),
                    String::class.java,
                ).firstOrNull()
        if (status == "STOPPED") throw ConflictException("executionGate", "$network:SWEEP")
    }

    private fun lockAdminSnapshot(execution: SweepExecution) {
        jdbc.queryForObject(
            """
            SELECT bind_rvsn
              FROM bcm_plcy_bind_m
             WHERE plcy_scope_id = 'POLICY:' || :network || ':' || :symbol
             FOR SHARE
            """.trimIndent(),
            mapOf("network" to execution.network, "symbol" to execution.symbol),
            Long::class.java,
        )
        jdbc.queryForObject(
            """
            SELECT binding.bind_rvsn
              FROM bcm_ctrt_bind_m binding
              JOIN bcm_ctrt_vrsn_l contract ON contract.ctrt_scope_id = binding.ctrt_scope_id
             WHERE contract.ctrt_vrsn_id = :contractVersionId
             FOR SHARE OF binding
            """.trimIndent(),
            mapOf("contractVersionId" to execution.contractVersionId),
            Long::class.java,
        )
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) IS NULL",
            mapOf("key" to "BCM:SWEEP:EVIDENCE:${execution.contractVersionId}"),
            Boolean::class.java,
        )
    }

    override fun markSubmitted(
        executionId: String,
        vendorTransactionId: String,
    ): SweepExecution {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_swp_exec_l
                SET swp_exec_stcd = 'SUBMITTED',
                    vndr_tx_id = :vendorTransactionId,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE swp_exec_id = :executionId
                  AND (swp_exec_stcd = 'SUBMITTING'
                    OR (swp_exec_stcd = 'SUBMITTED' AND vndr_tx_id = :vendorTransactionId))
                """.trimIndent(),
                lifecycleParameters(executionId) + mapOf("vendorTransactionId" to vendorTransactionId),
            )
        if (updated != 1) throw ConflictException("sweepExecution", executionId)
        return required(executionId)
    }

    override fun markReconciling(
        executionId: String,
        vendorTransactionId: String,
        transactionHash: String?,
    ): SweepExecution {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_swp_exec_l
                SET swp_exec_stcd = 'RECONCILING',
                    vndr_tx_id = COALESCE(vndr_tx_id, :vendorTransactionId),
                    tx_hash = COALESCE(tx_hash, :transactionHash),
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE swp_exec_id = :executionId
                  AND swp_exec_stcd IN ('SUBMITTING', 'SUBMITTED', 'RECONCILING')
                  AND (vndr_tx_id IS NULL OR vndr_tx_id = :vendorTransactionId)
                  AND (tx_hash IS NULL OR CAST(:transactionHash AS VARCHAR) IS NULL
                    OR tx_hash = CAST(:transactionHash AS VARCHAR))
                """.trimIndent(),
                lifecycleParameters(executionId) +
                    mapOf("vendorTransactionId" to vendorTransactionId, "transactionHash" to transactionHash),
            )
        if (updated != 1) {
            val current = required(executionId)
            val terminal =
                current.status == SweepExecutionStatus.COMPLETED ||
                    current.status == SweepExecutionStatus.PARTIAL ||
                    current.status == SweepExecutionStatus.FAILED
            val sameIdentity =
                current.vendorTransactionId == vendorTransactionId &&
                    (transactionHash == null || current.transactionHash?.equals(transactionHash, ignoreCase = true) == true)
            if (terminal && sameIdentity) return current
            throw ConflictException("sweepExecution", executionId)
        }
        return required(executionId)
    }

    @Transactional
    override fun completeReconciliation(
        executionId: String,
        items: List<SweepItemReconciliation>,
        status: SweepExecutionStatus,
        actualTotalAmount: String,
        finishedAt: String,
    ): SweepExecution {
        require(status == SweepExecutionStatus.COMPLETED || status == SweepExecutionStatus.PARTIAL) {
            "reconciled sweep status must be COMPLETED or PARTIAL"
        }
        val current = findByIdForUpdate(executionId) ?: throw ResourceNotFoundException("sweepExecution", executionId)
        lockRequests(executionId)
        require(items.size == current.itemCount) { "reconciled item count must match execution" }
        require(items.map { it.sequence }.distinct().size == items.size) { "reconciled item sequence must be unique" }
        items.sortedBy { it.sequence }.forEach { item ->
            val updated =
                jdbc.update(
                    """
                    UPDATE bcm_swp_item_l
                    SET actl_amt = :actualAmount,
                        swp_item_stcd = :status,
                        fail_cd = :failureCode,
                        log_idx = :logIndex,
                        last_chng_empno = :employeeNo,
                        last_chng_brcd = :branchCode
                    WHERE swp_exec_id = :executionId AND item_seq = :sequence AND swp_item_stcd = 'READY'
                    """.trimIndent(),
                    lifecycleParameters(executionId) +
                        mapOf(
                            "sequence" to item.sequence,
                            "actualAmount" to BigDecimal(item.actualAmount),
                            "status" to item.status.name,
                            "failureCode" to item.failureCode,
                            "logIndex" to item.logIndex,
                        ),
                )
            if (updated != 1) throw ConflictException("sweepItem", "$executionId:${item.sequence}")
            updateRequestItemAfterReconciliation(executionId, item)
        }
        val updated =
            jdbc.update(
                """
                UPDATE bcm_swp_exec_l
                SET swp_exec_stcd = :status,
                    actl_tot_amt = :actualTotalAmount,
                    fnsh_dttm = :finishedAt,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE swp_exec_id = :executionId AND swp_exec_stcd = 'RECONCILING'
                """.trimIndent(),
                lifecycleParameters(executionId) +
                    mapOf(
                        "status" to status.name,
                        "actualTotalAmount" to BigDecimal(actualTotalAmount),
                        "finishedAt" to finishedAt,
                    ),
            )
        if (updated != 1) throw ConflictException("sweepExecution", executionId)
        refreshRequestStatuses(executionId, finishedAt)
        return required(executionId)
    }

    @Transactional
    override fun markFailedAndRelease(
        executionId: String,
        failureCode: String,
        finishedAt: String,
    ): SweepExecution {
        findByIdForUpdate(executionId) ?: throw ResourceNotFoundException("sweepExecution", executionId)
        lockRequests(executionId)
        jdbc.update(
            """
            UPDATE bcm_swp_item_l
            SET swp_item_stcd = 'RETRY',
                fail_cd = :failureCode,
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            WHERE swp_exec_id = :executionId AND swp_item_stcd = 'READY'
            """.trimIndent(),
            lifecycleParameters(executionId) + mapOf("failureCode" to failureCode),
        )
        jdbc.update(
            """
            UPDATE bcm_swp_trgt
            SET actv_swp_exec_id = NULL,
                actv_item_seq = NULL,
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            WHERE actv_swp_exec_id = :executionId
            """.trimIndent(),
            lifecycleParameters(executionId),
        )
        jdbc.update(
            """
            UPDATE bcm_swp_req_item_l request_item
            SET swp_req_item_stcd = 'PENDING',
                last_fail_cd = execution_item.fail_cd,
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            FROM bcm_swp_item_l execution_item
            WHERE execution_item.swp_exec_id = :executionId
              AND execution_item.swp_req_item_id = request_item.swp_req_item_id
              AND request_item.swp_req_item_stcd = 'PROCESSING'
            """.trimIndent(),
            lifecycleParameters(executionId),
        )
        val updated =
            jdbc.update(
                """
                UPDATE bcm_swp_exec_l
                SET swp_exec_stcd = 'FAILED',
                    fnsh_dttm = :finishedAt,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE swp_exec_id = :executionId
                  AND swp_exec_stcd IN ('READY', 'SUBMITTING', 'SUBMITTED', 'RECONCILING', 'FAILED')
                """.trimIndent(),
                lifecycleParameters(executionId) + mapOf("finishedAt" to finishedAt),
            )
        if (updated != 1) throw ConflictException("sweepExecution", executionId)
        refreshRequestStatuses(executionId, finishedAt)
        return required(executionId)
    }

    @Transactional
    override fun invalidateFinalized(
        executionId: String,
        failureCode: String,
        finishedAt: String,
    ) {
        val current = findByIdForUpdate(executionId) ?: throw ResourceNotFoundException("sweepExecution", executionId)
        if (!SweepInvalidation.canInvalidate(current.status)) throw ConflictException("sweepExecution", executionId)
        lockRequests(executionId)
        val parameters = lifecycleParameters(executionId) + mapOf("failureCode" to failureCode, "finishedAt" to finishedAt)
        jdbc.update(
            """
            UPDATE bcm_swp_item_l
            SET swp_item_stcd = 'FAILED', actl_amt = 0, fail_cd = :failureCode, log_idx = NULL,
                last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
            WHERE swp_exec_id = :executionId
            """.trimIndent(),
            parameters,
        )
        // PROCESSING은 후속 실행이 소유하므로 유지한다. 완료한 요청만 재평가 대상으로 되돌린다.
        jdbc.update(
            """
            UPDATE bcm_swp_req_item_l request_item
            SET swp_req_item_stcd = 'PENDING', last_fail_cd = :failureCode,
                last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
            FROM bcm_swp_item_l execution_item
            WHERE execution_item.swp_exec_id = :executionId
              AND execution_item.swp_req_item_id = request_item.swp_req_item_id
              AND request_item.swp_req_item_stcd = 'COMPLETED'
            """.trimIndent(),
            parameters,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_trgt
              (acnt_id, ntwk_cd, tkn_smbl, reg_dttm, actv_swp_exec_id, actv_item_seq,
               try_cnt, last_try_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT item.acnt_id, execution.ntwk_cd, execution.tkn_smbl, :finishedAt, NULL, NULL,
                   0, NULL, :employeeNo, :branchCode, :employeeNo, :branchCode
            FROM bcm_swp_item_l item
            JOIN bcm_swp_exec_l execution ON execution.swp_exec_id = item.swp_exec_id
            WHERE item.swp_exec_id = :executionId
            ON CONFLICT (acnt_id, ntwk_cd, tkn_smbl) DO NOTHING
            """.trimIndent(),
            parameters,
        )
        jdbc.update(
            """
            UPDATE bcm_swp_exec_l
            SET swp_exec_stcd = 'FAILED', actl_tot_amt = 0, fnsh_dttm = :finishedAt,
                last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
            WHERE swp_exec_id = :executionId
            """.trimIndent(),
            parameters,
        )
        refreshRequestStatuses(executionId, finishedAt)
    }

    private fun lockRequests(executionId: String) {
        jdbc.queryForList(
            """
            SELECT request.swp_req_id
            FROM bcm_swp_req_l request
            WHERE request.swp_req_id IN (
                SELECT request_item.swp_req_id
                FROM bcm_swp_req_item_l request_item
                JOIN bcm_swp_item_l item ON item.swp_req_item_id = request_item.swp_req_item_id
                WHERE item.swp_exec_id = :executionId
            )
            ORDER BY request.swp_req_id
            FOR UPDATE
            """.trimIndent(),
            mapOf("executionId" to executionId),
        )
    }

    private fun validate(
        execution: SweepExecution,
        items: List<SweepItem>,
    ) {
        require(items.isNotEmpty()) { "sweep execution must contain at least one item" }
        require(items.size == execution.itemCount) { "sweep item count must match execution itemCount" }
        require(items.all { it.executionId == execution.executionId }) { "sweep item executionId must match" }
        require(items.map { it.sequence }.distinct().size == items.size) { "sweep item sequence must be unique" }
        require(items.map { it.accountId }.distinct().size == items.size) { "sweep item accountId must be unique" }
        require(items.all { it.sweepRequestItemId.isNotBlank() }) { "sweep item must reference a DAW request item" }
        require(items.all { it.sweepRequestId.isNotBlank() }) { "sweep item must reference a DAW request" }
        require(items.map { it.sweepRequestItemId }.distinct().size == items.size) {
            "sweep request item must be unique within an execution"
        }
        require(execution.policySnapshotHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "sweep execution must preserve the active Admin policy snapshot hash"
        }
        val total = items.map { BigDecimal(it.requestedAmount) }.fold(BigDecimal.ZERO, BigDecimal::add)
        require(total.compareTo(BigDecimal(execution.requestedTotalAmount)) == 0) {
            "sweep requested total amount must equal item sum"
        }
    }

    private fun lockAndValidateAuthorizations(
        execution: SweepExecution,
        items: List<SweepItem>,
    ) {
        items.sortedBy { it.accountId }.forEach { item ->
            val rows =
                jdbc.queryForList(
                    """
                    SELECT obs_alwnc, auth_stcd
                    FROM bcm_swp_auth_m
                    WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
                      AND swp_ctrt_addr = :sweepContractAddress
                    FOR UPDATE
                    """.trimIndent(),
                    mapOf(
                        "accountId" to item.accountId,
                        "network" to execution.network,
                        "symbol" to execution.symbol,
                        "sweepContractAddress" to execution.sweepContractAddress,
                    ),
                )
            val authorization = rows.singleOrNull() ?: throw ConflictException("sweepAuthorization", item.accountId)
            val observedAllowance = authorization["obs_alwnc"] as BigDecimal
            if (authorization["auth_stcd"] != SweepAuthorizationStatus.ACTIVE.name ||
                observedAllowance.compareTo(BigDecimal(item.requestedAmount)) < 0
            ) {
                throw ConflictException("sweepAuthorization", item.accountId)
            }
        }
    }

    private fun lockAndValidateRequestItems(
        execution: SweepExecution,
        items: List<SweepItem>,
    ): Set<String> {
        val rows =
            jdbc
                .queryForList(
                    """
                    SELECT request_item.swp_req_item_id, request.swp_req_id,
                           request.ntwk_cd, request.tkn_smbl, request.swp_req_stcd,
                           request_item.acnt_id, request_item.swp_req_item_stcd,
                           NOT EXISTS (
                             SELECT 1
                             FROM bcm_swp_req_item_l older_item
                             JOIN bcm_swp_req_l older_request ON older_request.swp_req_id = older_item.swp_req_id
                             WHERE older_item.acnt_id = request_item.acnt_id
                               AND older_request.ntwk_cd = request.ntwk_cd
                               AND older_request.tkn_smbl = request.tkn_smbl
                               AND older_item.swp_req_item_stcd = 'PENDING'
                               AND older_request.swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'PARTIAL')
                               AND (older_request.req_dttm, older_request.swp_req_id, older_item.item_seq) <
                                   (request.req_dttm, request.swp_req_id, request_item.item_seq)
                           ) AS oldest_pending
                    FROM bcm_swp_req_item_l request_item
                    JOIN bcm_swp_req_l request ON request.swp_req_id = request_item.swp_req_id
                    WHERE request_item.swp_req_item_id IN (:requestItemIds)
                    ORDER BY request.swp_req_id, request_item.swp_req_item_id
                    FOR UPDATE OF request, request_item
                    """.trimIndent(),
                    mapOf("requestItemIds" to items.map { it.sweepRequestItemId }),
                ).associateBy { it.getValue("swp_req_item_id") as String }
        return items
            .map { item ->
                val row = rows[item.sweepRequestItemId] ?: throw ConflictException("sweepRequestItem", item.sweepRequestItemId)
                val valid =
                    row["ntwk_cd"] == execution.network &&
                        row["tkn_smbl"] == execution.symbol &&
                        row["acnt_id"] == item.accountId &&
                        row["swp_req_item_stcd"] == "PENDING" &&
                        row["swp_req_stcd"] in setOf("ACCEPTED", "BLOCKED", "PROCESSING", "PARTIAL") &&
                        row["oldest_pending"] == true
                if (!valid) throw ConflictException("sweepRequestItem", item.sweepRequestItemId)
                row.getValue("swp_req_id") as String
            }.toSet()
    }

    private fun markRequestItemsProcessing(
        items: List<SweepItem>,
        requestIds: Set<String>,
    ) {
        items.forEach { item ->
            val updated =
                jdbc.update(
                    """
                    UPDATE bcm_swp_req_item_l
                    SET swp_req_item_stcd = 'PROCESSING',
                        last_fail_cd = NULL,
                        last_chng_empno = :employeeNo,
                        last_chng_brcd = :branchCode
                    WHERE swp_req_item_id = :requestItemId AND swp_req_item_stcd = 'PENDING'
                    """.trimIndent(),
                    itemParameters(item),
                )
            if (updated != 1) throw ConflictException("sweepRequestItem", item.sweepRequestItemId)
        }
        jdbc.update(
            """
            UPDATE bcm_swp_req_l
            SET swp_req_stcd = 'PROCESSING',
                fnsh_dttm = NULL,
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            WHERE swp_req_id IN (:requestIds)
              AND swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'PARTIAL')
            """.trimIndent(),
            mapOf(
                "requestIds" to requestIds,
                "employeeNo" to SystemAudit.EMPNO,
                "branchCode" to SystemAudit.BRCD,
            ),
        )
    }

    private fun updateRequestItemAfterReconciliation(
        executionId: String,
        item: SweepItemReconciliation,
    ) {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_swp_req_item_l request_item
                SET swp_req_item_stcd = :requestItemStatus,
                    last_fail_cd = :failureCode,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                FROM bcm_swp_item_l execution_item
                WHERE execution_item.swp_exec_id = :executionId
                  AND execution_item.item_seq = :sequence
                  AND execution_item.swp_req_item_id = request_item.swp_req_item_id
                  AND request_item.swp_req_item_stcd = 'PROCESSING'
                """.trimIndent(),
                lifecycleParameters(executionId) +
                    mapOf(
                        "sequence" to item.sequence,
                        "requestItemStatus" to
                            if (item.requestCompleted) "COMPLETED" else "PENDING",
                        "failureCode" to item.failureCode,
                    ),
            )
        if (updated != 1) throw ConflictException("sweepRequestItem", "$executionId:${item.sequence}")
    }

    private fun refreshRequestStatuses(
        executionId: String,
        finishedAt: String,
    ) {
        jdbc.update(
            """
            UPDATE bcm_swp_req_l request
            SET swp_req_stcd = status.next_status,
                fnsh_dttm = CASE WHEN status.next_status = 'COMPLETED' THEN :finishedAt ELSE NULL END,
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            FROM (
              SELECT request_item.swp_req_id,
                     CASE
                       WHEN bool_and(request_item.swp_req_item_stcd = 'COMPLETED') THEN 'COMPLETED'
                       WHEN bool_or(request_item.swp_req_item_stcd = 'PROCESSING') THEN 'PROCESSING'
                       WHEN bool_or(request_item.swp_req_item_stcd = 'COMPLETED') THEN 'PARTIAL'
                       ELSE 'ACCEPTED'
                     END AS next_status
              FROM bcm_swp_req_item_l request_item
              WHERE request_item.swp_req_id IN (
                SELECT DISTINCT linked.swp_req_id
                FROM bcm_swp_item_l execution_item
                JOIN bcm_swp_req_item_l linked
                  ON linked.swp_req_item_id = execution_item.swp_req_item_id
                WHERE execution_item.swp_exec_id = :executionId
              )
              GROUP BY request_item.swp_req_id
            ) status
            WHERE request.swp_req_id = status.swp_req_id
            """.trimIndent(),
            lifecycleParameters(executionId) + mapOf("finishedAt" to finishedAt),
        )
    }

    private fun insertExecution(execution: SweepExecution) {
        jdbc.update(
            """
            INSERT INTO bcm_swp_exec_l
              (swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id, swp_ctrt_addr,
               plcy_vrsn_id, plcy_snps_hash, ctrt_vrsn_id, ctrt_evdc_id,
               swp_exec_stcd, item_cnt, req_tot_amt, actl_tot_amt, gasless_yn, vndr_tx_id, tx_hash,
               req_dttm, fnsh_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:executionId, :externalTransactionId, :requestHash, :network, :symbol, :operatorAccountId,
               :sweepContractAddress, :policyVersionId, :policySnapshotHash, :contractVersionId, :contractEvidenceId,
               :status, :itemCount, :requestedTotalAmount, :actualTotalAmount, :gasless,
               :vendorTransactionId, :transactionHash, :requestedAt, :finishedAt,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            executionParameters(execution),
        )
    }

    private fun insertItem(item: SweepItem) {
        jdbc.update(
            """
            INSERT INTO bcm_swp_item_l
              (swp_exec_id, item_seq, swp_req_item_id, acnt_id, src_addr, req_amt, actl_amt,
               swp_item_stcd, fail_cd, log_idx,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:executionId, :sequence, :requestItemId, :accountId, :sourceAddress, :requestedAmount, :actualAmount, :status,
               :failureCode, :logIndex, :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            itemParameters(item),
        )
    }

    private fun executionParameters(execution: SweepExecution): Map<String, Any?> =
        mapOf(
            "executionId" to execution.executionId,
            "externalTransactionId" to execution.externalTransactionId,
            "requestHash" to execution.requestHash,
            "network" to execution.network,
            "symbol" to execution.symbol,
            "operatorAccountId" to execution.operatorAccountId,
            "sweepContractAddress" to execution.sweepContractAddress,
            "status" to execution.status.name,
            "itemCount" to execution.itemCount,
            "requestedTotalAmount" to BigDecimal(execution.requestedTotalAmount),
            "actualTotalAmount" to execution.actualTotalAmount?.let(::BigDecimal),
            "gasless" to if (execution.gasless) "Y" else "N",
            "vendorTransactionId" to execution.vendorTransactionId,
            "transactionHash" to execution.transactionHash,
            "requestedAt" to execution.requestedAt,
            "finishedAt" to execution.finishedAt,
            "policyVersionId" to execution.policyVersionId,
            "policySnapshotHash" to execution.policySnapshotHash,
            "contractVersionId" to execution.contractVersionId,
            "contractEvidenceId" to execution.contractEvidenceId,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private fun itemParameters(item: SweepItem): Map<String, Any?> =
        mapOf(
            "executionId" to item.executionId,
            "sequence" to item.sequence,
            "requestItemId" to item.sweepRequestItemId,
            "accountId" to item.accountId,
            "sourceAddress" to item.sourceAddress,
            "requestedAmount" to BigDecimal(item.requestedAmount),
            "actualAmount" to item.actualAmount?.let(::BigDecimal),
            "status" to item.status.name,
            "failureCode" to item.failureCode,
            "logIndex" to item.logIndex,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private fun lifecycleParameters(executionId: String): Map<String, Any?> =
        mapOf(
            "executionId" to executionId,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private fun required(executionId: String): SweepExecution =
        findById(executionId) ?: throw ResourceNotFoundException("sweepExecution", executionId)

    private companion object {
        const val EXECUTION_COLUMNS =
            """SELECT swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id, swp_ctrt_addr,
                      plcy_vrsn_id, plcy_snps_hash, ctrt_vrsn_id, ctrt_evdc_id,
                      swp_exec_stcd, item_cnt, req_tot_amt, actl_tot_amt, gasless_yn, vndr_tx_id, tx_hash,
                      req_dttm, fnsh_dttm
               FROM bcm_swp_exec_l"""
        const val ITEM_COLUMNS =
            """SELECT swp_exec_id, item_seq, swp_req_item_id, acnt_id, src_addr, req_amt, actl_amt,
                      swp_item_stcd, fail_cd, log_idx
               FROM bcm_swp_item_l"""
        const val ITEM_WITH_REQUEST_COLUMNS =
            """SELECT item.swp_exec_id, item.item_seq, request_item.swp_req_id, item.swp_req_item_id,
                      item.acnt_id, item.src_addr, item.req_amt, item.actl_amt,
                      item.swp_item_stcd, item.fail_cd, item.log_idx
               FROM bcm_swp_item_l item
               JOIN bcm_swp_req_item_l request_item
                 ON request_item.swp_req_item_id = item.swp_req_item_id"""
        val EXECUTION_MAPPER =
            RowMapper { rs, _ ->
                SweepExecution(
                    executionId = rs.getString("swp_exec_id"),
                    externalTransactionId = rs.getString("ext_tx_id"),
                    requestHash = rs.getString("req_hash"),
                    network = rs.getString("ntwk_cd"),
                    symbol = rs.getString("tkn_smbl"),
                    operatorAccountId = rs.getString("opr_acnt_id"),
                    sweepContractAddress = rs.getString("swp_ctrt_addr"),
                    status = SweepExecutionStatus.valueOf(rs.getString("swp_exec_stcd")),
                    itemCount = rs.getInt("item_cnt"),
                    requestedTotalAmount = rs.getBigDecimal("req_tot_amt").stripTrailingZeros().toPlainString(),
                    actualTotalAmount = rs.getBigDecimal("actl_tot_amt")?.stripTrailingZeros()?.toPlainString(),
                    gasless = rs.getString("gasless_yn") == "Y",
                    vendorTransactionId = rs.getString("vndr_tx_id"),
                    transactionHash = rs.getString("tx_hash"),
                    requestedAt = rs.getString("req_dttm"),
                    finishedAt = rs.getString("fnsh_dttm"),
                    policyVersionId = rs.getString("plcy_vrsn_id"),
                    policySnapshotHash = rs.getString("plcy_snps_hash"),
                    contractVersionId = rs.getString("ctrt_vrsn_id"),
                    contractEvidenceId = rs.getString("ctrt_evdc_id"),
                )
            }
        val ITEM_MAPPER =
            RowMapper { rs, _ ->
                SweepItem(
                    executionId = rs.getString("swp_exec_id"),
                    sequence = rs.getInt("item_seq"),
                    sweepRequestId = rs.getString("swp_req_id"),
                    sweepRequestItemId = rs.getString("swp_req_item_id"),
                    accountId = rs.getString("acnt_id"),
                    sourceAddress = rs.getString("src_addr"),
                    requestedAmount = rs.getBigDecimal("req_amt").stripTrailingZeros().toPlainString(),
                    actualAmount = rs.getBigDecimal("actl_amt")?.stripTrailingZeros()?.toPlainString(),
                    status = SweepItemStatus.valueOf(rs.getString("swp_item_stcd")),
                    failureCode = rs.getString("fail_cd"),
                    logIndex = rs.getObject("log_idx")?.let { rs.getInt("log_idx") },
                )
            }
    }
}
