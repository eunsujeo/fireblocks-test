plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

val productionOnly = providers.gradleProperty("bcmProductionOnly").map(String::toBoolean).getOrElse(false)

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":blockchain-manager-application"))
    implementation(project(":blockchain-manager-domain"))
    implementation(project(":blockchain-manager-support"))
    implementation(project(":blockchain-manager-infra:persistence"))
    implementation(project(":blockchain-manager-infra:client"))
    implementation(project(":blockchain-manager-infra:messaging"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(kotlin("reflect"))
    implementation("tools.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    if (!productionOnly) {
        testImplementation(project(":blockchain-manager-test-support"))
    }
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    // Dfns 판단 유스케이스 + 실제 원장 결합 슬라이스(@DataJdbcTest) — 별도 Dfns 데이터셋으로 실행한다
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    testImplementation(libs.springmockk)
    testImplementation(libs.spring.kafka)
    testImplementation(libs.openapi.validator.mockmvc)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    if (productionOnly) {
        enabled = false
    } else {
        dependsOn(":blockchain-manager-test-support:compileLocalContracts")
        systemProperty(
            "bcm.contract-artifacts",
            project(":blockchain-manager-test-support")
                .layout
                .buildDirectory
                .dir("contracts")
                .get()
                .asFile
                .absolutePath,
        )
    }
}
