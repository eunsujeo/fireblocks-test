// 벤더(Fireblocks) API 클라이언트 소관 모듈 — JWT 서명·429 백오프·벤더 에러의 도메인 예외 변환.
// 벤더 원어 필드는 이 모듈 밖으로 새지 않는다 (도메인 포트 WalletVendorPort 로만 노출).
plugins {
    // @Configuration CGLIB 프록시가 final 클래스에 못 붙는다 — Spring 빈 보유 모듈은 allopen (tooling.md)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":blockchain-manager-domain"))
    implementation(project(":blockchain-manager-support"))
    // Boot 4 모듈화 — RestClient 배선(HTTP 클라이언트 + Jackson converter)은 restclient 스타터 소관
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation(kotlin("reflect"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val fireblocksContractTest = sourceSets.create("fireblocksContractTest")
fireblocksContractTest.compileClasspath += sourceSets.main.get().output
fireblocksContractTest.runtimeClasspath += sourceSets.main.get().output

configurations[fireblocksContractTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[fireblocksContractTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("fireblocksContractTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs explicitly approved read-only contracts against FIREBLOCKS+TESTNET."
    testClassesDirs = fireblocksContractTest.output.classesDirs
    classpath = fireblocksContractTest.runtimeClasspath
    useJUnitPlatform()
    doFirst {
        val officialFireblocksOrigin = "https://api.fireblocks.io"
        val configuredBaseUrl = System.getenv("BCM_FIREBLOCKS_BASE_URL")?.removeSuffix("/")
        check(configuredBaseUrl == officialFireblocksOrigin) {
            "real Fireblocks contract test requires official Fireblocks API origin $officialFireblocksOrigin"
        }
        check(System.getenv("BCM_FIREBLOCKS_CONTRACT_TEST_SCOPE") == "READ_ONLY") {
            "real Fireblocks contract test requires READ_ONLY scope"
        }
        check(!System.getenv("BCM_FIREBLOCKS_CONTRACT_TEST_APPROVAL_ID").isNullOrBlank()) {
            "real Fireblocks contract test requires an execution approval id"
        }
        check(System.getenv("BCM_VENDOR_MODE") == "FIREBLOCKS" && System.getenv("BCM_CHAIN_MODE") == "TESTNET") {
            "real Fireblocks contract test requires FIREBLOCKS+TESTNET"
        }
        check(!System.getenv("BCM_FIREBLOCKS_API_KEY").isNullOrBlank()) { "real Fireblocks API key is missing" }
        check(!System.getenv("BCM_FIREBLOCKS_PRIVATE_KEY_FILE").isNullOrBlank()) { "real Fireblocks private key file is missing" }
        check(!System.getenv("BCM_FIREBLOCKS_CONTRACT_BLOCKCHAIN_ID").isNullOrBlank()) {
            "real Fireblocks blockchain id is missing"
        }
    }
}
