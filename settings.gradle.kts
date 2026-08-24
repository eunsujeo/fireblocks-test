pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // JDK 25 toolchain 자동 프로비저닝 — 머신에 JDK 25 가 없어도 빌드가 받아온다 (docs/tooling.md 1절)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "blockchain-manager"

include(
    ":blockchain-manager-app:bcm-api",
    ":blockchain-manager-app:bcm-admin",
    ":blockchain-manager-app:bcm-bat",
    ":blockchain-manager-domain",
    ":blockchain-manager-infra:persistence",
    ":blockchain-manager-infra:client",
    ":blockchain-manager-infra:messaging",
    ":blockchain-manager-support",
)
