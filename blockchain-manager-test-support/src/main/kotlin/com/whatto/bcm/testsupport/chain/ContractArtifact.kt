package com.whatto.bcm.testsupport.chain

import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.io.path.readText

internal data class ContractArtifact(
    val creationBytecode: String,
) {
    companion object {
        fun load(
            artifactDirectory: Path,
            sourceName: String,
            contractName: String,
        ): ContractArtifact {
            val path = artifactDirectory.resolve("$sourceName.sol").resolve("$contractName.json")
            val document = ObjectMapper().readTree(path.readText())
            val bytecode =
                checkNotNull(document.get("bytecode")?.get("object")?.asString()) {
                    "contract bytecode is missing: $path"
                }
            require(bytecode.startsWith("0x") && bytecode.length > 2) { "contract bytecode is empty: $path" }
            return ContractArtifact(bytecode.lowercase())
        }
    }
}
