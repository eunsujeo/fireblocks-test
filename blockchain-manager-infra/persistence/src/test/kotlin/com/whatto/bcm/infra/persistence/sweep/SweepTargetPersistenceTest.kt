package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import com.whatto.bcm.infra.persistence.sweep.fixture.SweepTargetFixture.fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@DataJdbcTest
@Import(SweepTargetJdbcAdapter::class)
class SweepTargetPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var sweepTargets: SweepTargetJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `같은 계정 자산을 다시 마킹하면 최초 행을 보존한다`() {
        val first = fixture()
        val duplicate = fixture(registeredAt = "20260810130000")

        assertThat(sweepTargets.insertIfAbsent(first)).isTrue()
        assertThat(sweepTargets.insertIfAbsent(duplicate)).isFalse()

        assertThat(sweepTargets.findByKey(first.key)).isEqualTo(first)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bcm_swp_trgt", Long::class.java)).isEqualTo(1)
    }

    @Test
    fun `승인된 요청의 미제출 대상만 최초 마킹 순서와 상한에 맞춰 조회한다`() {
        val newest = fixture(accountId = "acct-3", registeredAt = "20260810120300")
        val oldest = fixture(accountId = "acct-1", registeredAt = "20260810120100")
        val middle = fixture(accountId = "acct-2", registeredAt = "20260810120200")
        sweepTargets.insertIfAbsent(newest)
        sweepTargets.insertIfAbsent(oldest)
        sweepTargets.insertIfAbsent(middle)
        val newestItemId = insertPendingRequest(newest)
        val oldestItemId = insertPendingRequest(oldest)
        val middleItemId = insertPendingRequest(middle)

        val pending = sweepTargets.findPending(setOf("ETHEREUM"), 2)

        assertThat(pending)
            .containsExactly(
                pendingTarget(oldest, oldestItemId),
                pendingTarget(middle, middleItemId),
            )
        assertThat(pending).noneMatch { it.pendingSweepRequestItemId == newestItemId }
    }

    @Test
    fun `요청 원장이 없는 target은 BAT 후보에서 제외한다`() {
        val orphan = fixture(accountId = "acct-orphan")
        sweepTargets.insertIfAbsent(orphan)

        assertThat(sweepTargets.findPending(setOf("ETHEREUM"), 10)).isEmpty()
    }

    @Test
    fun `같은 계정 자산의 가장 오래된 미완료 요청 항목을 반환한다`() {
        val target = fixture(accountId = "acct-ordered")
        sweepTargets.insertIfAbsent(target)
        val oldestItemId = insertPendingRequest(target, requestedAt = "20260810110000", discriminator = "oldest")
        insertPendingRequest(target, requestedAt = "20260810120000", discriminator = "newest")

        assertThat(sweepTargets.findPending(setOf("ETHEREUM"), 10).single().pendingSweepRequestItemId)
            .isEqualTo(oldestItemId)
    }

    @Test
    fun `0잔액 완료는 가장 오래된 요청 하나씩 종결하고 마지막 요청 뒤 target 삭제를 허용한다`() {
        val target = fixture(accountId = "acct-no-sweep")
        sweepTargets.insertIfAbsent(target)
        val oldestItemId = insertPendingRequest(target, requestedAt = "20260810110000", discriminator = "oldest")
        val newestItemId = insertPendingRequest(target, requestedAt = "20260810120000", discriminator = "newest")

        assertThat(sweepTargets.completeOldestPendingWithoutExecution(target.key, "20260810130000"))
            .extracting("sweepRequestItemId")
            .isEqualTo(oldestItemId)
        assertThat(requestItemStatus(oldestItemId)).isEqualTo("COMPLETED")
        assertThat(requestItemStatus(newestItemId)).isEqualTo("PENDING")
        assertThat(sweepTargets.hasUnfinishedRequest(target.key)).isTrue()

        assertThat(sweepTargets.completeOldestPendingWithoutExecution(target.key, "20260810130100"))
            .extracting("sweepRequestItemId")
            .isEqualTo(newestItemId)
        assertThat(requestItemStatus(newestItemId)).isEqualTo("COMPLETED")
        assertThat(sweepTargets.hasUnfinishedRequest(target.key)).isFalse()
        assertThat(sweepTargets.deletePending(target.key)).isTrue()
    }

    @Test
    fun `제한 출시 네트워크를 DB limit 전에 적용한다`() {
        repeat(3) { index ->
            val target = fixture(accountId = "base-$index", network = "BASE", registeredAt = "20260810120${index}00")
            sweepTargets.insertIfAbsent(target)
            insertPendingRequest(target)
        }
        val enabled = fixture(accountId = "eth-enabled", network = "ETHEREUM", registeredAt = "20260810130000")
        sweepTargets.insertIfAbsent(enabled)
        insertPendingRequest(enabled)

        assertThat(sweepTargets.findPending(setOf("ETHEREUM"), 1).map { it.accountId })
            .containsExactly("eth-enabled")
    }

    @Test
    fun `실행 준비 잠금 조회는 아직 claim되지 않은 대상을 반환한다`() {
        val pending = fixture(accountId = "acct-pending")
        sweepTargets.insertIfAbsent(pending)

        assertThat(sweepTargets.findPendingForUpdate(pending.key)).isEqualTo(pending)
    }

    @Test
    fun `최소 미달 삭제는 claim되지 않은 대상에 적용한다`() {
        val pending = fixture(accountId = "acct-pending")
        sweepTargets.insertIfAbsent(pending)

        assertThat(sweepTargets.deletePending(pending.key)).isTrue()

        assertThat(sweepTargets.findByKey(pending.key)).isNull()
    }

    private fun insertPendingRequest(
        target: com.whatto.bcm.domain.sweep.SweepTarget,
        requestedAt: String = target.registeredAt,
        discriminator: String = requestedAt,
    ): String {
        val requestId = stableId("request:${target.accountId}:${target.network}:${target.symbol}:$discriminator")
        val itemId = stableId("item:$requestId")
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'CU', ?, ?, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (acnt_id) DO NOTHING
            """.trimIndent(),
            target.accountId,
            "ref-${target.accountId}",
            "vault-${target.accountId}",
            requestedAt,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_l
              (swp_req_id, ext_swp_req_id, req_hash, ntwk_cd, tkn_smbl, swp_req_stcd,
               item_cnt, req_dttm, fnsh_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, ?, ?, 'ACCEPTED', 1, ?, NULL,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            requestId,
            "external-$requestId",
            "a".repeat(64),
            target.network,
            target.symbol,
            requestedAt,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_item_l
              (swp_req_item_id, swp_req_id, item_seq, acnt_id, swp_req_item_stcd, last_fail_cd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 1, ?, 'PENDING', NULL, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            itemId,
            requestId,
            target.accountId,
        )
        return itemId
    }

    private fun stableId(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    private fun pendingTarget(
        target: com.whatto.bcm.domain.sweep.SweepTarget,
        itemId: String,
        requestedAt: String = target.registeredAt,
    ) = target.copy(
        pendingSweepRequestItemId = itemId,
        pendingSweepRequestId = stableId("request:${target.accountId}:${target.network}:${target.symbol}:$requestedAt"),
        pendingSweepRequestedAt = requestedAt,
    )

    private fun requestItemStatus(itemId: String): String =
        jdbc.queryForObject(
            "SELECT swp_req_item_stcd FROM bcm_swp_req_item_l WHERE swp_req_item_id = ?",
            String::class.java,
            itemId,
        )!!
}
