package com.whatto.bcm.infra.persistence.tx

import com.whatto.bcm.domain.tx.TxObservationQuarantine
import com.whatto.bcm.domain.tx.TxObservationQuarantineEvidence
import com.whatto.bcm.domain.tx.TxObservationQuarantineRepository
import com.whatto.bcm.domain.tx.TxObservationQuarantineSource
import com.whatto.bcm.domain.tx.TxObservationQuarantineStatus
import com.whatto.bcm.domain.vendor.VendorLookup
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * `bcm_tx_qrnt_l` SQL 어댑터(03 V37).
 *
 * 증적 본문은 **읽어 돌려주지 않는다** — 앱 역할은 `body` 를 볼 수 없고 감사 역할만 본다.
 * 조회 결과의 [TxObservationQuarantineEvidence.body] 는 빈 배열이며, 길이·해시는 `body_len`·`body_hash` 로 본다.
 */
@Repository
class TxObservationQuarantineJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : TxObservationQuarantineRepository {
    override fun open(
        quarantine: TxObservationQuarantine,
        observedAt: String,
    ): TxObservationQuarantine {
        jdbc.update(
            """
            INSERT INTO bcm_tx_qrnt_l
              (qrnt_id, orig_tx_id, obsv_tx_id, ntwk_cd, src_dvcd, qrnt_stcd,
               cnfl_fld, rcrd_vl, obsv_vl,
               evdc_lkup, evdc_idx, evdc_ctyp, body, body_len, body_hash,
               obsv_fngr, dtct_cnt, frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:quarantineId, :rootVendorTransactionId, :observedVendorTransactionId, :network, :source, 'OPEN',
               :field, :recordedValue, :observedValue,
               :lookup, :indexInBody, :contentType, :body, octet_length(:body), encode(sha256(:body), 'hex'),
               :fingerprint, 1, :observedAt, :observedAt,
               :empno, :brcd, :empno, :brcd)
            -- 같은 관찰이 다시 발견되면 행을 늘리지 않고 횟수만 올린다. 증적은 트리거가 막으므로 손대지 않는다.
            ON CONFLICT (obsv_fngr) WHERE qrnt_stcd = 'OPEN'
            DO UPDATE SET dtct_cnt = bcm_tx_qrnt_l.dtct_cnt + 1,
                          last_chng_dttm = GREATEST(bcm_tx_qrnt_l.last_chng_dttm, EXCLUDED.last_chng_dttm),
                          last_chng_empno = EXCLUDED.last_chng_empno,
                          last_chng_brcd = EXCLUDED.last_chng_brcd
            """.trimIndent(),
            mapOf(
                "quarantineId" to quarantine.quarantineId,
                "rootVendorTransactionId" to quarantine.rootVendorTransactionId,
                "observedVendorTransactionId" to quarantine.observedVendorTransactionId,
                "network" to quarantine.network,
                "source" to quarantine.source.name,
                "field" to quarantine.field,
                "recordedValue" to quarantine.recordedValue,
                "observedValue" to quarantine.observedValue,
                "lookup" to quarantine.evidence.lookup.name,
                "indexInBody" to quarantine.evidence.indexInBody,
                "contentType" to quarantine.evidence.contentType,
                "body" to quarantine.evidence.body,
                "fingerprint" to quarantine.fingerprint,
                "observedAt" to observedAt,
                "empno" to SystemAudit.EMPNO,
                "brcd" to SystemAudit.BRCD,
            ),
        )
        return checkNotNull(findOpenByFingerprint(quarantine.fingerprint)) { "quarantine row disappeared after insert" }
    }

    override fun findOpenByRoot(rootVendorTransactionId: String): List<TxObservationQuarantine> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE orig_tx_id = :root AND qrnt_stcd = 'OPEN' ORDER BY frst_dtct_dttm, qrnt_id",
            mapOf("root" to rootVendorTransactionId),
            rowMapper,
        )

    private fun findOpenByFingerprint(fingerprint: String): TxObservationQuarantine? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE obsv_fngr = :fingerprint AND qrnt_stcd = 'OPEN'",
                mapOf("fingerprint" to fingerprint),
                rowMapper,
            ).firstOrNull()

    private val rowMapper =
        RowMapper { rs, _ ->
            TxObservationQuarantine(
                quarantineId = rs.getString("qrnt_id"),
                rootVendorTransactionId = rs.getString("orig_tx_id"),
                observedVendorTransactionId = rs.getString("obsv_tx_id"),
                network = rs.getString("ntwk_cd"),
                source = TxObservationQuarantineSource.valueOf(rs.getString("src_dvcd")),
                status = TxObservationQuarantineStatus.valueOf(rs.getString("qrnt_stcd")),
                field = rs.getString("cnfl_fld"),
                recordedValue = rs.getString("rcrd_vl"),
                observedValue = rs.getString("obsv_vl"),
                evidence =
                    TxObservationQuarantineEvidence(
                        // 앱 역할은 body 를 읽지 않는다 — 길이·해시로만 본문을 가리킨다.
                        body = ByteArray(0),
                        contentType = rs.getString("evdc_ctyp"),
                        lookup = VendorLookup.valueOf(rs.getString("evdc_lkup")),
                        indexInBody = rs.getObject("evdc_idx", Integer::class.java)?.toInt(),
                    ),
                fingerprint = rs.getString("obsv_fngr"),
                detectionCount = rs.getInt("dtct_cnt"),
                firstDetectedAt = rs.getString("frst_dtct_dttm"),
                lastChangedAt = rs.getString("last_chng_dttm"),
            )
        }

    private companion object {
        /** `body` 는 고르지 않는다 — 앱 역할에 열람 권한이 없고, 있어도 메모리에 들고 다닐 이유가 없다. */
        const val SELECT_COLUMNS =
            "SELECT qrnt_id, orig_tx_id, obsv_tx_id, ntwk_cd, src_dvcd, qrnt_stcd, " +
                "cnfl_fld, rcrd_vl, obsv_vl, evdc_lkup, evdc_idx, evdc_ctyp, body_len, body_hash, " +
                "obsv_fngr, dtct_cnt, frst_dtct_dttm, last_chng_dttm FROM bcm_tx_qrnt_l"
    }
}
