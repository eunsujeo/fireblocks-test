package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.admin.VaultReconciliation
import com.whatto.bcm.domain.admin.VaultReconciliationFailureException
import com.whatto.bcm.domain.admin.VaultReconciliationItemStatus
import com.whatto.bcm.domain.admin.VaultReconciliationStatus
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.infra.persistence.account.AccountJdbcAdapter
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(VaultReconciliationJdbcAdapter::class, AccountJdbcAdapter::class)
class VaultReconciliationPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var reconciliations: VaultReconciliationJdbcAdapter

    @Autowired
    lateinit var accounts: AccountJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `완주한 실행만 미확인 계정을 MISSING으로 확정하고 고정 cursor로 page한다`() {
        accounts.insert(account("acct-1", "1"))
        accounts.insert(account("acct-3", "3"))
        reconciliations.create(run("run-1"))
        claimAndStart("run-1")

        reconciliations.recordVendorPage(
            "run-1",
            "claim-run-1",
            null,
            null,
            listOf(VendorVault("1", "customer-a", 2), VendorVault("2", "orphan", 1)),
            "20260831020200",
        )
        assertThat(reconciliations.find("run-1")?.vendorPaginationDone).isTrue()
        reconciliations.complete("run-1", "claim-run-1", "20260831020300")

        val first = checkNotNull(reconciliations.findPage("run-1", null, 2))
        val second = checkNotNull(reconciliations.findPage("run-1", first.nextSequence, 2))
        assertThat(first.run.status).isEqualTo(VaultReconciliationStatus.COMPLETED)
        assertThat(first.run.resultCount).isEqualTo(3)
        assertThat(first.items.map { it.reconciliationStatus }).containsExactly(
            VaultReconciliationItemStatus.MANAGED,
            VaultReconciliationItemStatus.UNMANAGED,
        )
        assertThat(second.items.single().reconciliationStatus).isEqualTo(VaultReconciliationItemStatus.MISSING_IN_FIREBLOCKS)
        assertThat(second.nextSequence).isNull()
    }

    @Test
    fun `중간 실패는 아직 확인하지 않은 계정을 MISSING으로 노출하지 않는다`() {
        accounts.insert(account("acct-1", "1"))
        accounts.insert(account("acct-3", "3"))
        reconciliations.create(run("run-partial"))
        claimAndStart("run-partial")
        reconciliations.recordVendorPage(
            "run-partial",
            "claim-run-partial",
            null,
            "next",
            listOf(VendorVault("1", "customer-a", 2)),
            "20260831020200",
        )

        reconciliations.fail("run-partial", "claim-run-partial", "VENDOR_UNAVAILABLE", "20260831020300")

        val page = checkNotNull(reconciliations.findPage("run-partial", null, 100))
        assertThat(page.run.status).isEqualTo(VaultReconciliationStatus.PARTIAL)
        assertThat(page.run.failureCode).isEqualTo("VENDOR_UNAVAILABLE")
        assertThat(page.items.map { it.vendorVaultId }).containsExactly("1")
        assertThat(page.items).allMatch { it.reconciliationStatus != VaultReconciliationItemStatus.MISSING_IN_FIREBLOCKS }
    }

    @Test
    fun `활성 전체 대사 실행은 하나만 허용한다`() {
        reconciliations.create(run("run-active"))

        assertThatThrownBy { reconciliations.create(run("run-conflict")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `같은 vendor vault가 반복되면 page 전체를 롤백한다`() {
        reconciliations.create(run("run-duplicate"))
        claimAndStart("run-duplicate")

        assertThatThrownBy {
            reconciliations.recordVendorPage(
                "run-duplicate",
                "claim-run-duplicate",
                null,
                null,
                listOf(VendorVault("2", "orphan", 1), VendorVault("2", "orphan", 1)),
                "20260831020200",
            )
        }.isInstanceOf(VaultReconciliationFailureException::class.java)

        assertThat(reconciliations.find("run-duplicate")?.vendorPageCount).isZero()
        assertThat(reconciliations.findPage("run-duplicate", null, 100)?.items).isEmpty()
    }

    @Test
    fun `대규모 실행의 vendor 대조와 결과 cursor는 실행 선두 index를 가진다`() {
        val indexes =
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'bcm_vlt_rcnc_item_l'",
                String::class.java,
            )

        assertThat(indexes).contains("idx_bcm_vlt_rcnc_item_vault", "ux_bcm_vlt_rcnc_item_seq")
    }

    @Test
    fun `재기동 시 RUNNING 실행의 시작 snapshot에 새 계정을 추가하지 않는다`() {
        accounts.insert(account("acct-before", "1"))
        reconciliations.create(run("run-restarted"))
        claimAndStart("run-restarted")
        accounts.insert(account("acct-after", "2"))

        reconciliations.startWithAccountSnapshot("run-restarted", "claim-run-restarted", "20260831020200")
        reconciliations.recordVendorPage("run-restarted", "claim-run-restarted", null, null, emptyList(), "20260831020300")
        reconciliations.complete("run-restarted", "claim-run-restarted", "20260831020400")

        val page = checkNotNull(reconciliations.findPage("run-restarted", null, 100))
        assertThat(page.items.map { it.accountId }).containsExactly("acct-before")
    }

    @Test
    fun `vendor pagination 미완주 상태에서는 완료와 MISSING 확정을 거부한다`() {
        accounts.insert(account("acct-pending", "1"))
        reconciliations.create(run("run-incomplete"))
        claimAndStart("run-incomplete")

        reconciliations.complete("run-incomplete", "claim-run-incomplete", "20260831020200")

        val page = checkNotNull(reconciliations.findPage("run-incomplete", null, 100))
        assertThat(page.run.status).isEqualTo(VaultReconciliationStatus.RUNNING)
        assertThat(page.items).isEmpty()
    }

    @Test
    fun `동일 실행은 한 worker만 claim하고 만료 뒤 인계하며 이전 worker 변경을 거부한다`() {
        reconciliations.create(run("run-claimed"))

        assertThat(reconciliations.claim("run-claimed", "worker-1", "20260831020100", "20260831020300")).isTrue()
        assertThat(reconciliations.claim("run-claimed", "worker-2", "20260831020200", "20260831020400")).isFalse()
        assertThat(reconciliations.startWithAccountSnapshot("run-claimed", "worker-1", "20260831020100")).isTrue()
        assertThat(reconciliations.claim("run-claimed", "worker-2", "20260831020300", "20260831020500")).isTrue()

        assertThat(
            reconciliations.recordVendorPage("run-claimed", "worker-1", null, null, emptyList(), "20260831020300"),
        ).isFalse()
        assertThat(reconciliations.fail("run-claimed", "worker-1", "VENDOR_UNAVAILABLE", "20260831020300")).isFalse()
        assertThat(
            reconciliations.recordVendorPage("run-claimed", "worker-2", null, null, emptyList(), "20260831020400"),
        ).isTrue()
        assertThat(reconciliations.complete("run-claimed", "worker-2", "20260831020500")).isTrue()
    }

    @Test
    fun `검색어의 SQL wildcard 문자는 literal substring으로 대조한다`() {
        reconciliations.create(run("run-literal").copy(query = "%"))
        claimAndStart("run-literal")
        reconciliations.recordVendorPage(
            "run-literal",
            "claim-run-literal",
            null,
            null,
            listOf(VendorVault("plain", "plain", 0), VendorVault("percent%", "percent", 0)),
            "20260831020200",
        )
        reconciliations.complete("run-literal", "claim-run-literal", "20260831020300")

        val page = checkNotNull(reconciliations.findPage("run-literal", null, 100))
        assertThat(page.items.map { it.vendorVaultId }).containsExactly("percent%")
    }

    private fun claimAndStart(runId: String) {
        val claimId = "claim-$runId"
        assertThat(reconciliations.claim(runId, claimId, "20260831020000", "20260831020500")).isTrue()
        assertThat(reconciliations.startWithAccountSnapshot(runId, claimId, "20260831020100")).isTrue()
    }

    private fun account(
        accountId: String,
        vaultId: String,
    ) = Account(accountId, AccountType.CUSTOMER, "ref-$accountId", vaultId, "20260831010000")

    private fun run(runId: String) =
        VaultReconciliation(
            runId = runId,
            query = null,
            status = VaultReconciliationStatus.ACCEPTED,
            vendorPageCount = 0,
            vendorVaultCount = 0,
            resultCount = 0,
            failureCode = null,
            requestedAt = "20260831020000",
            startedAt = null,
            finishedAt = null,
        )
}
