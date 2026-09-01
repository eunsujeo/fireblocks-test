package com.whatto.bcm.app.api.account

import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.DomainExceptionResolver
import com.whatto.bcm.app.api.web.ErrorResponse
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.account.AccountService
import com.whatto.bcm.app.application.account.AddressOutcome
import com.whatto.bcm.domain.exception.CreationRetryLaterException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 계정·주소 오퍼레이션 (openapi v0.1.1) — 검증·변환만, 판단은 application/domain 소관.
 * 생성 계열은 멱등 재요청도 201 이다 (스펙 "생성됨(또는 멱등 재요청)").
 */
@RestController
class AccountController(
    private val accountService: AccountService,
) {
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @Valid @RequestBody request: CreateAccountRequest,
        httpRequest: HttpServletRequest,
    ): ApiResponse<AccountData> {
        val account = accountService.createAccount(checkNotNull(request.accountType), checkNotNull(request.ref))
        return ApiResponse.of(
            AccountData(accountType = account.accountType, ref = account.ref, accountId = account.accountId),
            RequestIdFilter.requestIdOf(httpRequest),
        )
    }

    /**
     * 한 자산 심볼을 여러 네트워크로 발급. 계정이 없으면 전체 404, 매핑이 하나라도 없으면 발급 전 전체 400이다.
     * 매핑 검증 뒤 벤더 실패만 네트워크별 결과가 되며 HTTP 200이다 (스펙 createDepositAddresses).
     */
    @PostMapping("/accounts/{accountId}/addresses")
    fun createDepositAddresses(
        @PathVariable @Size(max = 64) accountId: String,
        @Valid @RequestBody request: CreateAddressesRequest,
        httpRequest: HttpServletRequest,
    ): ApiResponse<List<DepositAddressResultData>> {
        val outcomes =
            accountService.createDepositAddresses(
                accountId,
                checkNotNull(request.symbol),
                checkNotNull(request.networks),
            )
        return ApiResponse.of(outcomes.map(::toResultData), RequestIdFilter.requestIdOf(httpRequest))
    }

    /** 도메인 예외 → 네트워크별 에러 코드 변환은 여기서만 한다 — 전역 핸들러와 같은 해석기를 쓴다. */
    private fun toResultData(outcome: AddressOutcome): DepositAddressResultData {
        val error =
            outcome.failure?.let {
                val errorCode = DomainExceptionResolver.resolve(it)
                ErrorResponse.ErrorBody(
                    code = errorCode.code,
                    message = errorCode.message,
                    retryAfterSeconds = (it as? CreationRetryLaterException)?.retryAfterSeconds,
                )
            }
        return DepositAddressResultData(
            network = outcome.network,
            symbol = outcome.symbol,
            address = outcome.depositAddress?.address,
            memoTag = null,
            error = error,
        )
    }

    /** 자산별 vault 잔액 — 대사 재료다. 필터를 비우면 주소가 발급된 자산 전부 (스펙 balancesOf). */
    @GetMapping("/accounts/{accountId}/balances")
    fun balancesOf(
        @PathVariable @Size(max = 64) accountId: String,
        @RequestParam(required = false) @Pattern(regexp = NETWORK_PATTERN) network: String?,
        @RequestParam(required = false) @Pattern(regexp = SYMBOL_PATTERN) symbol: String?,
        httpRequest: HttpServletRequest,
    ): ApiResponse<List<AssetBalanceData>> {
        val balances = accountService.balancesOf(accountId, network, symbol)
        return ApiResponse.of(balances.map(AssetBalanceData::from), RequestIdFilter.requestIdOf(httpRequest))
    }

    /**
     * 발급된 주소 조회 — 매니저 DB 읽기, 벤더 왕복 없음. 발급과 경로가 같고 메서드만 다르다.
     * 미발급은 빈 배열이고 계정이 없으면 404 — null 특수 케이스를 두지 않는다 (스펙 depositAddressesOf).
     */
    @GetMapping("/accounts/{accountId}/addresses")
    fun depositAddressesOf(
        @PathVariable @Size(max = 64) accountId: String,
        @RequestParam(required = false) @Pattern(regexp = SYMBOL_PATTERN) symbol: String?,
        @RequestParam(required = false) @Pattern(regexp = NETWORK_PATTERN) network: String?,
        httpRequest: HttpServletRequest,
    ): ApiResponse<List<DepositAddressData>> {
        val addresses = accountService.depositAddressesOf(accountId, symbol, network)
        return ApiResponse.of(addresses.map(DepositAddressData::from), RequestIdFilter.requestIdOf(httpRequest))
    }

    companion object {
        /**
         * 허용 문자 allowlist — 자산 심볼에서 경로·쿼리 구분 문자(.·/)를 차단한다.
         * 스펙 maxLength(네트워크 20 · 심볼 16)에 문자 집합을 더한다. 벤더 assetId는 DB 매핑 뒤에만 URL로 간다.
         */
        const val NETWORK_PATTERN = "[A-Za-z0-9_-]{1,20}"
        const val SYMBOL_PATTERN = "[A-Za-z0-9_-]{1,16}"
    }
}
