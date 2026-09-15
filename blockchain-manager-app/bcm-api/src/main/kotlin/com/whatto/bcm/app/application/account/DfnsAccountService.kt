package com.whatto.bcm.app.application.account

import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.wallet.NetworkWalletProvisioningService
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import com.whatto.bcm.domain.exception.BcmException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletAssetPort
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.vendor.NetworkWalletSubmissionPort
import com.whatto.bcm.domain.vendor.VendorBalance
import com.whatto.bcm.domain.wallet.NetworkWalletAddressPolicy
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.support.time.CoreDateTimes
import java.time.Clock
import java.util.UUID

/**
 * Dfns 원천의 계정·주소 유스케이스 — 공개 계약은 Fireblocks 구현과 같고 자원 모델만 다르다(계약13 "계정·주소 API의 Dfns 연결").
 *
 * - 계정 생성은 외부 호출 없이 `(accountType, ref)`로 멱등한 논리 계정 등록으로 완료한다. 기본 체인·wallet ID를 응답에 넣지 않는다.
 * - 주소 발급은 현행 `(accountId, network, symbol)` 입구를 유지한다. 논리 계정·활성 자산 매핑·수신 주소 모델(`NetworkWalletAddressPolicy`)을
 *   먼저 검증한 뒤 `(origin, accountId, network)` 지갑 의도를 예약한다. 의도 ID·상관관계는 scope에서 결정적으로 도출하고 제출 snapshot은
 *   벤더 출력 포트(`NetworkWalletSubmissionPort`)가 만들어 재요청과 같은 네트워크의 다른 토큰 요청이 같은 지갑 의도에 합류한다.
 * - 지갑이 준비되면(Ready) 지갑 피처 서비스가 검증한 지갑 주소를 그 네트워크의 토큰 수신 주소로 저장한다 — 정책에 등록된 EVM 계정 모델
 *   네트워크에서만이며 tag/memo 모델을 가진 체인의 주소는 코드가 추정하지 않고 거절한다. 진행 중/충돌은 지갑 서비스가 공개 오류로 번역한다.
 * - 잔액 조회는 주소가 발급된 자산만 대상으로, 네트워크마다 원장의 준비 지갑을 찾아 지갑 자산을 **한 번** 관찰하고(`NetworkWalletAssetPort`)
 *   매핑의 `vendorAssetId`와 같은 키의 항목을 그 자산의 잔액으로 돌려준다(계약13 "잔액 계약 — 구현"). 관찰된 온체인 잔액은 total·available이고
 *   pending·frozen·locked는 Dfns가 주지 않으므로 null이다. 목록에 없는 등록 자산은 보유하지 않은 것이라 `"0"`이다.
 *   발급 기록이 있는데 원장에 준비 지갑이 없거나 응답 지갑·네트워크가 다르면 외부 drift라 빈 배열·0으로 숨기지 않고 실패한다.
 * 도메인 출력 포트만 사용하며 벤더 설정·HTTP 본문 형식은 조립부(DfnsAccountConfig)와 어댑터가 소유한다.
 */
