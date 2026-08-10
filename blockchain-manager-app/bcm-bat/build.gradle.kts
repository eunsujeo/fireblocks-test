plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":blockchain-manager-domain"))
    implementation(project(":blockchain-manager-support"))
    implementation(project(":blockchain-manager-infra:persistence"))
    implementation(project(":blockchain-manager-infra:client"))
    implementation(project(":blockchain-manager-infra:messaging"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation(kotlin("reflect"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
