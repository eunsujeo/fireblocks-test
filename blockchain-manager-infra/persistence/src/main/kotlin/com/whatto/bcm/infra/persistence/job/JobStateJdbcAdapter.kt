package com.whatto.bcm.infra.persistence.job

import com.whatto.bcm.domain.job.JobState
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JobStateJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : JobStateRepository {
    override fun markStarted(
        jobName: String,
        at: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_job_m
              (job_nm, last_run_dttm, last_scs_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:jobName, :at, NULL, :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (job_nm) DO UPDATE
            SET last_run_dttm = EXCLUDED.last_run_dttm,
                last_chng_empno = EXCLUDED.last_chng_empno,
                last_chng_brcd = EXCLUDED.last_chng_brcd
            """.trimIndent(),
            parameters(jobName, at),
        )
    }

    override fun markSucceeded(
        jobName: String,
        at: String,
    ) {
        check(
            jdbc.update(
                """
                UPDATE bcm_job_m
                SET last_scs_dttm = :at,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE job_nm = :jobName
                """.trimIndent(),
                parameters(jobName, at),
            ) == 1,
        ) { "job heartbeat does not exist: jobName=$jobName" }
    }

    override fun markValidationStarted(
        jobName: String,
        at: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_job_m
              (job_nm, last_run_dttm, last_scs_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:jobName, :at, NULL, :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (job_nm) DO UPDATE
            SET last_run_dttm = EXCLUDED.last_run_dttm,
                last_scs_dttm = NULL,
                last_chng_empno = EXCLUDED.last_chng_empno,
                last_chng_brcd = EXCLUDED.last_chng_brcd
            """.trimIndent(),
            parameters(jobName, at),
        )
    }

    override fun find(jobName: String): JobState? =
        jdbc
            .query(
                """
                SELECT job_nm, last_run_dttm, last_scs_dttm
                FROM bcm_job_m
                WHERE job_nm = :jobName
                """.trimIndent(),
                mapOf("jobName" to jobName),
            ) { rs, _ ->
                JobState(
                    jobName = rs.getString("job_nm"),
                    lastRunAt = rs.getString("last_run_dttm"),
                    lastSucceededAt = rs.getString("last_scs_dttm"),
                )
            }.firstOrNull()

    private fun parameters(
        jobName: String,
        at: String,
    ) = mapOf(
        "jobName" to jobName,
        "at" to at,
        "employeeNo" to SystemAudit.EMPNO,
        "branchCode" to SystemAudit.BRCD,
    )
}
