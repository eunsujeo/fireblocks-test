package com.whatto.bcm.admin.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI

@Component
class AdminExposureBoundary(
    private val properties: AdminProperties,
    @param:Value("\${server.address:127.0.0.1}") private val serverAddress: String,
) : InitializingBean {
    override fun afterPropertiesSet() {
        check(properties.mode == FUNCTION_TEST_MODE) {
            "shared BCM Admin mode is disabled until the mTLS and short-lived JWT boundary is implemented"
        }
        check(serverAddress.isLoopbackName()) {
            "functional-test BCM Admin must bind to a loopback address"
        }
        check(URI.create(properties.targetBaseUrl).host?.isLoopbackName() == true) {
            "functional-test BCM Admin target must use a loopback address"
        }
    }

    private fun String.isLoopbackName(): Boolean = lowercase() in LOOPBACK_NAMES

    companion object {
        private const val FUNCTION_TEST_MODE = "FUNCTION_TEST"
        private val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
    }
}
