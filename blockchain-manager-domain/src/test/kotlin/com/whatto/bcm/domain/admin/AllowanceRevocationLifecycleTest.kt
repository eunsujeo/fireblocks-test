package com.whatto.bcm.domain.admin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AllowanceRevocationLifecycleTest {
    @Test
    fun `event가 없으면 READY이고 일부만 0이면 PARTIAL이며 전부 0일 때만 COMPLETED다`() {
        assertThat(AllowanceRevocationLifecycle.executionStatus(2, emptyList()))
            .isEqualTo(AllowanceRevocationExecutionStatus.READY)

        val reserved = event(1, 1, AllowanceRevocationEventStatus.RESERVED)
        assertThat(AllowanceRevocationLifecycle.executionStatus(2, listOf(reserved)))
            .isEqualTo(AllowanceRevocationExecutionStatus.IN_PROGRESS)

        val firstZero = event(1, 2, AllowanceRevocationEventStatus.ZERO_CONFIRMED, observedAllowance = BigDecimal.ZERO)
        assertThat(AllowanceRevocationLifecycle.executionStatus(2, listOf(reserved, firstZero)))
            .isEqualTo(AllowanceRevocationExecutionStatus.PARTIAL)

        val secondZero = event(2, 2, AllowanceRevocationEventStatus.ZERO_CONFIRMED, observedAllowance = BigDecimal.ZERO)
        assertThat(AllowanceRevocationLifecycle.executionStatus(2, listOf(firstZero, secondZero)))
            .isEqualTo(AllowanceRevocationExecutionStatus.COMPLETED)
    }

    @Test
    fun `첫 event는 RESERVED여야 하고 제출 intent는 승인된 대상 externalTxId와 같아야 한다`() {
        val target = target()

        assertThatThrownBy {
            AllowanceRevocationLifecycle.validateAppend(
                target,
                emptyList(),
                event(1, 1, AllowanceRevocationEventStatus.SUBMIT_INTENT, externalTransactionId = target.externalTransactionId),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            AllowanceRevocationLifecycle.validateAppend(
                target,
                listOf(event(1, 1, AllowanceRevocationEventStatus.RESERVED)),
                event(1, 2, AllowanceRevocationEventStatus.SUBMIT_INTENT, externalTransactionId = "arv-other"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `FAILED 뒤에는 같은 제출 의도를 재개할 수 있고 관찰값 0만 ZERO_CONFIRMED다`() {
        val target = target()
        val previous =
            listOf(
                event(1, 1, AllowanceRevocationEventStatus.RESERVED),
                event(1, 2, AllowanceRevocationEventStatus.FAILED, errorCode = "VENDOR_REJECTED"),
            )
        val retry =
            event(
                1,
                3,
                AllowanceRevocationEventStatus.SUBMIT_INTENT,
                externalTransactionId = target.externalTransactionId,
            )

        AllowanceRevocationLifecycle.validateAppend(target, previous, retry)

        assertThatThrownBy {
            AllowanceRevocationLifecycle.validateAppend(
                target,
                previous,
                event(
                    1,
                    3,
                    AllowanceRevocationEventStatus.ZERO_CONFIRMED,
                    observedAllowance = BigDecimal.ONE,
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun target() =
        AllowanceRevocationTarget(
            executionId = "revocation-1",
            itemSequence = 1,
            accountId = "account-1",
            network = "BASE",
            symbol = "USDC",
            sweepContractAddress = "0x0000000000000000000000000000000000000001",
            sourceVaultId = "vault-1",
            ownerAddress = "0x0000000000000000000000000000000000000002",
            tokenContractAddress = "0x0000000000000000000000000000000000000003",
            beforeObservedAllowance = BigDecimal.TEN,
            externalTransactionId = "arv-1",
            requestHash = "a".repeat(64),
        )

    private fun event(
        itemSequence: Int,
        eventSequence: Int,
        status: AllowanceRevocationEventStatus,
        externalTransactionId: String? = null,
        vendorTransactionId: String? = null,
        observedAllowance: BigDecimal? = null,
        errorCode: String? = null,
    ) = AllowanceRevocationEvent(
        executionId = "revocation-1",
        itemSequence = itemSequence,
        eventSequence = eventSequence,
        status = status,
        externalTransactionId = externalTransactionId,
        vendorTransactionId = vendorTransactionId,
        observedAllowance = observedAllowance,
        observationPayload = "{}",
        observationHash = "b".repeat(64),
        errorCode = errorCode,
        occurredAt = java.time.Instant.parse("2026-08-18T12:00:00Z"),
        actor = AdminActor("SYSTEM", "9999", emptySet()),
    )
}