class DfnsAccountService(
    private val logicalAccounts: LogicalAccountService,
    private val accounts: AccountQueryService,
    private val assetMappings: VendorAssetMappingQueryService,
    private val depositAddresses: DepositAddressRepository,
    private val provisioning: NetworkWalletProvisioningService,
    private val submissions: NetworkWalletSubmissionPort,
    private val walletAssets: NetworkWalletAssetPort,
    private val policy: NetworkWalletAddressPolicy,
    private val origin: ProviderOrigin,
    private val clock: Clock,
) : AccountOperations {
    init {
        require(origin.protocolProvider == "dfns") { "DfnsAccountService requires the Dfns origin" }
    }

    override fun createAccount(
        accountType: AccountType,
        ref: String,
    ): Account = logicalAccounts.create(accountType, ref)

    override fun createDepositAddress(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddress {
        val account = accounts.requiredAccount(accountId)
        return issue(account, validated(network, symbol))
    }

    /** 계정·매핑·주소 모델은 전체를 먼저 검증해 미지원과 지갑 준비 중/충돌을 구분한다(스펙: 미지원이 섞이면 아무것도 발급하지 않고 400). */
    override fun createDepositAddresses(
        accountId: String,
        symbol: String,
        networks: List<String>,
    ): List<AddressOutcome> {
        val account = accounts.requiredAccount(accountId)
        val assets = networks.distinct().associateWith { network -> validated(network, symbol) }
        val issued = mutableMapOf<String, AddressOutcome>()
        return networks.map { network ->
            issued.getOrPut(network) {
                try {
                    AddressOutcome(network, symbol, issue(account, checkNotNull(assets[network])), null)
                } catch (exception: BcmException) {
                    AddressOutcome(network, symbol, null, exception)
                }
            }
        }
    }

    override fun balancesOf(
        accountId: String,
        network: String?,
        symbol: String?,
    ): List<AssetBalance> {
        val account = accounts.requiredAccount(accountId)
        val addresses = depositAddresses.findAll(accountId, symbol, network)
        if (addresses.isEmpty()) return emptyList()
        account.requireLogical()
        val snapshots =
            addresses.map { it.network }.distinct().associateWith { issuedNetwork ->
                val scope = NetworkWalletScope(origin, accountId, issuedNetwork)
                val wallet =
                    provisioning.readyWallet(scope)
                        ?: throw IllegalStateException("Issued address without a ready network wallet: $accountId/$issuedNetwork")
                val snapshot = walletAssets.assets(scope, wallet.vendorWalletId)
                check(snapshot.vendorWalletId == wallet.vendorWalletId && snapshot.network == issuedNetwork) {
                    "Network wallet asset snapshot does not match the ledger wallet: $accountId/$issuedNetwork"
                }
                snapshot
            }
        return addresses.map { address ->
            // 발급 당시 매핑은 해제돼도 발급 자산이므로 현재 활성 여부와 무관하게 조회한다(Fireblocks 구현과 같은 규칙).
            val mapping = assetMappings.requiredMapping(address.network, address.symbol)
            val observed = checkNotNull(snapshots[address.network]).assets.filter { it.vendorAssetId == mapping.vendorAssetId }
            check(observed.size <= 1) { "Duplicate wallet asset for ${address.network}/${address.symbol}" }
            val amount = observed.singleOrNull()?.amount() ?: ZERO_BALANCE
            AssetBalance(address.network, address.symbol, VendorBalance(amount, amount, null, null, null))
        }
    }

    override fun depositAddressesOf(
        accountId: String,
        symbol: String?,
        network: String?,
    ): List<DepositAddress> {
        accounts.requiredAccount(accountId)
        return depositAddresses.findAll(accountId, symbol, network)
    }

    private fun validated(
        network: String,
        symbol: String,
    ): ValidatedAsset {
        val mapping = assetMappings.requiredCurrentMapping(network, symbol)
        if (!policy.allowsAccountAddress(network)) throw AssetNotSupportedException(network, symbol)
        return ValidatedAsset(mapping.network, mapping.symbol)
    }

    private fun issue(
        account: Account,
        asset: ValidatedAsset,
    ): DepositAddress {
        account.requireLogical()
        depositAddresses.find(account.accountId, asset.network, asset.symbol)?.let { return it }
        val scope = NetworkWalletScope(origin, account.accountId, asset.network)
        // 같은 scope의 재요청은 원장의 기존 의도에 합류해야 하므로 의도 ID·상관관계를 scope에서 결정적으로 만든다(원장은 다른 요청 snapshot을 충돌로 거절한다).
        val intentId =
            UUID
                .nameUUIDFromBytes(
                    "bcm-network-wallet|${origin.originId}|${account.accountId}|${asset.network}".toByteArray(),
                ).toString()
        val request = NetworkWalletCreationRequest(scope, intentId)
        val submission = submissions.submission(request) ?: throw AssetNotSupportedException(asset.network, asset.symbol)
        val wallet =
            provisioning.provisionedWallet(
                NetworkWalletCreationSeed(intentId, request, submission),
                policy.provisioningRetryAfterSeconds,
            )
        val address = checkNotNull(wallet.address) { "Provisioned network wallet without address: $intentId" }
        return try {
            depositAddresses.insert(DepositAddress(account.accountId, asset.network, asset.symbol, address, CoreDateTimes.now(clock)))
        } catch (conflict: ConflictException) {
            // (계정, 네트워크, 심볼) PK 경합 — 먼저 저장된 값을 돌려준다(주소 매핑 멱등).
            depositAddresses.find(account.accountId, asset.network, asset.symbol) ?: throw conflict
        }
    }

    companion object {
        /** 지갑 자산 목록에 없는 등록 자산 — 보유하지 않은 자산의 온체인 잔액은 0이다(관찰 목록 의미는 계약13 수용 항목). */
        private const val ZERO_BALANCE = "0"
    }

    private data class ValidatedAsset(
        val network: String,
        val symbol: String,
    )
}
