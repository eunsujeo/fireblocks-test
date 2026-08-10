package com.whatto.bcm.app.api.account

import com.whatto.bcm.app.api.web.ErrorResponse
import com.whatto.bcm.app.application.account.AssetBalance
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.support.amount.CoreAmounts
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * openapi CreateAccountRequest — accountType·ref 필수, ref maxLength 64. 초과 시 400 VALIDATION_FAILED.
 * accountType 이 enum 밖 값이면 역직렬화가 실패해 400 이 된다 (HttpMessageNotReadableException).
 */
data class CreateAccountRequest(
    val accountType: AccountType?,
    @field:NotBlank
    @field:Size(max = 64)
    val ref: String?,
)

/** openapi Account 스키마 — 도메인을 직접 노출하지 않는다 (architecture.md). */
data class AccountData(
    val accountType: AccountType,
    val ref: String,
    val accountId: String,
)

/** openapi CreateAddressesRequest — 자산 심볼 하나 + 네트워크 1~20개. 빈 배열·초과는 400 VALIDATION_FAILED. */
data class CreateAddressesRequest(
    @field:NotBlank
    @field:Pattern(regexp = AccountController.SYMBOL_PATTERN)
    val symbol: String?,
    @field:NotEmpty
    @field:Size(max = 20)
    val networks: List<
        @NotBlank
        @Pattern(regexp = AccountController.NETWORK_PATTERN)
        String,
    >?,
)

/**
 * openapi DepositAddressResult — 조회 항목(DepositAddressData)과 같은 필드에 error 가 더해진 모양.
 * 성공이면 address, 실패면 error 가 채워진다. HTTP 는 항목 결과와 무관하게 200 이라 판단 기준은 error 유무다.
 */
data class DepositAddressResultData(
    val network: String,
    val symbol: String,
    val address: String?,
    val memoTag: String?,
    val error: ErrorResponse.ErrorBody?,
)

/**
 * openapi AssetBalance — 어느 자산의 잔액인지까지 담는다. 금액은 문자열(decimal).
 * locked = "나가는 중 출금 예약분 + AML 동결분" = 벤더 lockedAmount + frozen 합산.
 */
data class AssetBalanceData(
    val network: String,
    val symbol: String,
    val available: String,
    val pending: String,
    val locked: String,
) {
    companion object {
        fun from(assetBalance: AssetBalance): AssetBalanceData =
            AssetBalanceData(
                network = assetBalance.network,
                symbol = assetBalance.symbol,
                available = assetBalance.balance.available,
                pending = assetBalance.balance.pending,
                locked = CoreAmounts.plus(assetBalance.balance.lockedAmount, assetBalance.balance.frozen),
            )
    }
}

/** openapi DepositAddress — 조회 응답 항목. memoTag 는 EVM null (벤더 tag 는 03 미보관 · PLAN #21). */
data class DepositAddressData(
    val network: String,
    val symbol: String,
    val address: String,
    val memoTag: String?,
) {
    companion object {
        fun from(depositAddress: DepositAddress): DepositAddressData =
            DepositAddressData(
                network = depositAddress.network,
                symbol = depositAddress.symbol,
                address = depositAddress.address,
                memoTag = null,
            )
    }
}
