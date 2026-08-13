package com.whatto.bcm.infra.persistence.tx

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.StallCandidateRepository
import com.whatto.bcm.domain.tx.TxReconciliationRecord
import com.whatto.bcm.domain.tx.TxReconciliationRepository
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** bcm_tx_l 파생 쿼리 — 어댑터 내부 전용 */
interface TxCrudRepository : CrudRepository<TxEntity, String> {
    fun findByExtTxId(extTxId: String): TxEntity?

    fun findByActvTxId(actvTxId: String): TxEntity?
}

@Repository
class TxJdbcAdapter(
    private val crud: TxCrudRepository,
    private val template: JdbcAggregateTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
) : TxRecordRepository,
    StallCandidateRepository,
    TxReconciliationRepository {
    private val rowMapper =
        RowMapper { rs, _ ->
            TxRecord(
                vendorTxId = rs.getString("vndr_tx_id"),
                activeVendorTxId = rs.getString("actv_tx_id"),
                externalTxId = rs.getString("ext_tx_id"),
                accountId = rs.getString("acnt_id"),
                network = rs.getString("ntwk_cd"),
                symbol = rs.getString("tkn_smbl"),
                transactionHash = rs.getString("tx_hash"),
                lastPublishedStatus = TxStatus.valueOf(rs.getString("last_pub_stcd")),
                confirmationCount = rs.getInt("cnfm_cnt"),
                vendorSubStatus = rs.getString("vndr_sub_stcd"),
                vendorNetworkStatus = rs.getString("vndr_ntwk_stcd"),
                stallAlertedAt = rs.getString("stall_alrt_dttm"),
                firstDetectedAt = rs.getString("frst_dtct_dttm"),
                lastChangedAt = rs.getString("last_chng_dttm"),
            )
        }
    private val reconciliationRowMapper =
        RowMapper { rs, rowNumber ->
            TxReconciliationRecord(
                record = rowMapper.mapRow(rs, rowNumber),
                submissionType = rs.getString("tx_dvcd")?.let(SubmissionTransactionType::valueOf),
                sweepExecutionId = rs.getString("swp_exec_id"),
            )
        }

    // 문자열 PK 는 신규 판별이 안 돼 save() 가 UPDATE 를 시도한다 — 명시적 insert/update 분리
    override fun insert(txRecord: TxRecord): TxRecord =
        try {
            template.insert(TxEntity.from(txRecord)).toDomain()
        } catch (exception: DuplicateKeyException) {
            throw ConflictException(
                resource = "transaction",
                key = txRecord.externalTxId ?: txRecord.vendorTxId,
                cause = exception,
            )
        }

    override fun update(txRecord: TxRecord): TxRecord {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_tx_l
                   SET ext_tx_id = :externalTxId,
                       acnt_id = :accountId,
                       ntwk_cd = :network,
                       tkn_smbl = :symbol,
                       tx_hash = COALESCE(tx_hash, :transactionHash),
                       last_pub_stcd = :lastPublishedStatus,
                       cnfm_cnt = GREATEST(cnfm_cnt, :confirmationCount),
                       vndr_sub_stcd = :vendorSubStatus,
                       vndr_ntwk_stcd = :vendorNetworkStatus,
                       stall_alrt_dttm = :stallAlertedAt,
                       last_chng_dttm = GREATEST(last_chng_dttm, :lastChangedAt),
                       last_chng_empno = :employeeNo,
                       last_chng_brcd = :branchCode
                 WHERE vndr_tx_id = :vendorTxId
                   AND actv_tx_id = :activeVendorTxId
                   AND (
                     tx_hash IS NULL
                     OR CAST(:transactionHash AS VARCHAR) IS NULL
                     OR tx_hash = CAST(:transactionHash AS VARCHAR)
                   )
                """.trimIndent(),
                txRecord.parameters(),
            )
        if (updated != 1) {
            val current =
                findByVendorTxId(txRecord.vendorTxId)
                    ?: throw ResourceNotFoundException("transaction", txRecord.vendorTxId)
            throw ConflictException(
                resource = "transactionState",
                key = current.vendorTxId,
            )
        }
        return requireNotNull(findByVendorTxId(txRecord.vendorTxId))
    }

    override fun updatePhysicalWinner(
        txRecord: TxRecord,
        previousActiveVendorTxId: String,
    ): TxRecord {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_tx_l
                   SET actv_tx_id = :activeVendorTxId,
                       tx_hash = :transactionHash,
                       last_pub_stcd = :lastPublishedStatus,
                       cnfm_cnt = :confirmationCount,
                       vndr_sub_stcd = :vendorSubStatus,
                       vndr_ntwk_stcd = :vendorNetworkStatus,
                       stall_alrt_dttm = NULL,
                       last_chng_dttm = GREATEST(last_chng_dttm, :lastChangedAt),
                       last_chng_empno = :employeeNo,
                       last_chng_brcd = :branchCode
                 WHERE vndr_tx_id = :vendorTxId
                   AND actv_tx_id = :previousActiveVendorTxId
                   AND cnfm_cnt = 0
                """.trimIndent(),
                txRecord.parameters() + ("previousActiveVendorTxId" to previousActiveVendorTxId),
            )
        if (updated != 1) throw ConflictException("transactionWinner", txRecord.vendorTxId)
        return requireNotNull(findByVendorTxId(txRecord.vendorTxId))
    }

    override fun findByVendorTxId(vendorTxId: String): TxRecord? = crud.findByIdOrNull(vendorTxId)?.toDomain()

    override fun findByActiveVendorTxId(activeVendorTxId: String): TxRecord? = crud.findByActvTxId(activeVendorTxId)?.toDomain()

    override fun findByVendorTxIdForUpdate(vendorTxId: String): TxRecord? =
        jdbc
            .query(
                "$TX_COLUMNS FROM bcm_tx_l WHERE vndr_tx_id = :vendorTxId FOR UPDATE",
                mapOf("vendorTxId" to vendorTxId),
                rowMapper,
            ).firstOrNull()

    override fun findByActiveVendorTxIdForUpdate(activeVendorTxId: String): TxRecord? =
        jdbc
            .query(
                "$TX_COLUMNS FROM bcm_tx_l WHERE actv_tx_id = :activeVendorTxId FOR UPDATE",
                mapOf("activeVendorTxId" to activeVendorTxId),
                rowMapper,
            ).firstOrNull()

    override fun findByExternalTxId(externalTxId: String): TxRecord? = crud.findByExtTxId(externalTxId)?.toDomain()

    override fun findByPhysicalVendorTransactionId(vendorTransactionId: String): TxReconciliationRecord? =
        jdbc
            .query(
                """
                SELECT tx.*, submission.tx_dvcd, submission.swp_exec_id
                FROM bcm_tx_l tx
                LEFT JOIN bcm_sbmt_l submission ON submission.vndr_tx_id = tx.vndr_tx_id
                LEFT JOIN bcm_boost_l boost
                  ON boost.orig_tx_id = tx.vndr_tx_id
                 AND boost.new_tx_id = :vendorTransactionId
                WHERE tx.vndr_tx_id = :vendorTransactionId
                   OR tx.actv_tx_id = :vendorTransactionId
                   OR boost.new_tx_id = :vendorTransactionId
                """.trimIndent(),
                mapOf("vendorTransactionId" to vendorTransactionId),
                reconciliationRowMapper,
            ).firstOrNull()

    override fun findDetectedBetween(
        detectedAtOrAfter: String,
        detectedAtOrBefore: String,
    ): List<TxReconciliationRecord> =
        jdbc.query(
            """
            SELECT tx.*, submission.tx_dvcd, submission.swp_exec_id
            FROM bcm_tx_l tx
            LEFT JOIN bcm_sbmt_l submission ON submission.vndr_tx_id = tx.vndr_tx_id
            WHERE tx.frst_dtct_dttm >= :detectedAtOrAfter
              AND tx.frst_dtct_dttm <= :detectedAtOrBefore
            ORDER BY tx.frst_dtct_dttm, tx.vndr_tx_id
            """.trimIndent(),
            mapOf(
                "detectedAtOrAfter" to detectedAtOrAfter,
                "detectedAtOrBefore" to detectedAtOrBefore,
            ),
            reconciliationRowMapper,
        )

    override fun findPendingChangedAtOrBefore(
        changedAtOrBefore: String,
        limit: Int,
    ): List<TxReconciliationRecord> {
        require(limit > 0) { "reconciliation pending limit must be positive" }
        return jdbc.query(
            """
            SELECT tx.*, submission.tx_dvcd, submission.swp_exec_id
            FROM bcm_tx_l tx
            LEFT JOIN bcm_sbmt_l submission ON submission.vndr_tx_id = tx.vndr_tx_id
            WHERE tx.last_pub_stcd IN ('SUBMITTED', 'CONFIRMED')
              AND tx.last_chng_dttm <= :changedAtOrBefore
            ORDER BY tx.last_chng_dttm, tx.vndr_tx_id
            LIMIT :limit
            """.trimIndent(),
            mapOf("changedAtOrBefore" to changedAtOrBefore, "limit" to limit),
            reconciliationRowMapper,
        )
    }

    override fun findStallCandidates(
        changedBefore: String,
        limit: Int,
    ): List<StallCandidate> {
        require(limit > 0) { "stall candidate limit must be positive" }
        return jdbc.query(
            """
            SELECT candidate.*, submission.tx_dvcd, submission.swp_exec_id
            FROM (
              SELECT *
              FROM bcm_tx_l
              WHERE last_pub_stcd IN ('SUBMITTED', 'CONFIRMED')
                AND last_chng_dttm <= :changedBefore
                AND stall_alrt_dttm IS NULL
              ORDER BY last_chng_dttm, vndr_tx_id
              LIMIT :limit
            ) candidate
            LEFT JOIN bcm_sbmt_l submission ON submission.vndr_tx_id = candidate.vndr_tx_id
            ORDER BY candidate.last_chng_dttm, candidate.vndr_tx_id
            """.trimIndent(),
            mapOf("changedBefore" to changedBefore, "limit" to limit),
        ) { rs, rowNumber ->
            StallCandidate(
                record = rowMapper.mapRow(rs, rowNumber),
                submissionType = rs.getString("tx_dvcd")?.let(SubmissionTransactionType::valueOf),
                sweepExecutionId = rs.getString("swp_exec_id"),
            )
        }
    }

    override fun markStallAlertedIfAbsent(
        candidate: TxRecord,
        alertedAt: String,
    ): Boolean =
        jdbc.update(
            """
            UPDATE bcm_tx_l
            SET stall_alrt_dttm = :alertedAt,
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            WHERE vndr_tx_id = :vendorTxId
              AND actv_tx_id = :activeVendorTxId
              AND last_chng_dttm = :lastChangedAt
              AND stall_alrt_dttm IS NULL
              AND last_pub_stcd IN ('SUBMITTED', 'CONFIRMED')
            """.trimIndent(),
            mapOf(
                "vendorTxId" to candidate.vendorTxId,
                "activeVendorTxId" to candidate.activeVendorTxId,
                "lastChangedAt" to candidate.lastChangedAt,
                "alertedAt" to alertedAt,
                "employeeNo" to SystemAudit.EMPNO,
                "branchCode" to SystemAudit.BRCD,
            ),
        ) == 1

    private fun TxRecord.parameters(): Map<String, Any?> =
        mapOf(
            "vendorTxId" to vendorTxId,
            "activeVendorTxId" to activeVendorTxId,
            "externalTxId" to externalTxId,
            "accountId" to accountId,
            "network" to network,
            "symbol" to symbol,
            "transactionHash" to transactionHash,
            "lastPublishedStatus" to lastPublishedStatus.name,
            "confirmationCount" to confirmationCount,
            "vendorSubStatus" to vendorSubStatus,
            "vendorNetworkStatus" to vendorNetworkStatus,
            "stallAlertedAt" to stallAlertedAt,
            "lastChangedAt" to lastChangedAt,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private companion object {
        const val TX_COLUMNS =
            """SELECT vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
                      last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, stall_alrt_dttm,
                      frst_dtct_dttm, last_chng_dttm"""
    }
}
