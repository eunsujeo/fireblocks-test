package com.whatto.bcm.app.bat.asset

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.context.ConfigurableApplicationContext
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class VendorAssetCatalogCacheSyncOnceRunnerTest {
    @Test
    fun `명시적 일회 job은 자산 카탈로그를 동기화하고 context를 닫는다`() {
        val command = RecordingCommand()
        val context = RecordingContext()

        VendorAssetCatalogCacheSyncOnceRunner(command, context.proxy).run(DefaultApplicationArguments())

        assertThat(command.scopes).containsExactly(VendorAssetCatalogSyncScope.ALL)
        assertThat(context.closed.get()).isTrue()
    }

    @Test
    fun `로컬 bootstrap 일회 job은 채택 네트워크 범위만 동기화한다`() {
        val command = RecordingCommand()
        val context = RecordingContext()

        VendorAssetCatalogCacheSupportedSyncOnceRunner(command, context.proxy).run(DefaultApplicationArguments())

        assertThat(command.scopes).containsExactly(VendorAssetCatalogSyncScope.ADOPTED)
        assertThat(context.closed.get()).isTrue()
    }

    @Test
    fun `자산 카탈로그 동기화 실패는 context를 성공 종료하지 않는다`() {
        val command = RecordingCommand(IllegalStateException("asset catalog failed"))
        val context = RecordingContext()

        assertThatThrownBy {
            VendorAssetCatalogCacheSyncOnceRunner(command, context.proxy).run(DefaultApplicationArguments())
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(command.scopes).containsExactly(VendorAssetCatalogSyncScope.ALL)
        assertThat(context.closed.get()).isFalse()
    }

    private class RecordingCommand(
        private val failure: RuntimeException? = null,
    ) : VendorAssetCatalogCacheSyncCommand {
        val scopes = mutableListOf<VendorAssetCatalogSyncScope>()

        override fun sync(scope: VendorAssetCatalogSyncScope) {
            scopes += scope
            failure?.let { throw it }
        }
    }

    private class RecordingContext {
        val closed = AtomicBoolean(false)
        val proxy =
            Proxy.newProxyInstance(
                ConfigurableApplicationContext::class.java.classLoader,
                arrayOf(ConfigurableApplicationContext::class.java),
            ) { _, method, _ ->
                if (method.name == "close") closed.set(true)
                primitiveDefault(method.returnType)
            } as ConfigurableApplicationContext

        private fun primitiveDefault(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0F
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }
}
