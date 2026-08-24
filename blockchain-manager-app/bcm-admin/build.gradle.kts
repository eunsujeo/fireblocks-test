plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(kotlin("reflect"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.register<Exec>("frontendTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the dependency-free Admin frontend state tests."
    commandLine("node", "--test", "src/test/js/app-state.test.mjs")
}

tasks.register<Exec>("checkAdminOpenApiTypes") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the committed BCM Admin client types against docs/api/openapi.yaml."
    commandLine("python3", "openapi/generate.py", "--check")
}

tasks.named("check") {
    dependsOn("frontendTest", "checkAdminOpenApiTypes")
}
