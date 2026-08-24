package com.whatto.bcm.infra.messaging

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties

/** 로컬 입금 점검 전용 consumer. 일치 event 하나만 출력하며 production runtime에는 포함되지 않는다. */
object LocalKafkaEventProbe {
    fun requested(args: Array<String>): Boolean = parse(args) != null

    fun run(args: Array<String>) {
        val command = checkNotNull(parse(args)) { "unsupported local kafka event probe" }
        val properties =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, command.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, command.groupId)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            }
        var matched: String? = null
        KafkaConsumer<String, String>(properties).use { consumer ->
            consumer.subscribe(listOf(command.topic))
            val deadline = System.nanoTime() + Duration.ofMillis(command.timeoutMillis).toNanos()
            while (matched == null && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach { record ->
                    if (matches(record.value(), "accountId", command.accountId) &&
                        matches(record.value(), "status", command.status) &&
                        matches(record.value(), "amount", command.amount)
                    ) {
                        matched = record.value()
                    }
                }
            }
        }
        checkNotNull(matched) { "matching local kafka event timed out" }
        println(matched)
    }

    private fun matches(
        document: String,
        field: String,
        expected: String,
    ): Boolean = Regex("\\\"${Regex.escape(field)}\\\"\\s*:\\s*\\\"${Regex.escape(expected)}\\\"").containsMatchIn(document)

    private fun parse(args: Array<String>): Command? {
        if (args.size != 7) return null
        val port =
            LOOPBACK_BOOTSTRAP
                .matchEntire(args[0])
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: return null
        val topic = args[1].takeIf(TOPIC_PATTERN::matches) ?: return null
        val groupId = args[2].takeIf(GROUP_PATTERN::matches) ?: return null
        val timeoutMillis = args[3].toLongOrNull()?.takeIf { it in 1..60_000 } ?: return null
        val accountId = args[4].takeIf(IDENTIFIER_PATTERN::matches) ?: return null
        val status = args[5].takeIf(STATUS_PATTERN::matches) ?: return null
        val amount = args[6].takeIf(AMOUNT_PATTERN::matches) ?: return null
        return Command("127.0.0.1:$port", topic, groupId, timeoutMillis, accountId, status, amount)
    }

    private data class Command(
        val bootstrapServers: String,
        val topic: String,
        val groupId: String,
        val timeoutMillis: Long,
        val accountId: String,
        val status: String,
        val amount: String,
    )

    private val LOOPBACK_BOOTSTRAP = Regex("127\\.0\\.0\\.1:([0-9]{1,5})")
    private val TOPIC_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    private val GROUP_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
    private val STATUS_PATTERN = Regex("[A-Z_]{1,32}")
    private val AMOUNT_PATTERN = Regex("[0-9]+(?:\\.[0-9]+)?")
}

fun main() {
    val args =
        arrayOf(
            System.getenv("BCM_KAFKA_PROBE_BOOTSTRAP").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_TOPIC").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_GROUP_ID").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_TIMEOUT_MILLIS").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_ACCOUNT_ID").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_STATUS").orEmpty(),
            System.getenv("BCM_KAFKA_PROBE_AMOUNT").orEmpty(),
        )
    require(LocalKafkaEventProbe.requested(args)) { "invalid local kafka event probe environment" }
    LocalKafkaEventProbe.run(args)
}
