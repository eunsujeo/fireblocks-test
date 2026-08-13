package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.support.id.UuidV7Generator
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

fun interface BoostExternalTransactionIdGenerator {
    fun nextId(): String
}

fun interface BoostClaimIdGenerator {
    fun nextId(): String
}

@Component
class UuidV7BoostExternalTransactionIdGenerator(
    clock: Clock,
) : BoostExternalTransactionIdGenerator {
    private val delegate = UuidV7Generator(clock)

    override fun nextId(): String = "bst-${delegate.nextId()}"
}

@Component
class UuidBoostClaimIdGenerator : BoostClaimIdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}
