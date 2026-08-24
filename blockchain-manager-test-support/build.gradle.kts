plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

val contractArtifactsDirectory = layout.buildDirectory.dir("contracts")
val contractCacheDirectory = layout.buildDirectory.dir("forge-cache")

val compileLocalContracts =
    tasks.register<Exec>("compileLocalContracts") {
        group = "verification"
        description = "Compiles the deterministic local EVM contracts with the pinned Foundry toolchain."
        workingDir(layout.projectDirectory.dir("contracts"))
        commandLine(
            "forge",
            "build",
            "--root",
            layout.projectDirectory
                .dir("contracts")
                .asFile.absolutePath,
            "--out",
            contractArtifactsDirectory.get().asFile.absolutePath,
            "--cache-path",
            contractCacheDirectory.get().asFile.absolutePath,
            "--deny",
            "warnings",
        )
        inputs.file(layout.projectDirectory.file("contracts/foundry.toml"))
        inputs.dir(layout.projectDirectory.dir("contracts/src"))
        outputs.dir(contractArtifactsDirectory)
    }

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(kotlin("reflect"))
    implementation(libs.web3j.crypto) {
        // TRANSFER/CONTRACT_CALL signing does not use blob/KZG or Web3j's JSON model.
        exclude(group = "io.consensys.protocols", module = "jc-kzg-4844")
        exclude(group = "io.consensys.tuweni")
        exclude(group = "tools.jackson.core", module = "jackson-databind")
    }

    testImplementation(project(":blockchain-manager-domain"))
    testImplementation(project(":blockchain-manager-infra:client"))
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    dependsOn(compileLocalContracts)
    systemProperty("bcm.contract-artifacts", contractArtifactsDirectory.get().asFile.absolutePath)
}
