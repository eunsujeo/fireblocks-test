package com.whatto.bcm.app.application.account

import com.whatto.bcm.domain.vendor.VendorBalance

/** 자산 하나의 vault 잔액 — 어느 (네트워크, 심볼)의 잔액인지까지 묶어 돌려준다. */
data class AssetBalance(
    val network: String,
    val symbol: String,
    val balance: VendorBalance,
)
