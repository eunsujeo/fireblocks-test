package com.whatto.bcm.infra.persistence.wallet

import com.whatto.bcm.domain.provider.ProviderOriginRepository
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceArchive
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceContext
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceOperation
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceRecord
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceStore
import com.whatto.bcm.domain.wallet.StoredNetworkWalletEvidence
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * bcm_ntwk_wlt_evdc_l — 생성/조회 응답 원문의 보호 보관소.
 * 원문 바이트를 그대로 저장하고 길이·SHA-256은 DB가 계산해 CHECK로 본문과 대조한다. 반환 hash는 그 CHECK를 통과한 body_hash 컬럼 값이다.
 * 저장·조회 SQL은 body 컬럼을 읽지 않는다 — 앱 역할에는 body SELECT 권한이 없으므로 RETURNING/SELECT에서 body를 참조하면 저장이 거절된다.
 * 보관은 호출자 트랜잭션과 독립적으로 커밋한다 — 뒤따르는 원장 저장이 실패해도 이미 받은 응답 증적은 남는다.
 * 원문은 이 어댑터로 읽지 않는다. 앱 역할 권한 절차는 03·원천 runbook을 따른다.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
class NetworkWalletEvidenceJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val origins: ProviderOriginRepository,
) : NetworkWalletEvidenceStore,
    NetworkWalletEvidenceArchive {
    override fun store(
        context: NetworkWalletEvidenceContext,
        body: ByteArray,
    ): StoredNetworkWalletEvidence {
        context.scope.origin.requireMatch(origins.findBinding())
        val id = UUID.randomUUID().toString()
        val storedHash =
            jdbc.queryForObject(
                """
                INSERT INTO bcm_ntwk_wlt_evdc_l
                  (evdc_id, crtn_id, orgn_id, acnt_id, ntwk_cd, corr_id, req_hash, oprtn_dvcd, qry_crsr, vndr_wlt_id,
                   body, body_len, body_hash, obs_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (:id, :intentId, :originId, :accountId, :network, :correlationId, :requestHash, :operation, :cursor, :knownWalletId,
                        :body, octet_length(:body), encode(sha256(:body), 'hex'), :observedAt,
                        :employeeNo, :branchCode, :employeeNo, :branchCode)
                RETURNING body_hash
                """.trimIndent(),
                mapOf(
                    "id" to id,
                    "intentId" to context.intentId,
                    "originId" to context.scope.origin.originId,
                    "accountId" to context.scope.accountId,
                    "network" to context.scope.network,
                    "correlationId" to context.correlationId,
                    "requestHash" to context.requestHash,
                    "operation" to context.operation.name,
                    "cursor" to context.cursor,
                    "knownWalletId" to context.knownWalletId,
                    "body" to body,
                    "observedAt" to context.observedAt,
                    "employeeNo" to SystemAudit.EMPNO,
                    "branchCode" to SystemAudit.BRCD,
                ),
                String::class.java,
            )
        return StoredNetworkWalletEvidence(
            REFERENCE_PREFIX + id,
            checkNotNull(storedHash) { "Network wallet evidence hash was not returned" },
        )
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    override fun find(reference: String): NetworkWalletEvidenceRecord? {
        val id = reference.removePrefix(REFERENCE_PREFIX)
        require(reference.startsWith(REFERENCE_PREFIX) && id.isNotBlank() && id == id.trim() && id.length <= 64) {
            "Unsupported network wallet evidence reference"
        }
        return jdbc
            .query(
                """
                SELECT crtn_id, orgn_id, acnt_id, ntwk_cd, corr_id, req_hash, oprtn_dvcd, qry_crsr, vndr_wlt_id, body_len, body_hash, obs_dttm
                FROM bcm_ntwk_wlt_evdc_l WHERE evdc_id = :id
                """.trimIndent(),
                mapOf("id" to id),
            ) { row, _ ->
                NetworkWalletEvidenceRecord(
                    reference,
                    row.getString("crtn_id"),
                    row.getString("orgn_id"),
                    row.getString("acnt_id"),
                    row.getString("ntwk_cd"),
                    row.getString("corr_id"),
                    row.getString("req_hash"),
                    NetworkWalletEvidenceOperation.valueOf(row.getString("oprtn_dvcd")),
                    row.getString("qry_crsr"),
                    row.getString("vndr_wlt_id"),
                    row.getInt("body_len"),
                    row.getString("body_hash"),
                    row.getString("obs_dttm"),
                )
            }.singleOrNull()
    }

    companion object {
        /** 이 어댑터가 발급하는 참조 형식. 임의 URI는 보관을 보장하지 않으므로 다른 형식은 조회를 거절한다. */
        const val REFERENCE_PREFIX = "bcm-evidence://network-wallet/"
    }
}
