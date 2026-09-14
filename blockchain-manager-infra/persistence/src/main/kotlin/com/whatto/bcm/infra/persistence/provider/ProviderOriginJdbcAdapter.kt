package com.whatto.bcm.infra.persistence.provider

import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.provider.ProviderOriginRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class ProviderOriginJdbcAdapter(
    private val jdbc: JdbcTemplate,
) : ProviderOriginRepository {
    override fun findBinding(): ProviderOrigin? {
        val bindings =
            jdbc.query("SELECT orgn_id, exec_mode, prtc_prvd, pltfrm_inst_id, vndr_org_id, chain_mode FROM bcm_prvd_bndg_m") { row, _ ->
                ProviderOrigin(
                    row.getString("orgn_id"),
                    row.getString("exec_mode"),
                    row.getString("prtc_prvd"),
                    row.getString("pltfrm_inst_id"),
                    row.getString("vndr_org_id"),
                    row.getString("chain_mode"),
                )
            }
        check(bindings.size <= 1) { "Provider origin binding must contain at most one row" }
        return bindings.singleOrNull()
    }
}
