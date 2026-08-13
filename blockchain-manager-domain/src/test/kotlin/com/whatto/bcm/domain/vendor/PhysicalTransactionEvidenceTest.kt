package com.whatto.bcm.domain.vendor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PhysicalTransactionEvidenceTest {
    @Test
    fun `confirmation 또는 COMPLETED만 물리 승자 증거다`() {
        assertThat(PhysicalTransactionEvidence.hasSucceeded(observation("CONFIRMING", 1))).isTrue()
        assertThat(PhysicalTransactionEvidence.hasSucceeded(observation("COMPLETED", 0))).isTrue()
        assertThat(PhysicalTransactionEvidence.hasSucceeded(observation("CONFIRMING", 0))).isFalse()
        assertThat(PhysicalTransactionEvidence.hasSucceeded(observation("FAILED", 0))).isFalse()
    }

    private fun observation(
        rawStatus: String,
        confirmationCount: Int,
    ) = VendorStatusObservation(rawStatus, null, confirmationCount)
}
