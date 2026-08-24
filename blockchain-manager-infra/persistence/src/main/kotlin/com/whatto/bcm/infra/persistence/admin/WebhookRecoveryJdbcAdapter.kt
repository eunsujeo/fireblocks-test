package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.WebhookRecoveryCallType
import com.whatto.bcm.domain.admin.WebhookRecoveryEvent
import com.whatto.bcm.domain.admin.WebhookRecoveryEventStatus
import com.whatto.bcm.domain.admin.WebhookRecoveryRepository
import com.whatto.bcm.domain.admin.WebhookRecoveryRequest
import com.whatto.bcm.domain.admin.WebhookRecoveryScope
import com.whatto.bcm.domain.admin.WebhookRecoveryView
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class WebhookRecoveryJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : WebhookRecoveryRepository {
    override fun findRecent(limit: Int): List<WebhookRecoveryView> {
        require(limit > 0) { "limit must be positive" }
        return jdbc
            .query(
                "$REQUEST_SELECT ORDER BY req_dttm DESC, rcvr_req_id DESC LIMIT :limit",
                mapOf("limit" to limit),
            ) { rs, _ -> rs.toRequest() }
            .map { it.view() }
    }

    override fun find(requestId: String): WebhookRecoveryView? =
        findRequest("WHERE rcvr_req_id = :requestId", mapOf("requestId" to requestId))?.view()

    override fun findForUpdate(requestId: String): WebhookRecoveryView? =
        findRequest("WHERE rcvr_req_id = :requestId FOR UPDATE", mapOf("requestId" to requestId))?.view()

    override fun findByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): WebhookRecoveryView? =
        findRequest(
            "WHERE frst_reg_empno = :employeeNo AND idmp_key = :idempotencyKey",
            mapOf("employeeNo" to employeeNo, "idempotencyKey" to idempotencyKey),
        )?.view()

    override fun insertRequest(request: WebhookRecoveryRequest): WebhookRecoveryRequest {
        val inserted =
            jdbc.update(
                """
                INSERT INTO bcm_whk_rcvr_req_l
                  (rcvr_req_id, whk_id, scope_dvcd, req_evt_payload, req_evt_hash,
                   idmp_key, req_rsn, work_tckt, req_dttm,
                   aprv_empno, aprv_brcd, aprv_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:requestId, :webhookId, :scope, CAST(:requiredEventsPayload AS jsonb), :requiredEventsHash,
                   :idempotencyKey, :reason, :workTicket, :requestedAt,
                   :approverEmployeeNo, :approverBranchCode, :approvedAt,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                ON CONFLICT (frst_reg_empno, idmp_key) DO NOTHING
                """.trimIndent(),
                mapOf(
                    "requestId" to request.requestId,
                    "webhookId" to request.webhookId,
                    "scope" to request.scope.name,
                    "requiredEventsPayload" to request.requiredEventsPayload,
                    "requiredEventsHash" to request.requiredEventsHash,
                    "idempotencyKey" to request.idempotencyKey,
                    "reason" to request.reason,
                    "workTicket" to request.workTicket,
                    "requestedAt" to request.requestedAt.coreDateTime(),
                    "approverEmployeeNo" to request.approvedBy.employeeNo,
                    "approverBranchCode" to request.approvedBy.branchCode,
                    "approvedAt" to request.approvedAt.coreDateTime(),
                    "employeeNo" to request.requestedBy.employeeNo,
                    "branchCode" to request.requestedBy.branchCode,
                ),
            )
        if (inserted != 1) {
            throw ConflictException("webhookRecovery", "${request.requestedBy.employeeNo}:${request.idempotencyKey}")
        }
        return request
    }

    override fun appendEvent(event: WebhookRecoveryEvent): WebhookRecoveryEvent {
        jdbc.update(
            """
            INSERT INTO bcm_whk_rcvr_evt_l
              (rcvr_req_id, evt_seq, rcvr_stcd, call_dvcd, call_dttm, rslt_dttm,
               whk_stcd, obs_evt_payload, obs_evt_hash, scope_fr_dttm, scope_to_dttm,
               schd_noti_cnt, rsp_payload, rsp_hash, err_cd, occr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:requestId, :sequence, :status, :callType, :calledAt, :resultAt,
               :webhookStatus, CAST(:observedEventsPayload AS jsonb), :observedEventsHash, :scopeFrom, :scopeTo,
               :scheduledNotificationCount, CAST(:responsePayload AS jsonb), :responseHash, :errorCode, :occurredAt,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "requestId" to event.requestId,
                "sequence" to event.sequence,
                "status" to event.status.name,
                "callType" to event.callType.name,
                "calledAt" to event.calledAt.coreDateTime(),
                "resultAt" to event.resultAt?.coreDateTime(),
                "webhookStatus" to event.webhookStatus,
                "observedEventsPayload" to event.observedEventsPayload,
                "observedEventsHash" to event.observedEventsHash,
                "scopeFrom" to event.scopeFrom?.coreDateTime(),
                "scopeTo" to event.scopeTo?.coreDateTime(),
                "scheduledNotificationCount" to event.scheduledNotificationCount,
                "responsePayload" to event.responsePayload,
                "responseHash" to event.responseHash,
                "errorCode" to event.errorCode,
                "occurredAt" to event.occurredAt.coreDateTime(),
                "employeeNo" to event.actor.employeeNo,
                "branchCode" to event.actor.branchCode,
            ),
        )
        return event
    }

    private fun findRequest(
        suffix: String,
        parameters: Map<String, Any>,
    ): WebhookRecoveryRequest? =
        jdbc
            .query(
                "$REQUEST_SELECT $suffix",
                parameters,
            ) { rs, _ -> rs.toRequest() }
            .firstOrNull()

    private fun WebhookRecoveryRequest.view(): WebhookRecoveryView = WebhookRecoveryView(this, findEvents(requestId))

    private fun findEvents(requestId: String): List<WebhookRecoveryEvent> =
        jdbc.query(
            """
            SELECT rcvr_req_id, evt_seq, rcvr_stcd, call_dvcd, call_dttm, rslt_dttm,
                   whk_stcd,
                   CASE WHEN obs_evt_payload IS NULL THEN ARRAY[]::text[]
                        ELSE ARRAY(SELECT jsonb_array_elements_text(obs_evt_payload)) END AS observed_events,
                   obs_evt_payload::text AS obs_evt_payload, obs_evt_hash,
                   scope_fr_dttm, scope_to_dttm, schd_noti_cnt,
                   rsp_payload::text AS rsp_payload, rsp_hash, err_cd, occr_dttm,
                   frst_reg_empno, frst_reg_brcd
              FROM bcm_whk_rcvr_evt_l
             WHERE rcvr_req_id = :requestId
             ORDER BY evt_seq
            """.trimIndent(),
            mapOf("requestId" to requestId),
        ) { rs, _ -> rs.toEvent() }

    private companion object {
        const val REQUEST_SELECT =
            """
            SELECT rcvr_req_id, whk_id, scope_dvcd,
                   ARRAY(SELECT jsonb_array_elements_text(req_evt_payload)) AS required_events,
                   req_evt_payload::text AS req_evt_payload, req_evt_hash,
                   idmp_key, req_rsn, work_tckt, req_dttm,
                   aprv_empno, aprv_brcd, aprv_dttm, frst_reg_empno, frst_reg_brcd
              FROM bcm_whk_rcvr_req_l
            """

        fun ResultSet.toRequest() =
            WebhookRecoveryRequest(
                requestId = getString("rcvr_req_id"),
                webhookId = getString("whk_id"),
                scope = WebhookRecoveryScope.valueOf(getString("scope_dvcd")),
                requiredEvents = getArray("required_events").strings().toSet(),
                requiredEventsPayload = getString("req_evt_payload"),
                requiredEventsHash = getString("req_evt_hash"),
                idempotencyKey = getString("idmp_key"),
                reason = getString("req_rsn"),
                workTicket = getString("work_tckt"),
                requestedAt = getString("req_dttm").instant(),
                requestedBy = AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
                approvedAt = getString("aprv_dttm").instant(),
                approvedBy = AdminActor(getString("aprv_empno"), getString("aprv_brcd"), emptySet()),
            )

        fun ResultSet.toEvent() =
            WebhookRecoveryEvent(
                requestId = getString("rcvr_req_id"),
                sequence = getInt("evt_seq"),
                status = WebhookRecoveryEventStatus.valueOf(getString("rcvr_stcd")),
                callType = WebhookRecoveryCallType.valueOf(getString("call_dvcd")),
                calledAt = getString("call_dttm").instant(),
                resultAt = getString("rslt_dttm")?.instant(),
                webhookStatus = getString("whk_stcd"),
                observedEvents = getArray("observed_events").strings().toSet(),
                observedEventsPayload = getString("obs_evt_payload"),
                observedEventsHash = getString("obs_evt_hash"),
                scopeFrom = getString("scope_fr_dttm")?.instant(),
                scopeTo = getString("scope_to_dttm")?.instant(),
                scheduledNotificationCount = getInt("schd_noti_cnt").takeUnless { wasNull() },
                responsePayload = getString("rsp_payload"),
                responseHash = getString("rsp_hash"),
                errorCode = getString("err_cd"),
                occurredAt = getString("occr_dttm").instant(),
                actor = AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
            )

        fun java.sql.Array.strings(): List<String> = (array as Array<*>).map(Any?::toString)

        fun Instant.coreDateTime(): String = CoreDateTimes.format(LocalDateTime.ofInstant(this, ZoneOffset.UTC))

        fun String.instant(): Instant = CoreDateTimes.parse(this).toInstant(ZoneOffset.UTC)
    }
}
