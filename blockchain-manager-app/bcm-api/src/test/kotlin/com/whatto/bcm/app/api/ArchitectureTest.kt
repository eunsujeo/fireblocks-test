package com.whatto.bcm.app.api

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * 모듈 의존성 규칙 (docs/standards/architecture.md) — ArchUnit core API 를 일반 @Test 로 호출한다
 * (archunit-junit5 엔진은 JUnit 6 플랫폼에서 안 돈다 — docs/testing.md).
 * allowEmptyShould: Phase 0 은 도메인 클래스가 아직 없다 — 클래스가 생기는 즉시 실검사로 전환된다.
 */
class ArchitectureTest {
    // 규칙 대상은 프로덕션 클래스 — 테스트 클래스는 검사에서 제외 (조립 검증 테스트가 Repository 를 참조한다)
    private val classes =
        ClassFileImporter()
            .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.whatto.bcm")

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
                "java.sql..",
                "javax.sql..",
            ).allowEmptyShould(true)
            .check(classes)
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
                "com.whatto.bcm.infra..",
                "com.whatto.bcm.support..",
            ).allowEmptyShould(true)
            .check(classes)
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
                "com.whatto.bcm.infra..",
            ).allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `infra 는 app 에 의존하지 않는다`() {
        noClasses()
            .that()
            .resideInAPackage("com.whatto.bcm.infra..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.whatto.bcm.app..")
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `production 모듈은 test-support 에 의존하지 않는다`() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "com.whatto.bcm.app..",
                "com.whatto.bcm.domain..",
                "com.whatto.bcm.infra..",
                "com.whatto.bcm.support..",
            ).should()
            .dependOnClassesThat()
            .resideInAPackage("com.whatto.bcm.testsupport..")
            .allowEmptyShould(true)
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
            .allowEmptyShould(true)
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
            .allowEmptyShould(true)
            .check(classes)
    }
}
