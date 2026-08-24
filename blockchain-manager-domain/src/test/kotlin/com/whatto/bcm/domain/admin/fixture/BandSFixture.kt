package com.whatto.bcm.domain.admin.fixture

import com.whatto.bcm.domain.admin.BandSDirection
import com.whatto.bcm.domain.admin.BandSInputSnapshot
import com.whatto.bcm.domain.admin.BandSLegType
import com.whatto.bcm.domain.admin.BandSProposal
import com.whatto.bcm.domain.admin.BandSProposalItem
import java.math.BigDecimal
import java.time.Instant

object BandSFixture {
    private val observedAt = Instant.parse("2026-08-17T09:00:00Z")

    fun snapshot(
        policyVersionId: String = "policy-v3",
        complete: Boolean = true,
        issueCodes: List<String> = emptyList(),
        expiresAt: Instant = observedAt.plusSeconds(1800),
    ) = BandSInputSnapshot(
        snapshotId = "snapshot-1",
        sourceRequestId = "daw-request-1",
        policyVersionId = policyVersionId,
        snapshotHash = "a".repeat(64),
        inputHash = "b".repeat(64),
        observedAt = observedAt,
        expiresAt = expiresAt,
        complete = complete,
        totalAssetKrwAmount = BigDecimal("1000000"),
        observedHotKrwAmount = BigDecimal("250000"),
        observedColdKrwAmount = BigDecimal("750000"),
        effectiveHotKrwAmount = BigDecimal("240000"),
        hotRatio = BigDecimal("24"),
        lowerRatio = BigDecimal("8"),
        targetRatio = BigDecimal("12.5"),
        upperRatio = BigDecimal("18"),
        inputPayload = "{}",
        issueCodes = issueCodes,
    )

    fun proposal(
        policyVersionId: String = "policy-v3",
        snapshotId: String = "snapshot-1",
        inputHash: String = "b".repeat(64),
        direction: BandSDirection = BandSDirection.HOT_TO_COLD,
        executable: Boolean = true,
        blockReasons: List<String> = emptyList(),
        items: List<BandSProposalItem> = listOf(externalItem()),
    ) = BandSProposal(
        proposalId = "proposal-1",
        sourceProposalId = "daw-proposal-1",
        snapshotId = snapshotId,
        policyVersionId = policyVersionId,
        direction = direction,
        proposalHash = "c".repeat(64),
        inputHash = inputHash,
        totalKrwAmount = items.sumOf(BandSProposalItem::krwAmount),
        afterHotRatio = BigDecimal("12.5"),
        executable = executable,
        blockReasons = blockReasons,
        proposalPayload = "{}",
        items = items,
    )

    fun internalItem(
        sequence: Int = 1,
        sourceVaultId: String = "withdrawal-pool-base",
        destinationVaultId: String = "omnibus-base",
    ) = BandSProposalItem(
        sequence = sequence,
        dependsOnSequence = null,
        legType = BandSLegType.INTERNAL_TO_EGRESS,
        network = "BASE",
        tokenSymbol = "USDC",
        sourceVaultId = sourceVaultId,
        destinationVaultId = destinationVaultId,
        destinationAddress = null,
        amount = BigDecimal("100"),
        krwAmount = BigDecimal("140000"),
        expectedFeeAmount = BigDecimal.ZERO,
        itemHash = "d".repeat(64),
        executable = true,
        blockReason = null,
    )

    fun externalItem(
        sequence: Int = 1,
        dependsOnSequence: Int? = null,
        sourceVaultId: String = "omnibus-base",
        destinationAddress: String? = "cold-base-usdc",
    ) = BandSProposalItem(
        sequence = sequence,
        dependsOnSequence = dependsOnSequence,
        legType = BandSLegType.EXTERNAL_COLD,
        network = "BASE",
        tokenSymbol = "USDC",
        sourceVaultId = sourceVaultId,
        destinationVaultId = null,
        destinationAddress = destinationAddress,
        amount = BigDecimal("100"),
        krwAmount = BigDecimal("140000"),
        expectedFeeAmount = BigDecimal("0.1"),
        itemHash = sequence.toString().padStart(64, 'd'),
        executable = true,
        blockReason = null,
    )

    fun coldDepositItem(
        sequence: Int = 1,
        destinationVaultId: String = "omnibus-base",
    ) = BandSProposalItem(
        sequence = sequence,
        dependsOnSequence = null,
        legType = BandSLegType.COLD_DEPOSIT,
        network = "BASE",
        tokenSymbol = "USDC",
        sourceVaultId = null,
        destinationVaultId = destinationVaultId,
        destinationAddress = null,
        amount = BigDecimal("100"),
        krwAmount = BigDecimal("140000"),
        expectedFeeAmount = BigDecimal.ZERO,
        itemHash = sequence.toString().padStart(64, 'e'),
        executable = true,
        blockReason = null,
    )

    fun hotRedistributeItem(
        sequence: Int = 2,
        dependsOnSequence: Int? = 1,
        sourceVaultId: String = "omnibus-base",
        destinationVaultId: String = "withdrawal-pool-base",
    ) = BandSProposalItem(
        sequence = sequence,
        dependsOnSequence = dependsOnSequence,
        legType = BandSLegType.HOT_REDISTRIBUTE,
        network = "BASE",
        tokenSymbol = "USDC",
        sourceVaultId = sourceVaultId,
        destinationVaultId = destinationVaultId,
        destinationAddress = null,
        amount = BigDecimal("100"),
        krwAmount = BigDecimal("140000"),
        expectedFeeAmount = BigDecimal.ZERO,
        itemHash = sequence.toString().padStart(64, 'f'),
        executable = true,
        blockReason = null,
    )
}
