package com.whatto.bcm.testsupport.database

import java.sql.DriverManager

/** 격리된 Testcontainers 데이터셋 전용 원천 fixture. 운영 앱에서는 호출하지 않는다. */
object ProviderOriginTestDatabase {
    fun registerFireblocks(
        jdbcUrl: String,
        username: String,
        password: String,
    ) = register(
        jdbcUrl,
        username,
        password,
        "fireblocks",
        "fireblocks",
        "test-fireblocks-origin",
        "test-platform",
        "test-organization",
        "TESTNET",
    )

    fun createLocal(
        jdbcUrl: String,
        username: String,
        password: String,
    ): String =
        createDataset(jdbcUrl, username, password, "bcm_provider_local") { localUrl ->
            register(
                localUrl,
                username,
                password,
                "local",
                "fireblocks",
                "test-local-origin",
                "test-local-platform",
                "test-local-organization",
                "LOCAL",
            )
        }

    /**
     * Dfns 원천만 등록된 빈 데이터셋. 논리 계정·네트워크 지갑 원장 결합 검증용이며 Dfns 기동 차단 해제나 실벤더 연결을 뜻하지 않는다.
     */
    fun createDfns(
        jdbcUrl: String,
        username: String,
        password: String,
    ): String =
        createDataset(jdbcUrl, username, password, "bcm_provider_dfns") { dfnsUrl ->
            register(
                dfnsUrl,
                username,
                password,
                "dfns",
                "dfns",
                "test-dfns-origin",
                "test-dfns-platform",
                "test-dfns-organization",
                "TESTNET",
            )
        }

    private fun createDataset(
        jdbcUrl: String,
        username: String,
        password: String,
        database: String,
        registerOrigin: (String) -> Unit,
    ): String {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $database") }
        }
        val datasetUrl = jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/" + database
        PostgreSqlSchemaInitializer.initialize(datasetUrl, username, password)
        registerOrigin(datasetUrl)
        return datasetUrl
    }

    private fun register(
        jdbcUrl: String,
        username: String,
        password: String,
        mode: String,
        protocol: String,
        origin: String,
        instance: String,
        organization: String,
        chain: String,
    ) {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO bcm_prvd_bndg_m VALUES
                      (1, ?, ?, ?, ?, ?, ?, '20260914000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
                    """.trimIndent(),
                ).use { statement ->
                    listOf(origin, mode, protocol, instance, organization, chain).forEachIndexed {
                        index,
                        value,
                        ->
                        statement.setString(index + 1, value)
                    }
                    statement.executeUpdate()
                }
        }
    }
}
