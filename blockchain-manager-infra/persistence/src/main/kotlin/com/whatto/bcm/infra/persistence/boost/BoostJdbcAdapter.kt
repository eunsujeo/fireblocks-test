package com.whatto.bcm.infra.persistence.boost

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.tx.BoostAttempt
import com.whatto.bcm.domain.tx.BoostAttemptAcquisition
import com.whatto.bcm.domain.tx.BoostAttemptRepository
import com.whatto.bcm.domain.tx.BoostIntentRequest
import com.whatto.bcm.domain.tx.BoostPolicy
import com.whatto.bcm.domain.tx.BoostStatus
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorFeeLevel
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class BoostJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : BoostAttemptRepository {
    override fun acquire(
        candidate: TxRecord,
        request: BoostIntentRequest,
        maximumAttempts: Int,
        now: String,
    ): BoostAttemptAcquisition {
        require(maximumAttempts > 0) { "maximum boost attempts must be positive" }
        val lockedRoot = lockRoot(candidate.vendorTxId) ?: return BoostAttemptAcquisition.StaleCandidate
        if (lockedRoot.activeVendorTransactionId != candidate.activeVendorTxId ||
            lockedRoot.lastChangedAt != candidate.lastChangedAt ||
            lockedRoot.status !in BoostPolicy.rootStatuses
        ) {
            return BoostAttemptAcquisition.StaleCandidate
        }

        val open = findOpen(candidate.vendorTxId)
        if (open != null) {
            val claimExpiresAt = open.claimExpiresAt
            if (claimExpiresAt != null && claimExpiresAt > now) {
                return BoostAttemptAcquisition.InProgress
            }
            val updated =
                jdbc.update(
                    """
                    UPDATE bcm_boost_l
                    SET claim_id = :claimId,
                        claim_exp_dttm = :claimExpiresAt,
                        last_chng_empno = :employeeNo,
                        last_chng_brcd = :branchCode
                    WHERE orig_tx_id = :rootVendorTransactionId
                      AND try_seq = :trySequence
                      AND bst_stcd = 'REQUESTED'
                      AND (claim_exp_dttm IS NULL OR claim_exp_dttm <= :now)
                    """.trimIndent(),
                    lifecycleParameters(open.rootVendorTransactionId, open.trySequence) +
                        mapOf(
                            "claimId" to request.claimId,
                            "claimExpiresAt" to request.claimExpiresAt,
                            "now" to now,
                        ),
                )
            return if (updated == 1) {
                BoostAttemptAcquisition.Acquired(required(open.rootVendorTransactionId, open.trySequence), false)
            } else {
                BoostAttemptAcquisition.InProgress
            }
        }

        val nextSequence = nextSequence(candidate.vendorTxId)
        if (nextSequence > maximumAttempts) return BoostAttemptAcquisition.MaximumAttemptsReached
        try {
            jdbc.update(
                """
                INSERT INTO bcm_boost_l
                  (orig_tx_id, try_seq, ext_tx_id, bst_stcd, claim_id, claim_exp_dttm,
                   rplc_tx_id, rplc_tx_hash, fee_lvl, gasless_yn, new_tx_id, req_dttm, rsp_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:rootVendorTransactionId, :trySequence, :externalTransactionId, 'REQUESTED',
                   :claimId, :claimExpiresAt, :replacementVendorTransactionId, :replacementTransactionHash,
                   :feeLevel, :useGasless, NULL, :requestedAt, NULL,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                mapOf(
                    "rootVendorTransactionId" to candidate.vendorTxId,
                    "trySequence" to nextSequence,
                    "externalTransactionId" to request.externalTransactionId,
                    "claimId" to request.claimId,
                    "claimExpiresAt" to request.claimExpiresAt,
                    "replacementVendorTransactionId" to request.replacementVendorTransactionId,
                    "replacementTransactionHash" to request.replacementTransactionHash,
                    "feeLevel" to request.feeLevel.name,
                    "useGasless" to if (request.useGasless) "Y" else "N",
                    "requestedAt" to request.requestedAt,
                    "employeeNo" to SystemAudit.EMPNO,
                    "branchCode" to SystemAudit.BRCD,
                ),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("boost", request.externalTransactionId, exception)
        }
        return BoostAttemptAcquisition.Acquired(required(candidate.vendorTxId, nextSequence), true)
    }

    override fun markSubmittedByClaim(
        rootVendorTransactionId: String,
        trySequence: Int,
        claimId: String,
        newVendorTransactionId: String,
        respondedAt: String,
    ): BoostAttempt {
        val current = required(rootVendorTransactionId, trySequence)
        val updated =
            try {
                jdbc.update(
                    """
                    UPDATE bcm_boost_l
                    SET bst_stcd = 'SUBMITTED',
                        claim_id = NULL,
                        claim_exp_dttm = NULL,
                        new_tx_id = :newVendorTransactionId,
                        rsp_dttm = :respondedAt,
                        last_chng_empno = :employeeNo,
                        last_chng_brcd = :branchCode
                    WHERE orig_tx_id = :rootVendorTransactionId
                      AND try_seq = :trySequence
                      AND bst_stcd = 'REQUESTED'
                      AND claim_id = :claimId
                    """.trimIndent(),
                    lifecycleParameters(rootVendorTransactionId, trySequence) +
                        mapOf(
                            "claimId" to claimId,
                            "newVendorTransactionId" to newVendorTransactionId,
                            "respondedAt" to respondedAt,
                        ),
                )
            } catch (exception: DuplicateKeyException) {
                throw ConflictException("boostVendorTransaction", newVendorTransactionId, exception)
            }
        if (updated != 1) throw ConflictException("boost", "$rootVendorTransactionId:$trySequence")

        jdbc.update(
            """
            UPDATE bcm_tx_l
            SET actv_tx_id = :newVendorTransactionId,
                tx_hash = NULL,
                stall_alrt_dttm = NULL,
                last_chng_dttm = GREATEST(last_chng_dttm, :respondedAt),
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            WHERE vndr_tx_id = :rootVendorTransactionId
              AND actv_tx_id = :replacementVendorTransactionId
              AND cnfm_cnt = 0
              AND last_pub_stcd IN (:boostableRootStatuses)
            """.trimIndent(),
            mapOf(
                "rootVendorTransactionId" to rootVendorTransactionId,
                "replacementVendorTransactionId" to current.replacementVendorTransactionId,
                "newVendorTransactionId" to newVendorTransactionId,
                "respondedAt" to respondedAt,
                "boostableRootStatuses" to BoostPolicy.rootStatuses.map(TxStatus::name),
                "employeeNo" to SystemAudit.EMPNO,
                "branchCode" to SystemAudit.BRCD,
            ),
        )
        return required(rootVendorTransactionId, trySequence)
    }

    override fun markFailedByClaim(
        rootVendorTransactionId: String,
        trySequence: Int,
        claimId: String,
        respondedAt: String,
    ): BoostAttempt {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_boost_l
                SET bst_stcd = 'FAILED',
                    claim_id = NULL,
                    claim_exp_dttm = NULL,
                    rsp_dttm = :respondedAt,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE orig_tx_id = :rootVendorTransactionId
                  AND try_seq = :trySequence
                  AND bst_stcd = 'REQUESTED'
                  AND claim_id = :claimId
                """.trimIndent(),
                lifecycleParameters(rootVendorTransactionId, trySequence) +
                    mapOf("claimId" to claimId, "respondedAt" to respondedAt),
            )
        if (updated != 1) throw ConflictException("boost", "$rootVendorTransactionId:$trySequence")
        return required(rootVendorTransactionId, trySequence)
    }

    override fun findByExternalTransactionId(externalTransactionId: String): BoostAttempt? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE boost.ext_tx_id = :externalTransactionId",
                mapOf("externalTransactionId" to externalTransactionId),
                ROW_MAPPER,
            ).firstOrNull()

    override fun findByRootAndSequence(
        rootVendorTransactionId: String,
        trySequence: Int,
    ): BoostAttempt? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE boost.orig_tx_id = :rootVendorTransactionId AND boost.try_seq = :trySequence",
                mapOf("rootVendorTransactionId" to rootVendorTransactionId, "trySequence" to trySequence),
                ROW_MAPPER,
            ).firstOrNull()

    fun required(
        rootVendorTransactionId: String,
        trySequence: Int,
    ): BoostAttempt =
        checkNotNull(findByRootAndSequence(rootVendorTransactionId, trySequence)) {
            "boost attempt disappeared: rootVendorTransactionId=$rootVendorTransactionId trySequence=$trySequence"
        }

    private fun findOpen(rootVendorTransactionId: String): BoostAttempt? =
        jdbc
            .query(
                """
                $SELECT_COLUMNS
                WHERE boost.orig_tx_id = :rootVendorTransactionId
                  AND boost.bst_stcd = 'REQUESTED'
                ORDER BY boost.try_seq DESC
                LIMIT 1
                """.trimIndent(),
                mapOf("rootVendorTransactionId" to rootVendorTransactionId),
                ROW_MAPPER,
            ).firstOrNull()

    private fun nextSequence(rootVendorTransactionId: String): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COALESCE(MAX(try_seq), 0) + 1 FROM bcm_boost_l WHERE orig_tx_id = :rootVendorTransactionId",
                mapOf("rootVendorTransactionId" to rootVendorTransactionId),
                Int::class.java,
            ),
        )

    private fun lockRoot(rootVendorTransactionId: String): LockedRoot? =
        jdbc
            .query(
                """
                SELECT actv_tx_id, last_chng_dttm, last_pub_stcd
                FROM bcm_tx_l
                WHERE vndr_tx_id = :rootVendorTransactionId
                FOR UPDATE
                """.trimIndent(),
                mapOf("rootVendorTransactionId" to rootVendorTransactionId),
            ) { rs, _ ->
                LockedRoot(
                    activeVendorTransactionId = rs.getString("actv_tx_id"),
                    lastChangedAt = rs.getString("last_chng_dttm"),
                    status = TxStatus.valueOf(rs.getString("last_pub_stcd")),
                )
            }.firstOrNull()

    private fun lifecycleParameters(
        rootVendorTransactionId: String,
        trySequence: Int,
    ) = mapOf(
        "rootVendorTransactionId" to rootVendorTransactionId,
        "trySequence" to trySequence,
        "employeeNo" to SystemAudit.EMPNO,
        "branchCode" to SystemAudit.BRCD,
    )

    private data class LockedRoot(
        val activeVendorTransactionId: String,
        val lastChangedAt: String,
        val status: TxStatus,
    )

    private companion object {
        val SELECT_COLUMNS =
            """
            SELECT boost.orig_tx_id, boost.try_seq, boost.ext_tx_id, boost.bst_stcd,
                   boost.claim_id, boost.claim_exp_dttm, boost.rplc_tx_id, boost.rplc_tx_hash,
                   boost.fee_lvl, boost.gasless_yn, boost.new_tx_id, boost.req_dttm, boost.rsp_dttm
            FROM bcm_boost_l boost
            """.trimIndent()
        val ROW_MAPPER =
            RowMapper { rs, _ ->
                BoostAttempt(
                    rootVendorTransactionId = rs.getString("orig_tx_id"),
                    trySequence = rs.getInt("try_seq"),
                    externalTransactionId = rs.getString("ext_tx_id"),
                    status = BoostStatus.valueOf(rs.getString("bst_stcd")),
                    claimId = rs.getString("claim_id"),
                    claimExpiresAt = rs.getString("claim_exp_dttm"),
                    replacementVendorTransactionId = rs.getString("rplc_tx_id"),
                    replacementTransactionHash = rs.getString("rplc_tx_hash"),
                    feeLevel = VendorFeeLevel.valueOf(rs.getString("fee_lvl")),
                    useGasless = rs.getString("gasless_yn") == "Y",
                    newVendorTransactionId = rs.getString("new_tx_id"),
                    requestedAt = rs.getString("req_dttm"),
                    respondedAt = rs.getString("rsp_dttm"),
                )
            }
    }
}
