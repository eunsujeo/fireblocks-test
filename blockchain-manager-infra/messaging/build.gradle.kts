dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":blockchain-manager-domain"))
    implementation(project(":blockchain-manager-support"))
    implementation(libs.spring.kafka)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.register<JavaExec>("consumeLocalKafka") {
    group = "verification"
    description = "Consumes deterministic local Kafka events for the system-test runner."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.whatto.bcm.infra.messaging.LocalKafkaProbeKt")
}

tasks.register<JavaExec>("inspectLocalKafkaOffsets") {
    group = "verification"
    description = "Prints deterministic local Kafka topic end offsets for the system-test runner."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.whatto.bcm.infra.messaging.LocalKafkaOffsetProbeKt")
}

tasks.register<JavaExec>("awaitLocalKafkaEvent") {
    group = "verification"
    description = "Waits for one matching deterministic local Kafka event."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.whatto.bcm.infra.messaging.LocalKafkaEventProbeKt")
}
