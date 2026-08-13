package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
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
            "$ITEM_COLUMNS WHERE swp_exec_id = :executionId ORDER BY item_seq",
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

    override fun markSubmitting(executionId: String): SweepExecution {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_swp_exec_l
                SET swp_exec_stcd = 'SUBMITTING',
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE swp_exec_id = :executionId AND swp_exec_stcd IN ('READY', 'SUBMITTING')
                """.trimIndent(),
                lifecycleParameters(executionId),
            )
        if (updated != 1) throw ConflictException("sweepExecution", executionId)
        return required(executionId)
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
        return required(executionId)
    }

    @Transactional
    override fun markFailedAndRelease(
        executionId: String,
        finishedAt: String,
    ): SweepExecution {
        jdbc.update(
            """
            UPDATE bcm_swp_item_l
            SET swp_item_stcd = 'RETRY',
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            WHERE swp_exec_id = :executionId AND swp_item_stcd = 'READY'
            """.trimIndent(),
            lifecycleParameters(executionId),
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
        return required(executionId)
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

    private fun insertExecution(execution: SweepExecution) {
        jdbc.update(
            """
            INSERT INTO bcm_swp_exec_l
              (swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id, swp_ctrt_addr,
               swp_exec_stcd, item_cnt, req_tot_amt, actl_tot_amt, gasless_yn, vndr_tx_id, tx_hash,
               req_dttm, fnsh_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:executionId, :externalTransactionId, :requestHash, :network, :symbol, :operatorAccountId,
               :sweepContractAddress, :status, :itemCount, :requestedTotalAmount, :actualTotalAmount, :gasless,
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
              (swp_exec_id, item_seq, acnt_id, src_addr, req_amt, actl_amt, swp_item_stcd, fail_cd, log_idx,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:executionId, :sequence, :accountId, :sourceAddress, :requestedAmount, :actualAmount, :status,
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
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private fun itemParameters(item: SweepItem): Map<String, Any?> =
        mapOf(
            "executionId" to item.executionId,
            "sequence" to item.sequence,
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
                      swp_exec_stcd, item_cnt, req_tot_amt, actl_tot_amt, gasless_yn, vndr_tx_id, tx_hash,
                      req_dttm, fnsh_dttm
               FROM bcm_swp_exec_l"""
        const val ITEM_COLUMNS =
            """SELECT swp_exec_id, item_seq, acnt_id, src_addr, req_amt, actl_amt, swp_item_stcd, fail_cd, log_idx
               FROM bcm_swp_item_l"""
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
                )
            }
        val ITEM_MAPPER =
            RowMapper { rs, _ ->
                SweepItem(
                    executionId = rs.getString("swp_exec_id"),
                    sequence = rs.getInt("item_seq"),
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
