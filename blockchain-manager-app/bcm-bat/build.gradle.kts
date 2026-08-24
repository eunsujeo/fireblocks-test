plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

val productionOnly = providers.gradleProperty("bcmProductionOnly").map(String::toBoolean).getOrElse(false)

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":blockchain-manager-domain"))
    implementation(project(":blockchain-manager-support"))
    implementation(project(":blockchain-manager-infra:persistence"))
    implementation(project(":blockchain-manager-infra:client"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation(kotlin("reflect"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    if (!productionOnly) {
        testImplementation(project(":blockchain-manager-test-support"))
    }
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    testImplementation(libs.testcontainers.postgresql)
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
