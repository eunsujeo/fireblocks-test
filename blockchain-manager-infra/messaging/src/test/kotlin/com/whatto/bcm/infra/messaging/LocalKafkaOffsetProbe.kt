package com.whatto.bcm.infra.messaging

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.Properties

/** 시스템 테스트 reset 전후의 topic end offset을 비교하는 loopback 전용 one-shot probe. */
object LocalKafkaOffsetProbe {
    fun requested(args: Array<String>): Boolean = parse(args) != null

    fun run(args: Array<String>) {
        val command = checkNotNull(parse(args)) { "unsupported local kafka offset probe" }
        val properties =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, command.bootstrapServers)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            }
        val total =
            KafkaConsumer<String, String>(properties).use { consumer ->
                val partitions =
                    consumer
                        .partitionsFor(command.topic)
                        .map { partition -> TopicPartition(command.topic, partition.partition()) }
                consumer.endOffsets(partitions).values.sum()
            }
        println("{\"topic\":\"${command.topic}\",\"totalEndOffset\":$total}")
    }

    private fun parse(args: Array<String>): Command? {
        if (args.size != 2) return null
        val match = LOOPBACK_BOOTSTRAP.matchEntire(args[0]) ?: return null
        val port = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val topic = args[1].takeIf(TOPIC_PATTERN::matches) ?: return null
        return Command("127.0.0.1:$port", topic)
    }

    private data class Command(
        val bootstrapServers: String,
        val topic: String,
    )

    private val LOOPBACK_BOOTSTRAP = Regex("127\\.0\\.0\\.1:([0-9]{1,5})")
    private val TOPIC_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
}

fun main() {
    val args =
        arrayOf(
            System.getenv("BCM_KAFKA_PROBE_BOOTSTRAP").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_TOPIC").orEmpty(),
        )
    require(LocalKafkaOffsetProbe.requested(args)) { "invalid local kafka offset probe environment" }
    LocalKafkaOffsetProbe.run(args)
}
