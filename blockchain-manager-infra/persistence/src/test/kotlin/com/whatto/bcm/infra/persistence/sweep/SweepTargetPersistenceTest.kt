package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import com.whatto.bcm.infra.persistence.sweep.fixture.SweepTargetFixture.fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

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
    fun `미제출 대상은 최초 마킹 순서와 상한에 맞춰 조회한다`() {
        val newest = fixture(accountId = "acct-3", registeredAt = "20260810120300")
        val oldest = fixture(accountId = "acct-1", registeredAt = "20260810120100")
        val middle = fixture(accountId = "acct-2", registeredAt = "20260810120200")
        sweepTargets.insertIfAbsent(newest)
        sweepTargets.insertIfAbsent(oldest)
        sweepTargets.insertIfAbsent(middle)

        val pending = sweepTargets.findPending(2)

        assertThat(pending).containsExactly(oldest, middle)
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
}
