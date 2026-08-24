plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

val productionOnly = providers.gradleProperty("bcmProductionOnly").map(String::toBoolean).getOrElse(false)
val apiDocumentation = rootProject.layout.projectDirectory.dir("docs/api")

tasks.processResources {
    from(apiDocumentation) {
        include("index.html", "openapi.yaml", "spec.js", "api.md", "api.html", "try-it.js")
        into("static/api-docs")
    }
}

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
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(kotlin("reflect"))
    // DB 배선(data-jdbc·pg driver)은 infra/persistence 소관 — 런타임에 전이된다

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    if (!productionOnly) {
        testImplementation(project(":blockchain-manager-test-support"))
        testImplementation(project(":blockchain-manager-app:bcm-webhook"))
    }
    // 통합 테스트의 JdbcTemplate 컴파일 참조용 — 런타임 배선 소관은 infra/persistence
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    testImplementation(libs.archunit)
    testImplementation(libs.openapi.validator.mockmvc)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.spring.kafka)
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
