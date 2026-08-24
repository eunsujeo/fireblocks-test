package com.whatto.bcm.app.bat.asset

import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.context.ConfigurableApplicationContext
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class VendorBlockchainCatalogSyncOnceRunnerTest {
    @Test
    fun `명시적 일회 job은 카탈로그를 한 번 동기화하고 context를 닫는다`() {
        val command = RecordingSyncCommand()
        val context = RecordingContext()
        val runner = VendorBlockchainCatalogSyncOnceRunner(command, context.proxy)

        runner.run(DefaultApplicationArguments())

        org.assertj.core.api.Assertions
            .assertThat(command.invocations)
            .isEqualTo(1)
        org.assertj.core.api.Assertions
            .assertThat(context.closed.get())
            .isTrue()
    }

    @Test
    fun `동기화가 실패하면 context를 성공 종료하지 않는다`() {
        val command = RecordingSyncCommand(IllegalStateException("catalog failed"))
        val context = RecordingContext()
        val runner = VendorBlockchainCatalogSyncOnceRunner(command, context.proxy)

        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                runner.run(DefaultApplicationArguments())
            }.isInstanceOf(IllegalStateException::class.java)

        org.assertj.core.api.Assertions
            .assertThat(context.closed.get())
            .isFalse()
    }

    private class RecordingSyncCommand(
        private val failure: RuntimeException? = null,
    ) : VendorBlockchainCatalogSyncCommand {
        var invocations = 0

        override fun sync() {
            invocations += 1
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
                if (method.name == "close") {
                    closed.set(true)
                }
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
