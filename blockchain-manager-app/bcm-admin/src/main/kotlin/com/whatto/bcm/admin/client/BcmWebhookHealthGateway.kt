package com.whatto.bcm.admin.client

interface BcmWebhookHealthGateway {
    fun isReady(): Boolean
}
