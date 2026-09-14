package com.whatto.bcm.domain.provider

fun interface ProviderOriginRepository {
    fun findBinding(): ProviderOrigin?
}
