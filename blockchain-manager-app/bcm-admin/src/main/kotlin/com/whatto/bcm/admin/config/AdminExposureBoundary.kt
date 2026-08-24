package com.whatto.bcm.admin.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

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
        check(URI.create(properties.webhookManagementBaseUrl).host?.isLoopbackName() == true) {
            "functional-test BCM Webhook management target must use a loopback address"
        }
        validateSystemTestDirectory()
        validateLocalScenarioDirectory()
        validateLocalAssetManagement()
    }

    private fun validateSystemTestDirectory() {
        if (!properties.systemTest.enabled) return
        val configured = properties.systemTest.stateDirectory
        check(configured.isNotBlank()) { "system-test state directory is required when diagnostics are enabled" }
        val path = Path.of(configured)
        check(path.isAbsolute && path.normalize() == path) { "system-test state directory must be an absolute normalized path" }
        check(Files.isDirectory(path) && !Files.isSymbolicLink(path)) { "system-test state directory must be a regular directory" }
    }

    private fun validateLocalScenarioDirectory() {
        if (!properties.localScenario.enabled) return
        check(properties.vendorMode == STUB_VENDOR_MODE && properties.chainMode == LOCAL_CHAIN_MODE) {
            "local scenarios require STUB+LOCAL mode"
        }
        check(properties.systemTest.enabled) { "local scenarios require the system-test ledger" }
        val configured = properties.localScenario.repositoryDirectory
        check(configured.isNotBlank()) { "local scenario repository directory is required" }
        val path = Path.of(configured)
        check(path.isAbsolute && path.normalize() == path) { "local scenario repository directory must be absolute and normalized" }
        check(Files.isDirectory(path) && !Files.isSymbolicLink(path)) { "local scenario repository directory must be a regular directory" }
        check(Files.isRegularFile(path.resolve("scripts/internal/local-scenario-runner.py"))) {
            "local scenario runner is missing"
        }
        check(Files.isRegularFile(path.resolve("scripts/system-test.sh"))) { "system-test runner is missing" }
    }

    private fun validateLocalAssetManagement() {
        if (!properties.localAssetManagement.enabled) return
        check(properties.localAssetManagement.employeeNo.matches(Regex("[A-Z0-9]{1,6}"))) {
            "local asset management employee number must be 1-6 uppercase letters or digits"
        }
        check(properties.localAssetManagement.branchCode.matches(Regex("[A-Z0-9]{1,4}"))) {
            "local asset management branch code must be 1-4 uppercase letters or digits"
        }
    }

    private fun String.isLoopbackName(): Boolean = lowercase() in LOOPBACK_NAMES

    companion object {
        private const val FUNCTION_TEST_MODE = "FUNCTION_TEST"
        private const val STUB_VENDOR_MODE = "STUB"
        private const val LOCAL_CHAIN_MODE = "LOCAL"
        private val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
    }
}
