package com.whatto.bcm.infra.persistence.submission

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.submission.PendingSubmissionCheck
import com.whatto.bcm.domain.submission.PendingSubmissionRecoveryRepository
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Repository
class SubmissionJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : SubmissionRecordRepository,
    PendingSubmissionRecoveryRepository {
    @Transactional
    override fun insert(record: SubmissionRecord): SubmissionRecord {
        if (record.transactionType == SubmissionTransactionType.WITHDRAWAL) {
            requireExecutionGateOpen(record.network)
        }
        try {
            jdbc.update(
                """
                INSERT INTO bcm_sbmt_l
                  (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, claim_id, claim_exp_dttm,
                   tx_dvcd, vndr_tx_id, swp_exec_id,
                   snd_acnt_id, rcv_dvcd, rcv_vl, ntwk_cd, tkn_smbl, trsf_amt,
                   call_data, req_dttm, rsp_dttm,
                   vndr_wlt_id, vndr_ast_id, base_amt, dcml_cnt,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:externalTransactionId, :requestHash, :hashVersion, :status, :claimId, :claimExpiresAt,
                   :transactionType,
                   :vendorTransactionId, :sweepExecutionId, :senderAccountId, :recipientType, :recipientValue,
                   :network, :symbol, :amount, :callData, :requestedAt, :respondedAt,
                   :vendorWalletId, :vendorAssetId, :amountBaseUnits, :decimals,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                parameters(record),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("submission", record.externalTransactionId, exception)
        }
        return record
    }

    @Transactional
    override fun tryClaimRequested(
        externalTransactionId: String,
        claimId: String,
        claimExpiresAt: String,
        now: String,
    ): SubmissionRecord? {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_sbmt_l
                SET claim_id = :claimId, claim_exp_dttm = :claimExpiresAt,
                    last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                WHERE ext_tx_id = :externalTransactionId
                  AND sbmt_stcd = 'REQUESTED'
                  AND (claim_id IS NULL OR claim_exp_dttm IS NULL OR claim_exp_dttm <= :now)
                """.trimIndent(),
                mapOf(
                    "externalTransactionId" to externalTransactionId,
                    "claimId" to claimId,
                    "claimExpiresAt" to claimExpiresAt,
                    "now" to now,
                    "employeeNo" to SystemAudit.EMPNO,
                    "branchCode" to SystemAudit.BRCD,
                ),
            )
        return if (updated == 1) required(externalTransactionId) else null
    }

    @Transactional
    override fun tryClaim(
        externalTransactionId: String,
        claimId: String,
        claimExpiresAt: String,
        now: String,
    ): SubmissionRecord? {
        val current = findByExternalTransactionId(externalTransactionId) ?: return null
        val withdrawalGateStatus =
            if (current.transactionType == SubmissionTransactionType.WITHDRAWAL) {
                lockExecutionGate(current.network)
            } else {
                null
            }
        val parameters =
            lifecycleParameters(externalTransactionId) +
                mapOf("claimId" to claimId, "claimExpiresAt" to claimExpiresAt, "now" to now)
        val requestedUpdated =
            jdbc.update(
                """
                UPDATE bcm_sbmt_l
                SET claim_id = :claimId, claim_exp_dttm = :claimExpiresAt,
                    last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                WHERE ext_tx_id = :externalTransactionId
                  AND sbmt_stcd = 'REQUESTED'
                  AND (claim_id IS NULL OR claim_exp_dttm IS NULL OR claim_exp_dttm <= :now)
                """.trimIndent(),
                parameters,
            )
        if (requestedUpdated == 1) return required(externalTransactionId)

        val latest = findByExternalTransactionId(externalTransactionId)
        if (latest?.status != SubmissionStatus.FAILED) return null
        if (latest.transactionType == SubmissionTransactionType.WITHDRAWAL && withdrawalGateStatus == "STOPPED") {
            throw ConflictException("executionGate", "${latest.network}:WITHDRAWAL")
        }
        val failedUpdated =
            jdbc.update(
                """
                UPDATE bcm_sbmt_l
                SET sbmt_stcd = 'REQUESTED', claim_id = :claimId, claim_exp_dttm = :claimExpiresAt,
                    rsp_dttm = NULL,
                    last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                WHERE ext_tx_id = :externalTransactionId
                  AND sbmt_stcd = 'FAILED'
                  AND (claim_id IS NULL OR claim_exp_dttm IS NULL OR claim_exp_dttm <= :now)
                """.trimIndent(),
                parameters,
            )
        return if (failedUpdated == 1) required(externalTransactionId) else null
    }

    override fun findByExternalTransactionId(externalTransactionId: String): SubmissionRecord? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE ext_tx_id = :externalTransactionId",
                mapOf("externalTransactionId" to externalTransactionId),
                ROW_MAPPER,
            ).firstOrNull()

    override fun reserveRequestedForRecovery(
        now: String,
        requestedBefore: String,
        checkedBefore: String,
        limit: Int,
    ): List<PendingSubmissionCheck> {
        require(limit > 0) { "recovery limit must be positive" }
        return jdbc.query(
            """
            WITH candidates AS (
              SELECT ext_tx_id
              FROM bcm_sbmt_l
              WHERE sbmt_stcd = 'REQUESTED'
                AND req_dttm <= :requestedBefore
                AND (claim_id IS NULL OR claim_exp_dttm IS NULL OR claim_exp_dttm <= :now)
                AND (last_chck_dttm IS NULL OR last_chck_dttm <= :checkedBefore)
              ORDER BY COALESCE(last_chck_dttm, req_dttm), req_dttm, ext_tx_id
              LIMIT :limit
              FOR UPDATE SKIP LOCKED
            ), reserved AS (
              UPDATE bcm_sbmt_l AS submission
              SET last_chck_dttm = :now,
                  chck_cnt = submission.chck_cnt + 1,
                  last_chng_empno = :employeeNo,
                  last_chng_brcd = :branchCode
              FROM candidates
              WHERE submission.ext_tx_id = candidates.ext_tx_id
              RETURNING submission.ext_tx_id, submission.req_dttm,
                        submission.last_chck_dttm, submission.chck_cnt
            )
            SELECT ext_tx_id, last_chck_dttm, chck_cnt
            FROM reserved
            ORDER BY req_dttm, ext_tx_id
            """.trimIndent(),
            mapOf(
                "now" to now,
                "requestedBefore" to requestedBefore,
                "checkedBefore" to checkedBefore,
                "limit" to limit,
                "employeeNo" to SystemAudit.EMPNO,
                "branchCode" to SystemAudit.BRCD,
            ),
        ) { rs, _ ->
            PendingSubmissionCheck(
                externalTransactionId = rs.getString("ext_tx_id"),
                checkedAt = rs.getString("last_chck_dttm"),
                checkCount = rs.getInt("chck_cnt"),
            )
        }
    }

    override fun markRecoveredSubmitted(
        externalTransactionId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ) {
        markSubmitted(externalTransactionId, vendorTransactionId, respondedAt)
    }

    override fun markSubmitted(
        externalTransactionId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ): SubmissionRecord {
        try {
            jdbc.update(
                """
                UPDATE bcm_sbmt_l
                SET sbmt_stcd = 'SUBMITTED', vndr_tx_id = :vendorTransactionId, rsp_dttm = :respondedAt,
                    claim_id = NULL, claim_exp_dttm = NULL,
                    last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                WHERE ext_tx_id = :externalTransactionId AND sbmt_stcd IN ('REQUESTED', 'FAILED')
                """.trimIndent(),
                lifecycleParameters(externalTransactionId) +
                    mapOf("vendorTransactionId" to vendorTransactionId, "respondedAt" to respondedAt),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("vendorTransaction", vendorTransactionId, exception)
        }
        val current = required(externalTransactionId)
        if (current.status != SubmissionStatus.SUBMITTED || current.vendorTransactionId != vendorTransactionId) {
            throw ConflictException("submission", externalTransactionId)
        }
        return current
    }

    override fun markSubmittedByClaim(
        externalTransactionId: String,
        claimId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ): SubmissionRecord {
        try {
            jdbc.update(
                """
                UPDATE bcm_sbmt_l
                SET sbmt_stcd = 'SUBMITTED', vndr_tx_id = :vendorTransactionId, rsp_dttm = :respondedAt,
                    claim_id = NULL, claim_exp_dttm = NULL,
                    last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                WHERE ext_tx_id = :externalTransactionId AND sbmt_stcd = 'REQUESTED'
                  AND claim_id = :claimId
                """.trimIndent(),
                lifecycleParameters(externalTransactionId) +
                    mapOf(
                        "claimId" to claimId,
                        "vendorTransactionId" to vendorTransactionId,
                        "respondedAt" to respondedAt,
                    ),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("vendorTransaction", vendorTransactionId, exception)
        }
        val current = required(externalTransactionId)
        if (current.status != SubmissionStatus.SUBMITTED || current.vendorTransactionId != vendorTransactionId) {
            throw ConflictException("submission", externalTransactionId)
        }
        return current
    }

    override fun markFailedByClaim(
        externalTransactionId: String,
        claimId: String,
        respondedAt: String,
    ): SubmissionRecord {
        jdbc.update(
            """
            UPDATE bcm_sbmt_l
            SET sbmt_stcd = 'FAILED', rsp_dttm = :respondedAt,
                claim_id = NULL, claim_exp_dttm = NULL,
                last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
            WHERE ext_tx_id = :externalTransactionId AND sbmt_stcd = 'REQUESTED'
              AND claim_id = :claimId
            """.trimIndent(),
            lifecycleParameters(externalTransactionId) + mapOf("claimId" to claimId, "respondedAt" to respondedAt),
        )
        val current = required(externalTransactionId)
        if (current.status != SubmissionStatus.FAILED) {
            throw ConflictException("submission", externalTransactionId)
        }
        return current
    }

    override fun existsUnresolvedWithSameCanonical(
        excludingExternalTransactionId: String,
        canonical: SubmissionVendorCanonical,
        recipientValue: String,
    ): Boolean =
        jdbc
            .query(
                """
                SELECT 1
                  FROM bcm_sbmt_l s
                  LEFT JOIN bcm_tx_l t ON t.vndr_tx_id = s.vndr_tx_id
                 WHERE s.ext_tx_id <> :excluded
                   AND s.sbmt_stcd IN ('REQUESTED', 'SUBMITTED')
                   AND s.vndr_wlt_id = :vendorWalletId
                   AND s.vndr_ast_id = :vendorAssetId
                   AND s.base_amt = :amountBaseUnits
                   -- 이 조회는 '배제하지 못하면 붙이지 않는다'의 입력이라 놓치면 안전조건이 다시 열린다.
                   -- 그래서 EVM checksum 표기 차이는 같은 주소로 본다. 다만 **EVM 형태일 때만** 그렇게 한다 —
                   -- base58(Solana)은 대소문자가 값의 일부라 case-fold하면 서로 다른 주소가 같아져 정상 건이 부당하게 막힌다.
                   AND (
                       s.rcv_vl = :recipientValue
                       OR (
                           :recipientValue ~ '^0x[0-9a-fA-F]{40}$'
                           AND lower(s.rcv_vl) = lower(:recipientValue)
                       )
                   )
                   -- hash를 아직 모르는 제출만 본다 — 아는 제출은 (ntwk_cd, tx_hash) 후보 조회가 이미 가른다.
                   AND (s.vndr_tx_id IS NULL OR t.tx_hash IS NULL)
                 LIMIT 1
                """.trimIndent(),
                mapOf(
                    "excluded" to excludingExternalTransactionId,
                    "vendorWalletId" to canonical.vendorWalletId,
                    "vendorAssetId" to canonical.vendorAssetId,
                    "amountBaseUnits" to canonical.amountBaseUnits,
                    "recipientValue" to recipientValue,
                ),
            ) { _, _ -> true }
            .isNotEmpty()

    override fun findByVendorTransactionId(vendorTransactionId: String): SubmissionRecord? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE vndr_tx_id = :vendorTransactionId",
                mapOf("vendorTransactionId" to vendorTransactionId),
                ROW_MAPPER,
            ).firstOrNull()

    private fun parameters(record: SubmissionRecord): Map<String, Any?> =
        mapOf(
            "externalTransactionId" to record.externalTransactionId,
            "requestHash" to record.requestHash,
            "hashVersion" to record.hashVersion,
            "status" to record.status.name,
            "claimId" to record.claimId,
            "claimExpiresAt" to record.claimExpiresAt,
            "transactionType" to record.transactionType.name,
            "vendorTransactionId" to record.vendorTransactionId,
            "sweepExecutionId" to record.sweepExecutionId,
            "senderAccountId" to record.senderAccountId,
            "recipientType" to record.recipientType.name,
            "recipientValue" to record.recipientValue,
            "network" to record.network,
            "symbol" to record.symbol,
            "amount" to BigDecimal(record.amount),
            "callData" to record.callData,
            "requestedAt" to record.requestedAt,
            "respondedAt" to record.respondedAt,
            "vendorWalletId" to record.vendorCanonical?.vendorWalletId,
            "vendorAssetId" to record.vendorCanonical?.vendorAssetId,
            "amountBaseUnits" to record.vendorCanonical?.amountBaseUnits,
            "decimals" to record.vendorCanonical?.decimals,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private fun lifecycleParameters(externalTransactionId: String): Map<String, Any?> =
        mapOf(
            "externalTransactionId" to externalTransactionId,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private fun requireExecutionGateOpen(network: String) {
        if (lockExecutionGate(network) == "STOPPED") {
            throw ConflictException("executionGate", "$network:WITHDRAWAL")
        }
    }

    private fun lockExecutionGate(network: String): String? {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) IS NULL",
            mapOf("key" to "BCM:EXECUTION_GATE:$network:WITHDRAWAL"),
            Boolean::class.java,
        )
        val status =
            jdbc
                .queryForList(
                    """
                    SELECT gate_stcd
                      FROM bcm_exec_gate_evt_l
                     WHERE ntwk_cd = :network AND gate_dvcd = 'WITHDRAWAL'
                     ORDER BY evt_seq DESC
                     LIMIT 1
                    """.trimIndent(),
                    mapOf("network" to network),
                    String::class.java,
                ).firstOrNull()
        return status
    }

    private fun required(externalTransactionId: String): SubmissionRecord =
        checkNotNull(findByExternalTransactionId(externalTransactionId)) {
            "submission disappeared during lifecycle update: externalTransactionId=$externalTransactionId"
        }

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs, _ ->
                SubmissionRecord(
                    externalTransactionId = rs.getString("ext_tx_id"),
                    requestHash = rs.getString("req_hash"),
                    hashVersion = rs.getString("hash_vrsn"),
                    status = SubmissionStatus.valueOf(rs.getString("sbmt_stcd")),
                    claimId = rs.getString("claim_id"),
                    claimExpiresAt = rs.getString("claim_exp_dttm"),
                    transactionType = SubmissionTransactionType.valueOf(rs.getString("tx_dvcd")),
                    vendorTransactionId = rs.getString("vndr_tx_id"),
                    sweepExecutionId = rs.getString("swp_exec_id"),
                    senderAccountId = rs.getString("snd_acnt_id"),
                    recipientType = SubmissionRecipientType.valueOf(rs.getString("rcv_dvcd")),
                    recipientValue = rs.getString("rcv_vl"),
                    network = rs.getString("ntwk_cd"),
                    symbol = rs.getString("tkn_smbl"),
                    amount = rs.getBigDecimal("trsf_amt").stripTrailingZeros().toPlainString(),
                    requestedAt = rs.getString("req_dttm"),
                    respondedAt = rs.getString("rsp_dttm"),
                    callData = rs.getString("call_data"),
                    vendorCanonical =
                        rs.getString("vndr_wlt_id")?.let { walletId ->
                            SubmissionVendorCanonical(
                                vendorWalletId = walletId,
                                vendorAssetId = rs.getString("vndr_ast_id"),
                                amountBaseUnits = rs.getString("base_amt"),
                                decimals = rs.getInt("dcml_cnt"),
                            )
                        },
                )
            }

        val SELECT_COLUMNS =
            """
            SELECT ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, claim_id, claim_exp_dttm,
                   tx_dvcd, vndr_tx_id, swp_exec_id,
                   snd_acnt_id, rcv_dvcd, rcv_vl, ntwk_cd, tkn_smbl, trsf_amt,
                   call_data, req_dttm, rsp_dttm,
                   vndr_wlt_id, vndr_ast_id, base_amt, dcml_cnt
            FROM bcm_sbmt_l
            """.trimIndent()
    }
}
