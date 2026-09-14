package com.whatto.bcm.infra.persistence.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("bcm_acnt_m")
data class AccountEntity(
    @Id
    @Column("acnt_id")
    val acntId: String,
    @Column("acnt_typ_dvcd")
    val acntTypDvcd: String,
    @Column("ref")
    val ref: String,
    @Column("vndr_vlt_id")
    val vndrVltId: String?,
    @Column("reg_dttm")
    val regDttm: String,
    @Column("frst_reg_empno")
    val frstRegEmpno: String,
    @Column("frst_reg_brcd")
    val frstRegBrcd: String,
    @Column("last_chng_empno")
    val lastChngEmpno: String,
    @Column("last_chng_brcd")
    val lastChngBrcd: String,
    @Column("acnt_mdl")
    val acntMdl: String = "VAULT",
) {
    fun toDomain(): Account =
        Account(
            accountId = acntId,
            accountType = AccountTypeCodes.toDomain(acntTypDvcd),
            ref = ref,
            vendorVaultId = vndrVltId,
            registeredAt = regDttm,
            model = AccountModel.valueOf(acntMdl),
        )

    companion object {
        fun from(account: Account): AccountEntity =
            AccountEntity(
                acntId = account.accountId,
                acntTypDvcd = AccountTypeCodes.toCode(account.accountType),
                ref = account.ref,
                vndrVltId = account.vendorVaultId,
                regDttm = account.registeredAt,
                frstRegEmpno = SystemAudit.EMPNO,
                frstRegBrcd = SystemAudit.BRCD,
                lastChngEmpno = SystemAudit.EMPNO,
                lastChngBrcd = SystemAudit.BRCD,
                acntMdl = account.model.name,
            )
    }
}
