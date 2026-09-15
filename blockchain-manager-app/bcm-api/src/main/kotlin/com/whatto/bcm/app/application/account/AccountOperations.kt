package com.whatto.bcm.app.application.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress

/**
 * 계정·주소 공개 API의 유스케이스 경계 — 선택 제공자별 구현 하나만 조립된다(`fireblocks|local` → AccountService, `dfns` → DfnsAccountService).
 * 공개 계약(멱등 키·응답 모양·오류 코드)은 같고, 벤더 자원 모델(vault/asset wallet vs 논리 계정/네트워크 지갑)만 구현이 다르다.
 */
interface AccountOperations {
    fun createAccount(
        accountType: AccountType,
        ref: String,
    ): Account

    fun createDepositAddress(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddress

    fun createDepositAddresses(
        accountId: String,
        symbol: String,
        networks: List<String>,
    ): List<AddressOutcome>

    fun balancesOf(
        accountId: String,
        network: String?,
        symbol: String?,
    ): List<AssetBalance>

    fun depositAddressesOf(
        accountId: String,
        symbol: String?,
        network: String?,
    ): List<DepositAddress>
}
