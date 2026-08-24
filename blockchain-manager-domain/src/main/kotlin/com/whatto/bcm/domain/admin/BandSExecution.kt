package com.whatto.bcm.domain.admin

import java.math.BigDecimal
import java.time.Instant

enum class BandSDirection {
    HOT_TO_COLD,
    COLD_TO_HOT,
}

enum class BandSLegType {
    /** 영속/API 호환 코드명이다. 1차 설계의 실제 목적지는 treasury egress가 아니라 omnibus다. */
    INTERNAL_TO_EGRESS,
    EXTERNAL_COLD,
    COLD_DEPOSIT,
    HOT_REDISTRIBUTE,
}

data class BandSNetworkAsset(
    val network: String,
    val tokenSymbol: String,
)

data class BandSInputSnapshot(
    val snapshotId: String,
    val sourceRequestId: String,
    val policyVersionId: String,
    val snapshotHash: String,
    val inputHash: String,
    val observedAt: Instant,
    val expiresAt: Instant,
    val complete: Boolean,
    val totalAssetKrwAmount: BigDecimal,
    val observedHotKrwAmount: BigDecimal,
    val observedColdKrwAmount: BigDecimal,
    val effectiveHotKrwAmount: BigDecimal,
    val hotRatio: BigDecimal,
    val lowerRatio: BigDecimal,
    val targetRatio: BigDecimal,
    val upperRatio: BigDecimal,
    val inputPayload: String,
    val issueCodes: List<String>,
)

data class BandSProposal(
    val proposalId: String,
    val sourceProposalId: String,
    val snapshotId: String,
    val policyVersionId: String,
    val direction: BandSDirection,
    val proposalHash: String,
    val inputHash: String,
    val totalKrwAmount: BigDecimal,
    val afterHotRatio: BigDecimal,
    val executable: Boolean,
    val blockReasons: List<String>,
    val proposalPayload: String,
    val items: List<BandSProposalItem>,
)

data class BandSProposalItem(
    val sequence: Int,
    val dependsOnSequence: Int?,
    val legType: BandSLegType,
    val network: String,
    val tokenSymbol: String,
    val sourceVaultId: String?,
    val destinationVaultId: String?,
    val destinationAddress: String?,
    val amount: BigDecimal,
    val krwAmount: BigDecimal,
    val expectedFeeAmount: BigDecimal,
    val itemHash: String,
    val executable: Boolean,
    val blockReason: String?,
)

data class BandSExecutionBoundary(
    val omnibusVaults: Map<String, String>,
    val withdrawalPoolVaults: Map<String, Set<String>>,
    val fixedColdAddresses: Map<BandSNetworkAsset, String>,
)

sealed class BandSValidationException(
    message: String,
) : RuntimeException(message)

class IncompleteBandSInput(
    snapshotId: String,
    issueCodes: List<String>,
) : BandSValidationException("band S snapshot $snapshotId is incomplete: ${issueCodes.joinToString(",")}")

class ExpiredBandSSnapshot(
    snapshotId: String,
) : BandSValidationException("band S snapshot $snapshotId is expired")

class StaleBandSProposal(
    proposalId: String,
) : BandSValidationException("band S proposal $proposalId does not match the active snapshot and policy")

class BlockedBandSProposal(
    proposalId: String,
) : BandSValidationException("band S proposal $proposalId is blocked")

class BandSBoundaryViolation(
    sequence: Int,
) : BandSValidationException("band S proposal item $sequence violates the execution boundary")

class BandSDependencyViolation(
    sequence: Int,
) : BandSValidationException("band S proposal item $sequence has an invalid dependency")

class UnsupportedBandSItemSubmission(
    sequence: Int,
) : BandSValidationException("band S proposal item $sequence is observed externally and cannot be submitted by BCM")

class BandSItemNotSubmittable(
    sequence: Int,
    status: BandSExecutionEventStatus,
) : BandSValidationException("band S proposal item $sequence cannot be submitted from $status")

object BandSExecutionGuard {
    fun validate(
        snapshot: BandSInputSnapshot,
        proposal: BandSProposal,
        activePolicyVersionId: String,
        boundary: BandSExecutionBoundary,
        now: Instant,
    ): BandSProposal {
        if (!snapshot.complete || snapshot.issueCodes.isNotEmpty()) {
            throw IncompleteBandSInput(snapshot.snapshotId, snapshot.issueCodes)
        }
        if (!now.isBefore(snapshot.expiresAt)) {
            throw ExpiredBandSSnapshot(snapshot.snapshotId)
        }
        if (snapshot.policyVersionId != activePolicyVersionId ||
            proposal.policyVersionId != activePolicyVersionId ||
            proposal.snapshotId != snapshot.snapshotId ||
            proposal.inputHash != snapshot.inputHash
        ) {
            throw StaleBandSProposal(proposal.proposalId)
        }
        if (!proposal.executable ||
            proposal.blockReasons.isNotEmpty() ||
            proposal.items.isEmpty() ||
            proposal.items.sumOf(BandSProposalItem::krwAmount).compareTo(proposal.totalKrwAmount) != 0 ||
            proposal.items.any { !it.executable || it.blockReason != null }
        ) {
            throw BlockedBandSProposal(proposal.proposalId)
        }
        proposal.items.sortedBy(BandSProposalItem::sequence).forEach { item ->
            validateDependency(item)
            validateBoundary(proposal.direction, item, boundary)
        }
        return proposal
    }

