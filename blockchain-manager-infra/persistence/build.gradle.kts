// DB 소관 모듈 — Spring Data JDBC 매핑 · Git 관리 SQL(src/main/resources/db/migration) 정본 위치.
// 물리 컬럼명(bcm_ 스네이크)은 이 모듈 밖으로 새지 않는다 (ArchUnit 강제).
plugins {
    // @Repository 빈의 CGLIB 프록시(예외 변환)가 final 클래스에 못 붙는다 — Spring 빈 보유 모듈은 allopen
    alias(libs.plugins.kotlin.spring)
}

val productionOnly = providers.gradleProperty("bcmProductionOnly").map(String::toBoolean).getOrElse(false)

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":blockchain-manager-domain"))
    implementation(project(":blockchain-manager-support"))
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation(kotlin("reflect")) // Spring Data 의 Kotlin data class 매핑 요구
    runtimeOnly(libs.postgresql)

    if (!productionOnly) {
        testImplementation(project(":blockchain-manager-test-support")) {
            isTransitive = false
        }
    }
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 모듈화 — @DataJdbcTest 는 별도 스타터 (org.springframework.boot.data.jdbc.test.autoconfigure)
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
