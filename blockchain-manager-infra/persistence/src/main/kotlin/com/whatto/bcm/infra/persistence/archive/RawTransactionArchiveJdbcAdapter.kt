package com.whatto.bcm.infra.persistence.archive

import com.whatto.bcm.domain.archive.RawTransactionArchiveBatch
import com.whatto.bcm.domain.archive.RawTransactionArchiveRepository
import com.whatto.bcm.infra.persistence.webhook.COMPLETED_WEBHOOK_PREDICATE
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class RawTransactionArchiveJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : RawTransactionArchiveRepository {
    override fun archiveCompletedBatch(
        baseDate: String,
        receivedAtOrBefore: String,
        limit: Int,
    ): RawTransactionArchiveBatch {
        require(baseDate.matches(Regex("[0-9]{8}"))) { "archive baseDate must be yyyyMMdd" }
        require(limit > 0) { "archive limit must be positive" }
        return checkNotNull(
            jdbc.queryForObject(
                """
                WITH ranked AS MATERIALIZED (
                  SELECT webhook.vndr_tx_id,
                         transaction.ext_tx_id,
                         transaction.tx_hash,
                         CASE WHEN submission.vndr_tx_id IS NULL
                              THEN webhook.payload::jsonb #>> '{data,destinationAddress}'
                              ELSE webhook.payload::jsonb #>> '{data,sourceAddress}'
                         END AS addr,
                         transaction.ntwk_cd,
                         transaction.tkn_smbl,
                         transaction.last_pub_stcd,
                         webhook.payload,
                         webhook.payload_hash,
                         webhook.sign_vl,
                         webhook.rcv_dttm,
                         ROW_NUMBER() OVER (
                           PARTITION BY webhook.vndr_tx_id
                           ORDER BY webhook.rcv_dttm DESC, webhook.noti_id DESC
                         ) AS latest_rank
                  FROM bcm_whk_l webhook
                  JOIN bcm_tx_l transaction
                    ON transaction.actv_tx_id = webhook.vndr_tx_id
                   AND transaction.last_pub_stcd = 'FINALIZED'
                  LEFT JOIN bcm_sbmt_l submission
                    ON submission.vndr_tx_id = transaction.vndr_tx_id
                  WHERE $COMPLETED_WEBHOOK_PREDICATE
                    AND webhook.rcv_dttm <= :receivedAtOrBefore
                ), candidates AS MATERIALIZED (
                  SELECT ranked.*
                  FROM ranked
                  WHERE ranked.latest_rank = 1
                    AND NOT EXISTS (
                      SELECT 1
                      FROM bcm_raw_tx_l archived
                      WHERE archived.vndr_tx_id = ranked.vndr_tx_id
                        AND archived.rcv_dttm >= ranked.rcv_dttm
                    )
                  ORDER BY ranked.rcv_dttm, ranked.vndr_tx_id
                  LIMIT :limit
                ), archived AS (
                  INSERT INTO bcm_raw_tx_l
                    (base_dt, vndr_tx_id, ext_tx_id, tx_hash, addr, ntwk_cd, tkn_smbl, final_stcd,
                     payload, payload_hash, sign_vl, rcv_dttm,
                     frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                  SELECT :baseDate, vndr_tx_id, ext_tx_id, tx_hash, addr, ntwk_cd, tkn_smbl, last_pub_stcd,
                         payload, payload_hash, sign_vl, rcv_dttm,
                         :employeeNo, :branchCode, :employeeNo, :branchCode
                  FROM candidates
                  ON CONFLICT (base_dt, vndr_tx_id) DO UPDATE
                  SET ext_tx_id = EXCLUDED.ext_tx_id,
                      tx_hash = EXCLUDED.tx_hash,
                      addr = EXCLUDED.addr,
                      ntwk_cd = EXCLUDED.ntwk_cd,
                      tkn_smbl = EXCLUDED.tkn_smbl,
                      final_stcd = EXCLUDED.final_stcd,
                      payload = EXCLUDED.payload,
                      payload_hash = EXCLUDED.payload_hash,
                      sign_vl = EXCLUDED.sign_vl,
                      rcv_dttm = EXCLUDED.rcv_dttm,
                      last_chng_empno = EXCLUDED.last_chng_empno,
                      last_chng_brcd = EXCLUDED.last_chng_brcd
                  WHERE bcm_raw_tx_l.rcv_dttm < EXCLUDED.rcv_dttm
                  RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM candidates) AS candidate_count,
                       (SELECT COUNT(*) FROM archived) AS archived_count
                """.trimIndent(),
                mapOf(
                    "baseDate" to baseDate,
                    "receivedAtOrBefore" to receivedAtOrBefore,
                    "limit" to limit,
                    "employeeNo" to SystemAudit.EMPNO,
                    "branchCode" to SystemAudit.BRCD,
                ),
            ) { rs, _ ->
                RawTransactionArchiveBatch(
                    candidateCount = rs.getInt("candidate_count"),
                    archivedCount = rs.getInt("archived_count"),
                )
            },
        )
    }

    override fun deleteProcessedAtOrBefore(processedAtOrBefore: String): Int =
        jdbc.update(
            """
            DELETE FROM bcm_whk_l webhook
            WHERE webhook.prcs_stcd = 'S'
              AND webhook.prcs_dttm <= :processedAtOrBefore
              AND (
                NOT ($COMPLETED_WEBHOOK_PREDICATE)
                OR EXISTS (
                  SELECT 1
                  FROM bcm_raw_tx_l archived
                  WHERE archived.vndr_tx_id = webhook.vndr_tx_id
                    AND archived.rcv_dttm >= webhook.rcv_dttm
                )
              )
            """.trimIndent(),
            mapOf("processedAtOrBefore" to processedAtOrBefore),
        )
}
