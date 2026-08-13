package com.whatto.bcm.infra.persistence.job

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import

@DataJdbcTest
@Import(JobStateJdbcAdapter::class)
class JobStatePersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var jobs: JobStateJdbcAdapter

    @Test
    fun `실행 heartbeat는 upsert하고 성공 시각은 성공한 주기에만 갱신한다`() {
        jobs.markStarted("submission-recovery", "20260807120000")

        assertThat(jobs.find("submission-recovery")?.lastRunAt).isEqualTo("20260807120000")
        assertThat(jobs.find("submission-recovery")?.lastSucceededAt).isNull()

        jobs.markSucceeded("submission-recovery", "20260807120010")
        jobs.markStarted("submission-recovery", "20260807130000")

        assertThat(jobs.find("submission-recovery")?.lastRunAt).isEqualTo("20260807130000")
        assertThat(jobs.find("submission-recovery")?.lastSucceededAt).isEqualTo("20260807120010")
    }
}
