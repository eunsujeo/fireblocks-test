package com.whatto.bcm.domain.event

import com.whatto.bcm.domain.tx.TxStatus

/** DAW-CORE로 내보내는 벤더 중립 거래 이벤트 — openapi.yaml ChainEvent 계약. */
data class ChainEvent(
    val eventId: String,
    val type: EventType,
    val txId: String,
    val txHash: String?,
    val externalTxId: String?,
    val accountId: String,
    val network: String,
    val symbol: String,
    val to: String?,
    val from: String?,
    val amount: String,
    val status: TxStatus,
    val numOfConfirmations: Int,
)
