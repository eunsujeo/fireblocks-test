package com.whatto.bcm.testsupport.stub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalDeterministicIdTest {
    @Test
    fun `같은 원인은 reset과 무관하게 같은 ID를 만든다`() {
        assertThat(localDeterministicId("tx-local-", "external-1"))
            .isEqualTo(localDeterministicId("tx-local-", "external-1"))
    }

    @Test
    fun `다른 거래는 서로 다른 ID를 만든다`() {
        val first = localDeterministicId("tx-local-", "external-1")
        val another = localDeterministicId("tx-local-", "external-2")

        assertThat(first).isNotEqualTo(another)
        assertThat(first).hasSizeLessThanOrEqualTo(64)
    }
}
