package com.whatto.bcm.app.bat.sweep

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.context.ConfigurableApplicationContext
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class SweepBatchReconciliationOnceRunnerTest {
    @Test
    fun `명시적 일회 job은 sweep 대사를 한 번 실행하고 context를 닫는다`() {
        val command = RecordingReconciliationCommand()
        val context = RecordingContext()
        val runner = SweepBatchReconciliationOnceRunner(command, context.proxy)

        runner.run(DefaultApplicationArguments())

        assertThat(command.invocations).isEqualTo(1)
        assertThat(context.closed.get()).isTrue()
    }

    @Test
    fun `sweep 대사가 실패하면 context를 성공 종료하지 않는다`() {
        val command = RecordingReconciliationCommand(IllegalStateException("reconciliation failed"))
        val context = RecordingContext()
        val runner = SweepBatchReconciliationOnceRunner(command, context.proxy)

        assertThatThrownBy { runner.run(DefaultApplicationArguments()) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(context.closed.get()).isFalse()
    }

    private class RecordingReconciliationCommand(
        private val failure: RuntimeException? = null,
    ) : SweepBatchReconciliationCommand {
        var invocations = 0

        override fun reconcile(): SweepReconciliationCycleResult {
            invocations += 1
            failure?.let { throw it }
            return SweepReconciliationCycleResult(0, 0, 0)
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
