package com.whatto.bcm.app.bat.support

import com.whatto.bcm.domain.vendor.ObservedVendorTransaction
import com.whatto.bcm.domain.vendor.VendorLookup
import com.whatto.bcm.domain.vendor.VendorResponseEvidence
import com.whatto.bcm.domain.vendor.VendorTransaction

/**
 * 시험용 증적 — 실제 벤더 본문이 아니라 **그 관찰을 알아볼 수 있는 표식**이다.
 * 격리가 어떤 원문을 남겼는지 단언할 때 쓴다.
 */
fun VendorTransaction.observed(
    lookup: VendorLookup = VendorLookup.BY_TRANSACTION_ID,
    indexInBody: Int? = null,
    body: String = """{"id":"$transactionId","amountInfo":{"amount":"$amount"}}""",
) = ObservedVendorTransaction(
    transaction = this,
    evidence =
        VendorResponseEvidence(
            body = body.toByteArray(),
            contentType = "application/json",
            lookup = lookup,
            indexInBody = indexInBody,
        ),
)
