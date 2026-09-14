package com.whatto.bcm.infra.persistence.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.LogicalAccountRepository
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.provider.ProviderOriginRepository
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class LogicalAccountJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val origins: ProviderOriginRepository,
    private val accounts: AccountJdbcAdapter,
) : LogicalAccountRepository {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun reserve(
        account: Account,
        origin: ProviderOrigin,
    ): Account {
        account.requireLogical()
        require(origin.protocolProvider == "dfns") { "Logical accounts require the Dfns origin" }
        origin.requireMatch(origins.findBinding())
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm, acnt_mdl,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (:id, :type, :ref, NULL, :now, 'LOGICAL', :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (acnt_typ_dvcd, ref) DO NOTHING
            """.trimIndent(),
            mapOf(
                "id" to account.accountId,
                "type" to AccountTypeCodes.toCode(account.accountType),
                "ref" to account.ref,
                "now" to account.registeredAt,
                "employeeNo" to SystemAudit.EMPNO,
                "branchCode" to SystemAudit.BRCD,
            ),
        )
        return checkNotNull(accounts.findByTypeAndRef(account.accountType, account.ref)).also { it.requireLogical() }
    }
}
