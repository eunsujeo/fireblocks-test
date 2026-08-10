package com.whatto.bcm.infra.persistence.tx

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** bcm_tx_l 파생 쿼리 — 어댑터 내부 전용 */
interface TxCrudRepository : CrudRepository<TxEntity, String> {
    fun findByExtTxId(extTxId: String): TxEntity?
}

@Repository
class TxJdbcAdapter(
    private val crud: TxCrudRepository,
    private val template: JdbcAggregateTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
) : TxRecordRepository {
    private val rowMapper =
        RowMapper { rs, _ ->
            TxRecord(
                vendorTxId = rs.getString("vndr_tx_id"),
                originTxId = rs.getString("orig_tx_id"),
                externalTxId = rs.getString("ext_tx_id"),
                accountId = rs.getString("acnt_id"),
                network = rs.getString("ntwk_cd"),
                symbol = rs.getString("tkn_smbl"),
                lastPublishedStatus = TxStatus.valueOf(rs.getString("last_pub_stcd")),
                confirmationCount = rs.getInt("cnfm_cnt"),
                vendorSubStatus = rs.getString("vndr_sub_stcd"),
                vendorNetworkStatus = rs.getString("vndr_ntwk_stcd"),
                stallAlertedAt = rs.getString("stall_alrt_dttm"),
                firstDetectedAt = rs.getString("frst_dtct_dttm"),
                lastChangedAt = rs.getString("last_chng_dttm"),
            )
        }

    // 문자열 PK 는 신규 판별이 안 돼 save() 가 UPDATE 를 시도한다 — 명시적 insert/update 분리
    override fun insert(txRecord: TxRecord): TxRecord =
        try {
            template.insert(TxEntity.from(txRecord)).toDomain()
        } catch (exception: DuplicateKeyException) {
            throw ConflictException(
                resource = "transaction",
                key = txRecord.externalTxId ?: txRecord.vendorTxId,
                cause = exception,
            )
        }

    override fun update(txRecord: TxRecord): TxRecord {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_tx_l
                   SET orig_tx_id = :originTxId,
                       ext_tx_id = :externalTxId,
                       acnt_id = :accountId,
                       ntwk_cd = :network,
                       tkn_smbl = :symbol,
                       last_pub_stcd = :lastPublishedStatus,
                       cnfm_cnt = GREATEST(cnfm_cnt, :confirmationCount),
                       vndr_sub_stcd = :vendorSubStatus,
                       vndr_ntwk_stcd = :vendorNetworkStatus,
                       stall_alrt_dttm = :stallAlertedAt,
                       last_chng_dttm = GREATEST(last_chng_dttm, :lastChangedAt),
                       last_chng_empno = :employeeNo,
                       last_chng_brcd = :branchCode
                 WHERE vndr_tx_id = :vendorTxId
                """.trimIndent(),
                txRecord.parameters(),
            )
        if (updated != 1) {
            throw ResourceNotFoundException("transaction", txRecord.vendorTxId)
        }
        return requireNotNull(findByVendorTxId(txRecord.vendorTxId))
    }

    override fun findByVendorTxId(vendorTxId: String): TxRecord? = crud.findByIdOrNull(vendorTxId)?.toDomain()

    override fun findByVendorTxIdForUpdate(vendorTxId: String): TxRecord? =
        jdbc
            .query(
                "$TX_COLUMNS FROM bcm_tx_l WHERE vndr_tx_id = :vendorTxId FOR UPDATE",
                mapOf("vendorTxId" to vendorTxId),
                rowMapper,
            ).firstOrNull()

    override fun findByExternalTxId(externalTxId: String): TxRecord? = crud.findByExtTxId(externalTxId)?.toDomain()

    private fun TxRecord.parameters(): Map<String, Any?> =
        mapOf(
            "vendorTxId" to vendorTxId,
            "originTxId" to originTxId,
            "externalTxId" to externalTxId,
            "accountId" to accountId,
            "network" to network,
            "symbol" to symbol,
            "lastPublishedStatus" to lastPublishedStatus.name,
            "confirmationCount" to confirmationCount,
            "vendorSubStatus" to vendorSubStatus,
            "vendorNetworkStatus" to vendorNetworkStatus,
            "stallAlertedAt" to stallAlertedAt,
            "lastChangedAt" to lastChangedAt,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private companion object {
        const val TX_COLUMNS =
            """SELECT vndr_tx_id, orig_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl,
                      last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, stall_alrt_dttm,
                      frst_dtct_dttm, last_chng_dttm"""
    }
}
