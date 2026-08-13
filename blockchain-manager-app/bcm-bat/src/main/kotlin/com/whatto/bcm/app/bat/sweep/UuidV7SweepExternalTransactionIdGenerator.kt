package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.support.id.UuidV7Generator
import org.springframework.stereotype.Component
import java.time.Clock

fun interface SweepExternalTransactionIdGenerator {
    fun nextId(): String
}

fun interface SweepApprovalExternalTransactionIdGenerator {
    fun nextId(): String
}

fun interface SweepExecutionIdGenerator {
    fun nextId(): String
}

@Component
class UuidV7SweepExternalTransactionIdGenerator(
    clock: Clock,
) : SweepExternalTransactionIdGenerator {
    private val delegate = UuidV7Generator(clock)

    override fun nextId(): String = "swp-${delegate.nextId()}"
}

@Component
class UuidV7SweepApprovalExternalTransactionIdGenerator(
    clock: Clock,
) : SweepApprovalExternalTransactionIdGenerator {
    private val delegate = UuidV7Generator(clock)

    override fun nextId(): String = "swa-${delegate.nextId()}"
}

@Component
class UuidV7SweepExecutionIdGenerator(
    clock: Clock,
) : SweepExecutionIdGenerator {
    private val delegate = UuidV7Generator(clock)

    override fun nextId(): String = delegate.nextId()
}
