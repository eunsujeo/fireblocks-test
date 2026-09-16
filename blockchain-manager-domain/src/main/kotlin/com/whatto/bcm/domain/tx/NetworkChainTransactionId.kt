package com.whatto.bcm.domain.tx

import java.security.MessageDigest

/**
 * 온체인 이동만 관찰되는 입금의 **논리 거래 ID**(03 V26·계약13 "논리 거래 식별자").
 *
 * Fireblocks는 입금에도 벤더 거래 ID를 주지만 Dfns는 주지 않는다 — 입금은 `txHash`·`index`만 있는 이동 사건으로만 온다.
 * `bcm_tx_l.vndr_tx_id`는 64자이고 `0x` 뺀 EVM hash가 이미 64자라 읽을 수 있는 형태로는 접두사도 순번도 넣을 자리가 없다.
 * 그래서 hash와 순번에서 **결정적으로** 파생한 ID를 쓴다 — 같은 이동은 재전달·이력 복구로 몇 번을 다시 봐도 같은 ID가 되어야
 * 전이 판정(02)이 성립한다. 실제 hash는 `bcm_tx_l.tx_hash`에 그대로 남고 운영 조사는 그 컬럼으로 한다(V26 인덱스).
 *
 * 우리가 제출한 전송은 벤더가 준 `xfr-…`를 그대로 쓰므로 이 규칙을 적용하지 않는다.
 */
object NetworkChainTransactionId {
    const val PREFIX = "dfns-"

    /** `bcm_tx_l.vndr_tx_id` 폭. */
    const val MAX_LENGTH = 64

    /** 자릿수 여유를 남긴 요약 길이. 208비트라 현실적인 충돌 여지가 없다. */
    private const val DIGEST_HEX_LENGTH = 52

    private val EVM_TRANSACTION_HASH = Regex("0x[0-9a-fA-F]{64}")

    /**
     * [network]는 BCM 네트워크 코드, [transactionHash]·[eventIndex]는 관찰한 값 그대로다.
     * EVM hash만 소문자로 정규화한다 — 16진수라 대소문자에 정보가 없고, 표기가 흔들려도 같은 이동이 같은 ID가 되어야 한다.
     * 그 밖의 형식(예: Solana base58 서명)은 대소문자가 값의 일부이므로 건드리지 않는다.
     *
     * [eventIndex]는 **필수**다. 명세에서는 선택 필드지만, 한 트랜잭션이 여러 이동을 담을 때 순번이 없으면
     * 서로 다른 이동이 같은 원장 PK로 합쳐져 서로 다른 계정·자산의 자금이 한 논리 거래가 된다.
     * 순번 없는 사건은 고유 키를 만들 수 없으므로 ID를 지어내지 않고 실패한다 — 호출자는 그 사건을 처리 보류로 남긴다.
     */
    fun of(
        network: String,
        transactionHash: String,
        eventIndex: String,
    ): String {
        require(network.isNotBlank()) { "network must not be blank" }
        require(transactionHash.isNotBlank()) { "transactionHash must not be blank" }
        require(eventIndex.isNotBlank()) { "eventIndex must not be blank" }
        val hash = if (EVM_TRANSACTION_HASH.matches(transactionHash)) transactionHash.lowercase() else transactionHash
        val digest = sha256Hex(canonical(network, hash, eventIndex))
        return (PREFIX + digest.take(DIGEST_HEX_LENGTH)).also { check(it.length <= MAX_LENGTH) { "id must fit vndr_tx_id" } }
    }

    /**
     * 구분자 대신 길이를 앞에 붙인다 — 값 안에 어떤 문자가 들어와도 세 입력의 경계가 흔들리지 않아
     * 서로 다른 이동이 같은 문자열로 합쳐지지 않는다.
     */
    private fun canonical(vararg parts: String): String = parts.joinToString("") { "${it.length}:$it" }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
