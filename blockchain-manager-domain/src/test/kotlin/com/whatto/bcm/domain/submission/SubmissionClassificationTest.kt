package com.whatto.bcm.domain.submission

import com.whatto.bcm.domain.event.EventType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SubmissionClassificationTest {
    @Test
    fun `목적지 유형이 제출 거래 계열을 결정한다`() {
        assertThat(SubmissionRecipientType.ADDRESS.transactionType()).isEqualTo(SubmissionTransactionType.WITHDRAWAL)
        assertThat(SubmissionRecipientType.WHITELISTED.transactionType()).isEqualTo(SubmissionTransactionType.WITHDRAWAL)
        assertThat(SubmissionRecipientType.ACCOUNT.transactionType()).isEqualTo(SubmissionTransactionType.INTERNAL)
    }

    @Test
    fun `제출 거래 계열이 고객 이벤트와 sweep 무발행을 결정한다`() {
        assertThat(SubmissionTransactionType.WITHDRAWAL.customerEventType()).isEqualTo(EventType.WITHDRAWAL)
        assertThat(SubmissionTransactionType.INTERNAL.customerEventType()).isEqualTo(EventType.INTERNAL)
        assertThat(SubmissionTransactionType.SWEEP.customerEventType()).isNull()
    }
}
