package com.whatto.bcm.app.api

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 모듈 의존성 규칙 (docs/standards/architecture.md) — ArchUnit core API 를 일반 @Test 로 호출한다
 * (archunit-junit5 엔진은 JUnit 6 플랫폼에서 안 돈다 — docs/testing.md).
 */
class ArchitectureTest {
    // 실행 모듈을 테스트 classpath에 추가하지 않고 각 모듈의 실제 프로덕션 bytecode를 검사한다.
    private val classDirectories =
        checkNotNull(System.getProperty("bcm.architecture.class-directories"))
            .split(File.pathSeparator)
            .map(::File)
    private val classes = ClassFileImporter().importPaths(classDirectories.map(File::toPath))

    @Test
    fun `모든 실행 모듈의 클래스를 검사한다`() {
        listOf(
            "com.whatto.bcm.app.api.BcmApiApplication",
            "com.whatto.bcm.app.webhook.BcmWebhookApplication",
            "com.whatto.bcm.app.bat.BcmBatApplication",
            "com.whatto.bcm.admin.BcmAdminApplication",
        ).forEach { assertThat(classes.contain(it)).describedAs(it).isTrue() }
    }

    @Test
    fun `프로덕션 모듈에 같은 클래스가 중복되지 않는다`() {
        val owners = mutableMapOf<String, MutableList<String>>()
        classDirectories.forEach { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
                owners.getOrPut(file.relativeTo(directory).invariantSeparatorsPath) { mutableListOf() }.add(directory.path)
            }
        }
        assertThat(owners.filterValues { it.size > 1 }).isEmpty()
    }

    @Test
    fun `공유 application 은 실행 어댑터와 스케줄러를 포함하지 않는다`() {
        val shared = ClassFileImporter().importPath(checkNotNull(System.getProperty("bcm.architecture.shared-classes")))
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.scheduling..",
                "org.springframework.web..",
                "com.whatto.bcm.app.api..",
                "com.whatto.bcm.app.webhook..",
                "com.whatto.bcm.app.bat..",
                "com.whatto.bcm.admin..",
                "com.whatto.bcm.infra..",
            ).check(shared)
    }

    @Test
    fun `실행 어댑터는 다른 실행 모듈에 의존하지 않는다`() {
        val modules =
            listOf("bcm-api", "bcm-webhook", "bcm-bat", "bcm-admin").associateWith {
                ClassFileImporter().importPath(checkNotNull(System.getProperty("bcm.architecture.$it-classes")))
            }
        modules.forEach { (name, ownClasses) ->
            val foreignTypes =
                modules
                    .filterKeys { it != name }
                    .values
                    .flatMap { it.map(JavaClass::getName) }
                    .toSet()
            noClasses()
                .should()
                .dependOnClassesThat(
                    object : DescribedPredicate<JavaClass>("다른 실행 모듈의 클래스") {
                        override fun test(type: JavaClass): Boolean = type.name in foreignTypes
                    },
                ).check(ownClasses)
        }
    }

    @Test
    fun `검토한 복합 유스케이스는 다른 피처의 Repository를 직접 참조하지 않는다`() {
        mapOf(
            "WebhookDecisionTransaction" to "webhook",
            "AllowanceRevocationCommandService" to "admin",
            "SweepBatchExecutionService" to "sweep",
        ).forEach { (service, feature) ->
            noClasses()
                .that()
                .haveSimpleName(service)
                .should()
                .dependOnClassesThat(
                    object : DescribedPredicate<JavaClass>("다른 피처 Repository") {
                        override fun test(type: JavaClass): Boolean =
                            type.simpleName.endsWith("Repository") && type.packageName != "com.whatto.bcm.domain.$feature"
                    },
                ).check(classes)
        }
    }

    @Test
    fun `domain 은 프레임워크(Spring · JDBC · Jackson)에 의존하지 않는다`() {
        noClasses()
            .that()
            .resideInAPackage("com.whatto.bcm.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "com.fasterxml..",
                "tools.jackson..",
                "java.sql..",
                "javax.sql..",
            ).check(classes)
    }

    @Test
    fun `domain 은 다른 모듈(app · infra · support)에 의존하지 않는다`() {
        noClasses()
            .that()
            .resideInAPackage("com.whatto.bcm.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.whatto.bcm.app..",
                "com.whatto.bcm.admin..",
                "com.whatto.bcm.infra..",
                "com.whatto.bcm.support..",
            ).check(classes)
    }

    @Test
    fun `support 는 domain 외 모듈에 의존하지 않는다`() {
        noClasses()
            .that()
            .resideInAPackage("com.whatto.bcm.support..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.whatto.bcm.app..",
                "com.whatto.bcm.admin..",
                "com.whatto.bcm.infra..",
            ).check(classes)
    }

    @Test
    fun `infra 는 app 에 의존하지 않는다`() {
        noClasses()
            .that()
            .resideInAPackage("com.whatto.bcm.infra..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.whatto.bcm.app..", "com.whatto.bcm.admin..")
            .check(classes)
    }

    @Test
    fun `production 모듈은 test-support 에 의존하지 않는다`() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "com.whatto.bcm.app..",
                "com.whatto.bcm.admin..",
                "com.whatto.bcm.domain..",
                "com.whatto.bcm.infra..",
                "com.whatto.bcm.support..",
            ).should()
            .dependOnClassesThat()
            .resideInAPackage("com.whatto.bcm.testsupport..")
            .check(classes)
    }

    @Test
    fun `물리 컬럼 매핑(spring-data-relational)은 infra 한정이다`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("com.whatto.bcm.infra..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.data.relational..")
            .check(classes)
    }

    @Test
    fun `api 계층은 Repository 에 직접 의존하지 않는다`() {
        noClasses()
            .that()
            .resideInAPackage("com.whatto.bcm.app.api..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .check(classes)
    }
}
