package com.whatto.bcm.domain.event

fun interface ChainEventSerializer {
    fun serialize(event: ChainEvent): String
}
