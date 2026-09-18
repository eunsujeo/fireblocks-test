package com.whatto.bcm.app.config

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.submission.DfnsTransferSubmissionService
import com.whatto.bcm.app.application.submission.TransactionSubmissionProperties
import com.whatto.bcm.app.application.submission.TransactionSubmissionWork
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.vendor.NetworkTransferPort
import com.whatto.bcm.domain.wallet.NetworkWalletProvisioningRepository
import com.whatto.bcm.infra.client.config.ConditionalOnDfnsProtocol
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Dfns 출금 제출 조립(계약13 "출금 제출 계약"). 공개 `POST /transactions`의 제출 경계(`TransactionSubmissionWork`)는
 * 제공자마다 **하나만** 뜬다 — Fireblocks·로컬은 `TransactionSubmissionService`, Dfns는 이 설정이 만드는 구현이다.
 *
 * 계정·주소 조립(`DfnsAccountConfig`)과 나눠 둔다. 제출은 제출 원장·전송 포트를 요구하므로 한 설정에 묶으면
 * 계정만 필요한 조립 지점까지 그 의존을 끌고 간다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDfnsProtocol
class DfnsSubmissionConfig {
    @Bean
    fun dfnsTransferSubmissionService(
        submissions: SubmissionRecordRepository,
        wallets: NetworkWalletProvisioningRepository,
        mappings: VendorAssetMappingQueryService,
        depositAddresses: DepositAddressQueryService,
        vendor: NetworkTransferPort,
        transactionRunner: TransactionRunner,
        origin: ProviderOrigin,
        clock: Clock,
        properties: TransactionSubmissionProperties,
    ): TransactionSubmissionWork =
        DfnsTransferSubmissionService(
            submissions,
            wallets,
            mappings,
            depositAddresses,
            vendor,
            transactionRunner,
            origin,
            clock,
            properties,
        )
}