    private fun validateDependency(item: BandSProposalItem) {
        val dependency = item.dependsOnSequence ?: return
        if (dependency >= item.sequence || dependency <= 0) {
            throw BandSDependencyViolation(item.sequence)
        }
    }

    private fun validateBoundary(
        direction: BandSDirection,
        item: BandSProposalItem,
        boundary: BandSExecutionBoundary,
    ) {
        val omnibus =
            boundary.omnibusVaults[item.network]
                ?: throw BandSBoundaryViolation(item.sequence)
        val valid =
            when (direction) {
                BandSDirection.HOT_TO_COLD ->
                    when (item.legType) {
                        BandSLegType.INTERNAL_TO_EGRESS ->
                            item.sourceVaultId in boundary.withdrawalPoolVaults[item.network].orEmpty() &&
                                item.destinationVaultId == omnibus &&
                                item.destinationAddress == null

                        BandSLegType.EXTERNAL_COLD ->
                            item.sourceVaultId == omnibus &&
                                item.destinationVaultId == null &&
                                item.destinationAddress == fixedColdAddress(boundary, item)

                        else -> false
                    }

                BandSDirection.COLD_TO_HOT ->
                    when (item.legType) {
                        BandSLegType.COLD_DEPOSIT ->
                            item.sourceVaultId == null && item.destinationVaultId == omnibus && item.destinationAddress == null

                        BandSLegType.HOT_REDISTRIBUTE ->
                            item.sourceVaultId == omnibus &&
                                item.destinationVaultId in boundary.withdrawalPoolVaults[item.network].orEmpty() &&
                                item.destinationAddress == null

                        else -> false
                    }
            }
        if (!valid) throw BandSBoundaryViolation(item.sequence)
    }

    private fun fixedColdAddress(
        boundary: BandSExecutionBoundary,
        item: BandSProposalItem,
    ): String =
        boundary.fixedColdAddresses[BandSNetworkAsset(item.network, item.tokenSymbol)]
            ?.takeIf(String::isNotBlank)
            ?: throw BandSBoundaryViolation(item.sequence)
}

enum class BandSExecutionEventStatus {
    RESERVED,
    SUBMIT_INTENT,
    SUBMITTED,
    FINALIZED,
    FAILED,
    RECONCILED,
    RELEASED,
}

data class BandSItemState(
    val sequence: Int,
    val status: BandSExecutionEventStatus,
)

enum class BandSItemSubmissionDecision {
    START,
    RESUME,
    ALREADY_SUBMITTED,
}

object BandSItemSubmissionGuard {
    fun decide(
        item: BandSProposalItem,
        itemStates: List<BandSItemState>,
    ): BandSItemSubmissionDecision {
        if (item.legType == BandSLegType.COLD_DEPOSIT) {
            throw UnsupportedBandSItemSubmission(item.sequence)
        }
        val current =
            itemStates.singleOrNull { it.sequence == item.sequence }
                ?: throw BandSItemNotSubmittable(item.sequence, BandSExecutionEventStatus.RELEASED)
        item.dependsOnSequence?.let { dependencySequence ->
            val dependency = itemStates.singleOrNull { it.sequence == dependencySequence }
            if (dependency?.status != BandSExecutionEventStatus.RECONCILED) {
                throw BandSDependencyViolation(item.sequence)
            }
        }
        return when (current.status) {
            BandSExecutionEventStatus.RESERVED -> BandSItemSubmissionDecision.START
            BandSExecutionEventStatus.SUBMIT_INTENT -> BandSItemSubmissionDecision.RESUME
            BandSExecutionEventStatus.SUBMITTED,
            BandSExecutionEventStatus.FINALIZED,
            BandSExecutionEventStatus.RECONCILED,
            -> BandSItemSubmissionDecision.ALREADY_SUBMITTED

            BandSExecutionEventStatus.FAILED,
            BandSExecutionEventStatus.RELEASED,
            -> throw BandSItemNotSubmittable(item.sequence, current.status)
        }
    }
}

enum class BandSExecutionStatus {
    EXECUTING,
    PARTIAL,
    COMPLETED,
    FAILED,
    ;

    companion object {
        fun derive(
            itemStates: List<BandSItemState>,
            itemCount: Int,
        ): BandSExecutionStatus {
            if (itemStates.size < itemCount) return EXECUTING
            val reconciled = itemStates.count { it.status == BandSExecutionEventStatus.RECONCILED }
            val failed = itemStates.count { it.status in setOf(BandSExecutionEventStatus.FAILED, BandSExecutionEventStatus.RELEASED) }
            return when {
                reconciled == itemCount -> COMPLETED
                reconciled > 0 && reconciled + failed == itemCount -> PARTIAL
                failed == itemCount -> FAILED
                else -> EXECUTING
            }
        }
    }
}
