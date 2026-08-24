package com.whatto.bcm.testsupport.chain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FoundryToolchainTest {
    @Test
    fun `로컬 체인은 검증된 Foundry와 Anvil 고정 버전만 사용한다`() {
        val versions = FoundryToolchain().verify()

        assertThat(versions.forge).isEqualTo("1.7.1")
        assertThat(versions.anvil).isEqualTo("1.7.1")
    }
}
