plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.git.properties) apply false
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.dependency.check)
    `jacoco-report-aggregation`
}

version = file("VERSION").readText().trim()
description = "whatto blockchain-manager"

// subprojects {} 안에서는 version catalog accessor 가 안 잡히므로 여기서 캡처한다
val jacocoVersion = libs.versions.jacoco.get()
val sentryBom = libs.sentry.bom
val log4jBom = libs.log4j.bom

allprojects {
    group = "com.whatto.bcm"
    version = rootProject.version
}

subprojects {
    // 컨테이너 프로젝트(-app · -infra)는 소스가 없으므로 leaf 에만 적용된다
    if (childProjects.isNotEmpty()) return@subprojects

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "com.gorylenko.gradle-git-properties")
    apply(plugin = "jacoco")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = jacocoVersion
    }

    dependencies {
        "implementation"(platform(sentryBom))
        "implementation"(platform(log4jBom))
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            javaParameters.set(true)
            freeCompilerArgs.add("-Xjsr305=strict")
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        finalizedBy(tasks.withType<JacocoReport>())
    }

    tasks.withType<JacocoReport> {
        dependsOn(tasks.withType<Test>())
        reports {
            csv.required.set(false)
            html.required.set(true)
            xml.required.set(true)
        }
    }
}

// ----------------------------------------------------------------------------
//  Analysis — 사내 sonarqube CI 가 jacocoTestReport · jacocoRootReport · check · sonar 를 호출한다
// ----------------------------------------------------------------------------
dependencies {
    subprojects.filter { it.childProjects.isEmpty() }.forEach {
        jacocoAggregation(project(it.path))
    }
}

tasks.register("jacocoRootReport") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "test code coverage report for sonarqube"
    dependsOn(subprojects.filter { it.childProjects.isEmpty() }.map { "${it.path}:jacocoTestReport" })
}

// ----------------------------------------------------------------------------
//  Supply-chain security — 전체 leaf 모듈의 배포 의존성을 한 보고서로 검사한다
// ----------------------------------------------------------------------------
configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    failBuildOnCVSS = 7.0f
    junitFailOnCVSS = 7.0f
    failOnError = true
    failBuildOnUnusedSuppressionRule = true
    formats = listOf("HTML", "JSON", "SARIF", "JUNIT")
    outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))
    scanConfigurations = listOf("runtimeClasspath")
    suppressionFiles = listOf(layout.projectDirectory.file("config/dependency-check-suppressions.xml").asFile.absolutePath)

    nvd.datafeedUrl =
        providers.environmentVariable("NVD_DATAFEED_URL")
            .orElse("https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz")
            .get()

    // 이 저장소는 JVM 프로젝트다. 인증 없는 원격 생태계 analyzer의 가변성은 게이트에서 제외한다.
    analyzers.ossIndex.enabled = false
    analyzers.nodeAudit.enabled = false
    analyzers.retirejs.enabled = false
    analyzers.assemblyEnabled = false
}
