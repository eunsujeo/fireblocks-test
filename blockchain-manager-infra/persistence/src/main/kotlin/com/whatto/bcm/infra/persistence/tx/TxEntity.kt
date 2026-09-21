package com.whatto.bcm.infra.persistence.tx

import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.tx.TxType
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal

@Table("bcm_tx_l")
data class TxEntity(
    @Id
    @Column("vndr_tx_id")
    val vndrTxId: String,
    @Column("actv_tx_id")
    val actvTxId: String,
    @Column("ext_tx_id")
    val extTxId: String?,
    @Column("acnt_id")
    val acntId: String,
    @Column("ntwk_cd")
    val ntwkCd: String,
    @Column("tkn_smbl")
    val tknSmbl: String,
    @Column("tx_hash")
    val txHash: String?,
    @Column("last_pub_stcd")
    val lastPubStcd: String,
    @Column("cnfm_cnt")
    val cnfmCnt: Int,
    @Column("vndr_sub_stcd")
    val vndrSubStcd: String?,
    @Column("vndr_ntwk_stcd")
    val vndrNtwkStcd: String?,
    @Column("stall_alrt_dttm")
    val stallAlrtDttm: String?,
    @Column("trsf_amt")
    val trsfAmt: BigDecimal?,
    @Column("src_addr")
    val srcAddr: String?,
    @Column("dst_addr")
    val dstAddr: String?,
    @Column("base_amt")
    val baseAmt: String?,
    @Column("dcml_cnt")
    val dcmlCnt: Int?,
    @Column("tx_dvcd")
    val txDvcd: String?,
    @Column("vndr_crt_dttm")
    val vndrCrtDttm: String?,
    @Column("rcnc_chck_dttm")
    val rcncChckDttm: String?,
    @Column("rcnc_chck_cnt")
    val rcncChckCnt: Int,
    @Column("rcnc_stop_dttm")
    val rcncStopDttm: String?,
    @Column("frst_dtct_dttm")
    val frstDtctDttm: String,
    @Column("last_chng_dttm")
    val lastChngDttm: String,
    @Column("frst_reg_empno")
    val frstRegEmpno: String,
    @Column("frst_reg_brcd")
    val frstRegBrcd: String,
    @Column("last_chng_empno")
    val lastChngEmpno: String,
    @Column("last_chng_brcd")
    val lastChngBrcd: String,
) {
    fun toDomain(): TxRecord =
        TxRecord(
            vendorTxId = vndrTxId,
            activeVendorTxId = actvTxId,
            externalTxId = extTxId,
            accountId = acntId,
            network = ntwkCd,
            symbol = tknSmbl,
            transactionHash = txHash,
            lastPublishedStatus = TxStatus.valueOf(lastPubStcd),
            confirmationCount = cnfmCnt,
            vendorSubStatus = vndrSubStcd,
            vendorNetworkStatus = vndrNtwkStcd,
            stallAlertedAt = stallAlrtDttm,
            firstDetectedAt = frstDtctDttm,
            lastChangedAt = lastChngDttm,
            vendorCreatedAt = vndrCrtDttm,
            amount = trsfAmt?.stripTrailingZeros()?.toPlainString(),
            sourceAddress = srcAddr,
            destinationAddress = dstAddr,
            amountBaseUnits = baseAmt,
            amountDecimals = dcmlCnt,
            transactionType = txDvcd?.let(TxType::valueOf),
            reconciliationCheckedAt = rcncChckDttm,
            reconciliationCheckCount = rcncChckCnt,
            reconciliationStoppedAt = rcncStopDttm,
        )

    companion object {
        fun from(txRecord: TxRecord): TxEntity =
            TxEntity(
                vndrTxId = txRecord.vendorTxId,
                actvTxId = txRecord.activeVendorTxId,
                extTxId = txRecord.externalTxId,
                acntId = txRecord.accountId,
                ntwkCd = txRecord.network,
                tknSmbl = txRecord.symbol,
                txHash = txRecord.transactionHash,
                lastPubStcd = txRecord.lastPublishedStatus.name,
                cnfmCnt = txRecord.confirmationCount,
                vndrSubStcd = txRecord.vendorSubStatus,
                vndrNtwkStcd = txRecord.vendorNetworkStatus,
                stallAlrtDttm = txRecord.stallAlertedAt,
                vndrCrtDttm = txRecord.vendorCreatedAt,
                trsfAmt = txRecord.amount?.let { BigDecimal(it) },
                srcAddr = txRecord.sourceAddress,
                dstAddr = txRecord.destinationAddress,
                baseAmt = txRecord.amountBaseUnits,
                dcmlCnt = txRecord.amountDecimals,
                txDvcd = txRecord.transactionType?.name,
                rcncChckDttm = txRecord.reconciliationCheckedAt,
                rcncChckCnt = txRecord.reconciliationCheckCount,
                rcncStopDttm = txRecord.reconciliationStoppedAt,
                frstDtctDttm = txRecord.firstDetectedAt,
                lastChngDttm = txRecord.lastChangedAt,
                frstRegEmpno = SystemAudit.EMPNO,
                frstRegBrcd = SystemAudit.BRCD,
                lastChngEmpno = SystemAudit.EMPNO,
                lastChngBrcd = SystemAudit.BRCD,
            )
    }
}
