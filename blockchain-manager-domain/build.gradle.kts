// 도메인 — 순수 Kotlin. Spring/JDBC 의존 금지 (docs/standards/architecture.md)
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
