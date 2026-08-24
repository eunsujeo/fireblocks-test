package com.whatto.bcm.app.bat.sweep.fixture

import com.whatto.bcm.app.bat.sweep.SweepRuntimeGuard
import com.whatto.bcm.domain.admin.SweepExecutionPolicy
import com.whatto.bcm.domain.sweep.ActiveSweepRuntimeContext
import java.math.BigDecimal

object SweepRuntimeFixtures {
    fun context(
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        contractAddress: String = "0x4444444444444444444444444444444444444444",
        minimumAmount: String = "1",
        allowanceCap: String = "100",
        batchSize: Int = 100,
        itemAmountCap: String = "1000000",
        batchAmountCap: String = "10000000",
    ) = ActiveSweepRuntimeContext(
        network = network,
        symbol = symbol,
        policyVersionId = "policy-$network-$symbol",
        policySnapshotHash = "a".repeat(64),
        policy =
            SweepExecutionPolicy(
                enabled = true,
                minimumAmount = BigDecimal(minimumAmount),
                batchSize = batchSize,
                allowanceCap = BigDecimal(allowanceCap),
                itemAmountCap = BigDecimal(itemAmountCap),
                batchAmountCap = BigDecimal(batchAmountCap),
                boostAttempts = 1,
            ),
        contractVersionId = "contract-$network",
        contractEvidenceId = "evidence-$network",
        contractAddress = contractAddress,
    )

    fun guard(context: ActiveSweepRuntimeContext = context()) =
        SweepRuntimeGuard { network, symbol ->
            check(network == context.network && symbol == context.symbol)
            context
        }
}
