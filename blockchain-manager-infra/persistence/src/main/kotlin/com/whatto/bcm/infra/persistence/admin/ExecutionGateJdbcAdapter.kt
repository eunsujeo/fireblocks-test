package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateRepository
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class ExecutionGateJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : ExecutionGateRepository {
    override fun findCurrent(
        network: String,
        type: ExecutionGateType,
    ): ExecutionGateEvent? =
        jdbc
            .query(
                "$SELECT WHERE ntwk_cd = :network AND gate_dvcd = :type ORDER BY evt_seq DESC LIMIT 1",
                mapOf("network" to network, "type" to type.name),
                ROW_MAPPER,
            ).firstOrNull()

    override fun lockAndFindCurrent(
        network: String,
        type: ExecutionGateType,
    ): ExecutionGateEvent? {
        lock(network, type)
        return findCurrent(network, type)
    }

    override fun findByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): ExecutionGateEvent? =
        jdbc
            .query(
                "$SELECT WHERE frst_reg_empno = :employeeNo AND idmp_key = :idempotencyKey",
                mapOf("employeeNo" to employeeNo, "idempotencyKey" to idempotencyKey),
                ROW_MAPPER,
            ).firstOrNull()

    @Transactional
    override fun insert(event: ExecutionGateEvent): ExecutionGateEvent {
        lock(event.network, event.type)
        val inserted =
            jdbc.update(
                """
                INSERT INTO bcm_exec_gate_evt_l
                  (gate_evt_id, ntwk_cd, gate_dvcd, evt_seq, gate_stcd, req_rsn, work_tckt,
                   idmp_key, rsm_req_id, occr_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:eventId, :network, :type, :sequence, :status, :reason, :workTicket,
                   :idempotencyKey, :resumeRequestId, :occurredAt, :employeeNo, :branchCode, :employeeNo, :branchCode)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                mapOf(
                    "eventId" to event.eventId,
                    "network" to event.network,
                    "type" to event.type.name,
                    "sequence" to event.sequence,
                    "status" to event.status.name,
                    "reason" to event.reason,
                    "workTicket" to event.workTicket,
                    "idempotencyKey" to event.idempotencyKey,
                    "resumeRequestId" to event.resumeRequestId,
                    "occurredAt" to CoreDateTimes.format(LocalDateTime.ofInstant(event.occurredAt, ZoneOffset.UTC)),
                    "employeeNo" to event.actor.employeeNo,
                    "branchCode" to event.actor.branchCode,
                ),
            )
        if (inserted != 1) {
            throw ConflictException("executionGate", "${event.network}:${event.type}")
        }
        return event
    }

    private fun lock(
        network: String,
        type: ExecutionGateType,
    ) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) IS NULL",
            mapOf("key" to "BCM:EXECUTION_GATE:$network:${type.name}"),
            Boolean::class.java,
        )
    }

    private companion object {
        const val SELECT =
            """
            SELECT gate_evt_id, ntwk_cd, gate_dvcd, evt_seq, gate_stcd, req_rsn, work_tckt,
                   idmp_key, rsm_req_id, occr_dttm, frst_reg_empno, frst_reg_brcd
              FROM bcm_exec_gate_evt_l
            """

        val ROW_MAPPER =
            RowMapper { rs, _ ->
                ExecutionGateEvent(
                    rs.getString("gate_evt_id"),
                    rs.getString("ntwk_cd"),
                    ExecutionGateType.valueOf(rs.getString("gate_dvcd")),
                    rs.getInt("evt_seq"),
                    ExecutionGateStatus.valueOf(rs.getString("gate_stcd")),
                    rs.getString("req_rsn"),
                    rs.getString("work_tckt"),
                    rs.getString("idmp_key"),
                    CoreDateTimes.parse(rs.getString("occr_dttm")).toInstant(ZoneOffset.UTC),
                    AdminActor(rs.getString("frst_reg_empno"), rs.getString("frst_reg_brcd"), emptySet()),
                    rs.getString("rsm_req_id"),
                )
            }
    }
}
