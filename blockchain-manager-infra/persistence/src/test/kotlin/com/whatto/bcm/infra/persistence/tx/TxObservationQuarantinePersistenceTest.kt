package com.whatto.bcm.infra.persistence.tx

import com.whatto.bcm.domain.tx.TxObservationQuarantine
import com.whatto.bcm.domain.tx.TxObservationQuarantineEvidence
import com.whatto.bcm.domain.tx.TxObservationQuarantineSource
import com.whatto.bcm.domain.tx.TxObservationQuarantineStatus
import com.whatto.bcm.domain.vendor.VendorLookup
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(TxObservationQuarantineJdbcAdapter::class)
class TxObservationQuarantinePersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var quarantines: TxObservationQuarantineJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `격리 왕복 — 본문 길이와 해시는 DB가 계산하고 앱은 본문을 돌려받지 않는다`() {
        val stored = quarantines.open(quarantine(), "20260807120000")

        assertThat(stored.status).isEqualTo(TxObservationQuarantineStatus.OPEN)
        assertThat(stored.field).isEqualTo("amount")
        assertThat(stored.detectionCount).isEqualTo(1)
        assertThat(stored.evidence.lookup).isEqualTo(VendorLookup.PAGE)
        assertThat(stored.evidence.indexInBody).isEqualTo(2)
        // 앱 역할은 body 를 열람하지 않는다 — 길이·해시로만 본문을 가리킨다.
        assertThat(stored.evidence.body).isEmpty()
        val row = jdbc.queryForMap("SELECT body_len, body_hash, body FROM bcm_tx_qrnt_l")
        assertThat(row["body_len"]).isEqualTo(BODY.size)
        assertThat(row["body_hash"]).isEqualTo(sha256Hex(BODY))
        assertThat(row["body"] as ByteArray).isEqualTo(BODY)
    }

    @Test
    fun `같은 관찰의 반복 발견은 행을 늘리지 않고 횟수만 올린다`() {
        // 재조회할 때마다 행이 늘면 운영이 같은 사실을 여러 번 본다.
        quarantines.open(quarantine(), "20260807120000")
        val again = quarantines.open(quarantine(), "20260807130000")

        assertThat(again.detectionCount).isEqualTo(2)
        assertThat(again.firstDetectedAt).isEqualTo("20260807120000")
        assertThat(again.lastChangedAt).isEqualTo("20260807130000")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_qrnt_l", Long::class.java)).isOne()
    }

    @Test
    fun `다른 관찰은 별개의 열린 격리다`() {
        quarantines.open(quarantine(), "20260807120000")
        quarantines.open(quarantine(quarantineId = "qr-2", fingerprint = "b".repeat(64)), "20260807120000")

        assertThat(quarantines.findOpenByRoot("tx-root")).hasSize(2)
    }

    @Test
    fun `증적은 바꿀 수 없다`() {
        quarantines.open(quarantine(), "20260807120000")

        assertThatThrownBy { jdbc.update("UPDATE bcm_tx_qrnt_l SET rcrd_vl = '9'") }
            .isInstanceOf(UncategorizedSQLException::class.java)
            .hasMessageContaining("evidence is immutable")
    }

    @Test
    fun `격리 행은 지울 수 없다`() {
        quarantines.open(quarantine(), "20260807120000")

        assertThatThrownBy { jdbc.update("DELETE FROM bcm_tx_qrnt_l") }
            .isInstanceOf(UncategorizedSQLException::class.java)
            .hasMessageContaining("append-only")
    }

    @Test
    fun `상태 전이는 허용한다 — 해소 책임이 이 행에 있다`() {
        quarantines.open(quarantine(), "20260807120000")

        jdbc.update("UPDATE bcm_tx_qrnt_l SET qrnt_stcd = 'RESOLVED', last_chng_dttm = '20260807140000'")

        assertThat(quarantines.findOpenByRoot("tx-root")).isEmpty()
    }

    @Test
    fun `해소된 뒤 같은 관찰이 다시 오면 새 행이다`() {
        // 열린 격리만 관찰당 하나다 — 닫힌 뒤의 재발견은 새 사실이다.
        quarantines.open(quarantine(), "20260807120000")
        jdbc.update("UPDATE bcm_tx_qrnt_l SET qrnt_stcd = 'RESOLVED', last_chng_dttm = '20260807130000'")

        quarantines.open(quarantine(quarantineId = "qr-2"), "20260807140000")

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_qrnt_l", Long::class.java)).isEqualTo(2)
        assertThat(quarantines.findOpenByRoot("tx-root")).hasSize(1)
    }

    @Test
    fun `허용 값 밖의 유입 경로와 상태는 CHECK가 막는다`() {
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO bcm_tx_qrnt_l
                  (qrnt_id, orig_tx_id, obsv_tx_id, ntwk_cd, src_dvcd, qrnt_stcd, cnfl_fld, rcrd_vl, obsv_vl,
                   evdc_lkup, body, body_len, body_hash, obsv_fngr, frst_dtct_dttm, last_chng_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES ('qr-x', 'tx-root', 'tx-root', 'ETHEREUM', 'WEBHOOK', 'OPEN', 'amount', '1', '2',
                        'PAGE', '\x00', 1, repeat('0', 64), repeat('a', 64), '20260807120000', '20260807120000',
                        'SYSTEM', '9999', 'SYSTEM', '9999')
                """.trimIndent(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun quarantine(
        quarantineId: String = "qr-1",
        fingerprint: String = "a".repeat(64),
    ) = TxObservationQuarantine(
        quarantineId = quarantineId,
        rootVendorTransactionId = "tx-root",
        observedVendorTransactionId = "tx-physical",
        network = "ETHEREUM",
        source = TxObservationQuarantineSource.RECONCILIATION,
        status = TxObservationQuarantineStatus.OPEN,
        field = "amount",
        recordedValue = "1.5",
        observedValue = "2.5",
        evidence =
            TxObservationQuarantineEvidence(
                body = BODY,
                contentType = "application/json",
                lookup = VendorLookup.PAGE,
                indexInBody = 2,
            ),
        fingerprint = fingerprint,
        detectionCount = 1,
        firstDetectedAt = "20260807120000",
        lastChangedAt = "20260807120000",
    )

    private fun sha256Hex(bytes: ByteArray) =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val BODY = """[{"id":"tx-physical","amountInfo":{"amount":"2.5"}}]""".toByteArray()
    }
}
