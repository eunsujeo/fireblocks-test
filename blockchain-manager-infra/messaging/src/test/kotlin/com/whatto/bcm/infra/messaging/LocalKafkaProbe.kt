package com.whatto.bcm.infra.messaging

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties

/** 시스템 테스트 전용 loopback Kafka one-shot consumer. test runtime에만 존재한다. */
object LocalKafkaProbe {
    fun requested(args: Array<String>): Boolean = parse(args) != null

    fun run(args: Array<String>) {
        val command = checkNotNull(parse(args)) { "unsupported local kafka probe" }
        val properties =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, command.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, command.groupId)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            }
        val values = mutableListOf<String>()
        KafkaConsumer<String, String>(properties).use { consumer ->
            consumer.subscribe(listOf(command.topic))
            val deadline = System.nanoTime() + Duration.ofMillis(command.timeoutMillis).toNanos()
            while (values.size < command.maxMessages && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach { record -> values += record.value() }
            }
        }
        check(values.size >= command.maxMessages) {
            "local kafka probe timed out: expected=${command.maxMessages}, actual=${values.size}"
        }
        values.take(command.maxMessages).forEach(::println)
    }

    private fun parse(args: Array<String>): Command? {
        if (args.size != 5) return null
        val match = LOOPBACK_BOOTSTRAP.matchEntire(args[0]) ?: return null
        val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val topic = args[1].takeIf(TOPIC_PATTERN::matches) ?: return null
        val groupId = args[2].takeIf(GROUP_PATTERN::matches) ?: return null
        val maxMessages = args[3].toIntOrNull()?.takeIf { it in 1..100 } ?: return null
        val timeoutMillis = args[4].toLongOrNull()?.takeIf { it in 1..60_000 } ?: return null
        return Command("127.0.0.1:$port", topic, groupId, maxMessages, timeoutMillis)
    }

    private data class Command(
        val bootstrapServers: String,
        val topic: String,
        val groupId: String,
        val maxMessages: Int,
        val timeoutMillis: Long,
    )

    private val LOOPBACK_BOOTSTRAP = Regex("127\\.0\\.0\\.1:([0-9]{1,5})")
    private val TOPIC_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    private val GROUP_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
}

fun main() {
    val args =
        arrayOf(
            System.getenv("BCM_KAFKA_PROBE_BOOTSTRAP").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_TOPIC").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_GROUP_ID").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_MAX_MESSAGES").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_TIMEOUT_MILLIS").orEmpty(),
        )
    require(LocalKafkaProbe.requested(args)) { "invalid local kafka probe environment" }
    LocalKafkaProbe.run(args)
}
