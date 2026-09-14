package com.whatto.bcm.testsupport.database

import java.sql.DriverManager

/** 격리된 Testcontainers 데이터셋 전용 원천 fixture. 운영 앱에서는 호출하지 않는다. */
object ProviderOriginTestDatabase {
    fun registerFireblocks(
        jdbcUrl: String,
        username: String,
        password: String,
    ) = register(jdbcUrl, username, password, "fireblocks", "test-fireblocks-origin", "test-platform", "test-organization", "TESTNET")

    fun createLocal(
        jdbcUrl: String,
        username: String,
        password: String,
    ): String {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE bcm_provider_local") }
        }
        val localUrl = jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/bcm_provider_local"
        PostgreSqlSchemaInitializer.initialize(localUrl, username, password)
        register(localUrl, username, password, "local", "test-local-origin", "test-local-platform", "test-local-organization", "LOCAL")
        return localUrl
    }

    private fun register(
        jdbcUrl: String,
        username: String,
        password: String,
        mode: String,
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
                      (1, ?, ?, 'fireblocks', ?, ?, ?, '20260914000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
                    """.trimIndent(),
                ).use { statement ->
                    listOf(origin, mode, instance, organization, chain).forEachIndexed {
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
