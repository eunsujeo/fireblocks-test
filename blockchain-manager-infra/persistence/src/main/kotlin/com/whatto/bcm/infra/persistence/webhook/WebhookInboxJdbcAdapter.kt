package com.whatto.bcm.infra.persistence.webhook

import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.webhook.WebhookFailureResult
import com.whatto.bcm.domain.webhook.WebhookInboxItem
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookInsertResult
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

internal const val COMPLETED_WEBHOOK_PREDICATE =
    "webhook.prcs_stcd = 'S' AND webhook.vndr_cmpl_yn = 'Y' AND webhook.vndr_tx_id IS NOT NULL"

/** bcm_whk_l 수신 적재 — noti_id 충돌만 정상 중복으로 무시하고 그 밖의 DB 오류는 올려 보낸다. */
@Repository
class WebhookInboxJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : WebhookInboxRepository {
    private val inboxItemMapper =
        RowMapper { rs, _ ->
            WebhookInboxItem(
                notificationId = rs.getString("noti_id"),
                eventType = rs.getString("evnt_typ"),
                vendorTransactionId = rs.getString("vndr_tx_id"),
                payload = rs.getString("payload"),
                receivedAt = rs.getString("rcv_dttm"),
                retryCount = rs.getInt("rtry_cnt"),
            )
        }

    override fun insertIfAbsent(notification: WebhookNotification): WebhookInsertResult {
        val inserted =
            jdbc.update(
                """
                INSERT INTO bcm_whk_l
                  (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl,
                   rcv_dttm, prcs_stcd, rtry_cnt, err_msg, prcs_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:notiId, :eventType, :vendorTxId, :payload, :payloadHash, :signature,
                   :receivedAt, 'P', 0, NULL, NULL,
                   :empno, :brcd, :empno, :brcd)
                ON CONFLICT (noti_id) DO NOTHING
                """.trimIndent(),
                mapOf(
                    "notiId" to notification.notificationId,
                    "eventType" to notification.eventType,
                    "vendorTxId" to notification.vendorTransactionId,
                    "payload" to notification.payload,
                    "payloadHash" to notification.payloadHash,
                    "signature" to notification.signature,
                    "receivedAt" to notification.receivedAt,
                    "empno" to SystemAudit.EMPNO,
                    "brcd" to SystemAudit.BRCD,
                ),
            )
        return if (inserted == 1) WebhookInsertResult.INSERTED else WebhookInsertResult.DUPLICATE
    }

    override fun findNextPendingForUpdate(now: String): WebhookInboxItem? =
        jdbc
            .query(
                """
                SELECT noti_id, evnt_typ, vndr_tx_id, payload, rcv_dttm, rtry_cnt
                  FROM bcm_whk_l
                 WHERE prcs_stcd = 'P'
                   AND (next_attmpt_dttm IS NULL OR next_attmpt_dttm <= :now)
                 ORDER BY rcv_dttm, noti_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
                """.trimIndent(),
                mapOf("now" to now),
                inboxItemMapper,
            ).firstOrNull()

    override fun markProcessed(
        notificationId: String,
        processedAt: String,
        vendorCompleted: Boolean,
    ) {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_whk_l
                   SET prcs_stcd = 'S',
                       prcs_dttm = :processedAt,
                       next_attmpt_dttm = NULL,
                       vndr_cmpl_yn = :vendorCompleted,
                       err_msg = NULL,
                       last_chng_empno = :employeeNo, last_chng_brcd = :branchCode
                 WHERE noti_id = :notificationId AND prcs_stcd = 'P'
                """.trimIndent(),
                mapOf(
                    "notificationId" to notificationId,
                    "processedAt" to processedAt,
                    "vendorCompleted" to if (vendorCompleted) "Y" else "N",
                    "employeeNo" to SystemAudit.EMPNO,
                    "branchCode" to SystemAudit.BRCD,
                ),
            )
        if (updated != 1) throw ResourceNotFoundException("pendingWebhook", notificationId)
    }

    override fun recordFailure(
        notificationId: String,
        errorMessage: String,
        maxAttempts: Int,
        nextAttemptAt: String?,
    ): WebhookFailureResult {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        return jdbc
            .query(
                """
                UPDATE bcm_whk_l
                   SET rtry_cnt = rtry_cnt + 1,
                       prcs_stcd = CASE WHEN rtry_cnt + 1 >= :maxAttempts THEN 'F' ELSE 'P' END,
                       next_attmpt_dttm = :nextAttemptAt,
                       err_msg = :errorMessage,
                       last_chng_empno = :employeeNo,
                       last_chng_brcd = :branchCode
                 WHERE noti_id = :notificationId AND prcs_stcd = 'P'
                 RETURNING rtry_cnt, prcs_stcd
                """.trimIndent(),
                mapOf(
                    "notificationId" to notificationId,
                    "errorMessage" to errorMessage.take(1000),
                    "nextAttemptAt" to nextAttemptAt,
                    "maxAttempts" to maxAttempts,
                    "employeeNo" to SystemAudit.EMPNO,
                    "branchCode" to SystemAudit.BRCD,
                ),
            ) { rs, _ ->
                WebhookFailureResult(
                    retryCount = rs.getInt("rtry_cnt"),
                    quarantined = rs.getString("prcs_stcd") == "F",
                )
            }.firstOrNull()
            ?: throw ResourceNotFoundException("pendingWebhook", notificationId)
    }
}
