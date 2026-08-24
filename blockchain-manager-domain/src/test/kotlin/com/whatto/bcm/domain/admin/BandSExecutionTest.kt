package com.whatto.bcm.domain.admin

import com.whatto.bcm.domain.admin.fixture.BandSFixture.coldDepositItem
import com.whatto.bcm.domain.admin.fixture.BandSFixture.externalItem
import com.whatto.bcm.domain.admin.fixture.BandSFixture.hotRedistributeItem
import com.whatto.bcm.domain.admin.fixture.BandSFixture.internalItem
import com.whatto.bcm.domain.admin.fixture.BandSFixture.proposal
import com.whatto.bcm.domain.admin.fixture.BandSFixture.snapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class BandSExecutionTest {
    private val now = Instant.parse("2026-08-17T09:10:00Z")
    private val boundary =
        BandSExecutionBoundary(
            omnibusVaults = mapOf("BASE" to "omnibus-base"),
            withdrawalPoolVaults = mapOf("BASE" to setOf("withdrawal-pool-base")),
            fixedColdAddresses = mapOf(BandSNetworkAsset("BASE", "USDC") to "cold-base-usdc"),
        )

    @Test
    fun `누락되거나 만료된 입력 snapshot은 실행 제안을 차단한다`() {
        assertThatThrownBy {
            BandSExecutionGuard.validate(
                snapshot(complete = false, issueCodes = listOf("FX_MISSING")),
                proposal(),
                "policy-v3",
                boundary,
                now,
            )
        }.isInstanceOf(IncompleteBandSInput::class.java)

        assertThatThrownBy {
            BandSExecutionGuard.validate(snapshot(expiresAt = now), proposal(), "policy-v3", boundary, now)
        }.isInstanceOf(ExpiredBandSSnapshot::class.java)
    }

    @Test
    fun `simulation과 실행의 정책 version 또는 input hash가 다르면 stale로 거절한다`() {
        assertThatThrownBy {
            BandSExecutionGuard.validate(snapshot(), proposal(policyVersionId = "policy-v4"), "policy-v3", boundary, now)
        }.isInstanceOf(StaleBandSProposal::class.java)

        assertThatThrownBy {
            BandSExecutionGuard.validate(snapshot(), proposal(inputHash = "e".repeat(64)), "policy-v3", boundary, now)
        }.isInstanceOf(StaleBandSProposal::class.java)
    }

    @Test
    fun `hot to cold는 단일 omnibus vault와 고정 cold 주소만 허용한다`() {
        val valid = proposal(items = listOf(internalItem(), externalItem(sequence = 2, dependsOnSequence = 1)))
        assertThat(BandSExecutionGuard.validate(snapshot(), valid, "policy-v3", boundary, now)).isEqualTo(valid)

        assertThatThrownBy {
            BandSExecutionGuard.validate(
                snapshot(),
                proposal(items = listOf(internalItem(sourceVaultId = "other-hot-vault"))),
                "policy-v3",
                boundary,
                now,
            )
        }.isInstanceOf(BandSBoundaryViolation::class.java)
        assertThatThrownBy {
            BandSExecutionGuard.validate(
                snapshot(),
                proposal(items = listOf(externalItem(sourceVaultId = "other-vault"))),
                "policy-v3",
                boundary,
                now,
            )
        }.isInstanceOf(BandSBoundaryViolation::class.java)

        assertThatThrownBy {
            BandSExecutionGuard.validate(
                snapshot(),
                proposal(items = listOf(externalItem(destinationAddress = "unknown-cold"))),
                "policy-v3",
                boundary,
                now,
            )
        }.isInstanceOf(BandSBoundaryViolation::class.java)
        assertThatThrownBy {
            BandSExecutionGuard.validate(
                snapshot(),
                proposal(items = listOf(externalItem(destinationAddress = null))),
                "policy-v3",
                boundary.copy(fixedColdAddresses = emptyMap()),
                now,
            )
        }.isInstanceOf(BandSBoundaryViolation::class.java)
    }

    @Test
    fun `item dependency는 앞선 item만 가리키고 omnibus 집결 뒤 외부 전송을 연다`() {
        val valid = proposal(items = listOf(internalItem(), externalItem(sequence = 2, dependsOnSequence = 1)))
        assertThat(BandSExecutionGuard.validate(snapshot(), valid, "policy-v3", boundary, now)).isEqualTo(valid)

        val invalid = proposal(items = listOf(externalItem(sequence = 1, dependsOnSequence = 2), internalItem(sequence = 2)))
        assertThatThrownBy {
            BandSExecutionGuard.validate(snapshot(), invalid, "policy-v3", boundary, now)
        }.isInstanceOf(BandSDependencyViolation::class.java)
    }

    @Test
    fun `cold to hot은 omnibus 입금 확인 뒤 출금 풀 재분배만 허용한다`() {
        val valid =
            proposal(
                direction = BandSDirection.COLD_TO_HOT,
                items = listOf(coldDepositItem(), hotRedistributeItem()),
            )
        assertThat(BandSExecutionGuard.validate(snapshot(), valid, "policy-v3", boundary, now)).isEqualTo(valid)

        assertThatThrownBy {
            BandSExecutionGuard.validate(
                snapshot(),
                proposal(
                    direction = BandSDirection.COLD_TO_HOT,
                    items = listOf(coldDepositItem(destinationVaultId = "other-vault")),
                ),
                "policy-v3",
                boundary,
                now,
            )
        }.isInstanceOf(BandSBoundaryViolation::class.java)
        assertThatThrownBy {
            BandSExecutionGuard.validate(
                snapshot(),
                proposal(
                    direction = BandSDirection.COLD_TO_HOT,
                    items = listOf(hotRedistributeItem(sequence = 1, dependsOnSequence = null, sourceVaultId = "other-vault")),
                ),
                "policy-v3",
                boundary,
                now,
            )
        }.isInstanceOf(BandSBoundaryViolation::class.java)
        assertThatThrownBy {
            BandSExecutionGuard.validate(
                snapshot(),
                proposal(
                    direction = BandSDirection.COLD_TO_HOT,
                    items =
                        listOf(
                            hotRedistributeItem(
                                sequence = 1,
                                dependsOnSequence = null,
                                destinationVaultId = "other-hot-vault",
                            ),
                        ),
                ),
                "policy-v3",
                boundary,
                now,
            )
        }.isInstanceOf(BandSBoundaryViolation::class.java)
    }

    @Test
    fun `item별 대사 결과에서 완료 부분실패 실패 실행중을 파생한다`() {
        assertThat(BandSExecutionStatus.derive(listOf(BandSItemState(1, BandSExecutionEventStatus.RECONCILED)), 1))
            .isEqualTo(BandSExecutionStatus.COMPLETED)
        assertThat(
            BandSExecutionStatus.derive(
                listOf(
                    BandSItemState(1, BandSExecutionEventStatus.RECONCILED),
                    BandSItemState(2, BandSExecutionEventStatus.FAILED),
                ),
                2,
            ),
        ).isEqualTo(BandSExecutionStatus.PARTIAL)
        assertThat(BandSExecutionStatus.derive(listOf(BandSItemState(1, BandSExecutionEventStatus.RELEASED)), 1))
            .isEqualTo(BandSExecutionStatus.FAILED)
        assertThat(BandSExecutionStatus.derive(listOf(BandSItemState(1, BandSExecutionEventStatus.SUBMITTED)), 1))
            .isEqualTo(BandSExecutionStatus.EXECUTING)
    }

    @Test
    fun `예약 item은 dependency 대사 뒤에만 제출을 시작하고 intent는 같은 제출을 재개한다`() {
        val item = externalItem(sequence = 2, dependsOnSequence = 1)

        assertThatThrownBy {
            BandSItemSubmissionGuard.decide(
                item,
                listOf(
                    BandSItemState(1, BandSExecutionEventStatus.FINALIZED),
                    BandSItemState(2, BandSExecutionEventStatus.RESERVED),
                ),
            )
        }.isInstanceOf(BandSDependencyViolation::class.java)

        assertThat(
            BandSItemSubmissionGuard.decide(
                item,
                listOf(
                    BandSItemState(1, BandSExecutionEventStatus.RECONCILED),
                    BandSItemState(2, BandSExecutionEventStatus.RESERVED),
                ),
            ),
        ).isEqualTo(BandSItemSubmissionDecision.START)
        assertThat(
            BandSItemSubmissionGuard.decide(
                item,
                listOf(
                    BandSItemState(1, BandSExecutionEventStatus.RECONCILED),
                    BandSItemState(2, BandSExecutionEventStatus.SUBMIT_INTENT),
                ),
            ),
        ).isEqualTo(BandSItemSubmissionDecision.RESUME)
    }

    @Test
    fun `이미 제출한 item은 멱등 완료로 보고 cold deposit과 실패 item은 제출하지 않는다`() {
        assertThat(
            BandSItemSubmissionGuard.decide(
                externalItem(),
                listOf(BandSItemState(1, BandSExecutionEventStatus.SUBMITTED)),
            ),
        ).isEqualTo(BandSItemSubmissionDecision.ALREADY_SUBMITTED)

        assertThatThrownBy {
            BandSItemSubmissionGuard.decide(
                internalItem().copy(legType = BandSLegType.COLD_DEPOSIT, sourceVaultId = null),
                listOf(BandSItemState(1, BandSExecutionEventStatus.RESERVED)),
            )
        }.isInstanceOf(UnsupportedBandSItemSubmission::class.java)
        assertThatThrownBy {
            BandSItemSubmissionGuard.decide(
                externalItem(),
                listOf(BandSItemState(1, BandSExecutionEventStatus.FAILED)),
            )
        }.isInstanceOf(BandSItemNotSubmittable::class.java)
    }
}
