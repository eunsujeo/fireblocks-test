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
