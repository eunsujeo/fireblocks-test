package com.whatto.bcm.domain.tx

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 온체인 이동만 관찰되는 입금의 논리 거래 ID(03 V26·계약13) — 결정적이고 `vndr_tx_id` 폭에 들어가야 한다. */
class NetworkChainTransactionIdTest {
    @Test
    fun `같은 이동은 언제 다시 봐도 같은 ID이고 vndr_tx_id 폭에 들어간다`() {
        val id = NetworkChainTransactionId.of(NETWORK, HASH, "3")

        assertThat(id).isEqualTo(NetworkChainTransactionId.of(NETWORK, HASH, "3"))
        assertThat(id).startsWith("dfns-")
        assertThat(id).hasSize(57)
        assertThat(id.length).isLessThanOrEqualTo(NetworkChainTransactionId.MAX_LENGTH)
        // 값 자체는 원문을 드러내지 않는다 — 조사는 tx_hash 컬럼으로 한다.
        assertThat(id).doesNotContain(HASH)
    }

    @Test
    fun `네트워크·hash·순번이 다르면 다른 거래다`() {
        val base = NetworkChainTransactionId.of(NETWORK, HASH, "3")

        assertThat(NetworkChainTransactionId.of("BASE_SEPOLIA", HASH, "3")).isNotEqualTo(base)
        assertThat(NetworkChainTransactionId.of(NETWORK, OTHER_HASH, "3")).isNotEqualTo(base)
        assertThat(NetworkChainTransactionId.of(NETWORK, HASH, "4")).isNotEqualTo(base)
    }

    @Test
    fun `순번 없는 이동은 ID를 지어내지 않는다`() {
        // 명세에서 `index`는 선택이지만, 한 트랜잭션의 여러 이동이 순번 없이 같은 PK로 합쳐지면
        // 서로 다른 계정·자산의 자금이 한 논리 거래가 된다. 고유 키를 만들 수 없으면 실패한다.
        listOf("", " ").forEach { index ->
            assertThatThrownBy { NetworkChainTransactionId.of(NETWORK, HASH, index) }
                .describedAs(index)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("eventIndex")
        }
    }

    @Test
    fun `입력 경계가 흔들려 서로 다른 이동이 합쳐지지 않는다`() {
        // 구분자 대신 길이를 앞에 붙이므로 값 안에 어떤 문자가 들어와도 경계가 유지된다.
        assertThat(NetworkChainTransactionId.of("A", "BC", "D"))
            .isNotEqualTo(NetworkChainTransactionId.of("AB", "C", "D"))
        assertThat(NetworkChainTransactionId.of("A:B", HASH, "1"))
            .isNotEqualTo(NetworkChainTransactionId.of("A", ":B$HASH", "1"))
    }

    @Test
    fun `EVM hash는 대소문자 표기가 달라도 같은 거래다`() {
        assertThat(NetworkChainTransactionId.of(NETWORK, HASH.uppercase().replace("0X", "0x"), "3"))
            .isEqualTo(NetworkChainTransactionId.of(NETWORK, HASH, "3"))
        // base58 서명처럼 대소문자가 값의 일부인 형식은 정규화하지 않는다.
        val signature = "5VERv8NW3Uh2uLmveXMBLvRw6KXpkCwcgSYqxLGfpPvT"
        assertThat(NetworkChainTransactionId.of("SOLANA_DEVNET", signature, "0"))
            .isNotEqualTo(NetworkChainTransactionId.of("SOLANA_DEVNET", signature.lowercase(), "0"))
    }

    @Test
    fun `빈 입력은 거래 식별자로 받지 않는다`() {
        listOf<Pair<String, () -> String>>(
            "network" to { NetworkChainTransactionId.of(" ", HASH, "3") },
            "transactionHash" to { NetworkChainTransactionId.of(NETWORK, "", "3") },
            "eventIndex" to { NetworkChainTransactionId.of(NETWORK, HASH, " ") },
        ).forEach { (field, build) ->
            assertThatThrownBy { build() }
                .describedAs(field)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(field)
        }
    }

    private companion object {
        const val NETWORK = "ETHEREUM_SEPOLIA"
        const val HASH = "0x2a6f0c9b6bd0a7b9f4e3e0b33d2ff4c7cf9a7a0f9b4d2a8f8c1b3e5d7a9c0b11"
        const val OTHER_HASH = "0x2a6f0c9b6bd0a7b9f4e3e0b33d2ff4c7cf9a7a0f9b4d2a8f8c1b3e5d7a9c0b12"
    }
}
