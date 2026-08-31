package com.whatto.bcm.testsupport.database

import java.sql.DriverManager

/** Testcontainers PostgreSQL에 운영 SQL manifest를 컨테이너당 한 번 적용한다. */
object PostgreSqlSchemaInitializer {
    private const val MANIFEST = "db/migration/manifest.txt"
    private const val NON_TRANSACTIONAL_DIRECTIVE = "-- bcm:transaction=off"
    private val scriptNamePattern = Regex("V[0-9]+__.+\\.sql")

    fun initialize(
        jdbcUrl: String,
        username: String,
        password: String,
    ) {
        val classLoader = requireNotNull(Thread.currentThread().contextClassLoader)
        val scriptNames =
            requireNotNull(classLoader.getResourceAsStream(MANIFEST)) {
                "DB schema manifest를 찾을 수 없습니다: $MANIFEST"
            }.bufferedReader().useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }

        require(scriptNames.isNotEmpty()) { "DB schema manifest가 비어 있습니다: $MANIFEST" }

        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            scriptNames.forEach { scriptName ->
                require(scriptNamePattern.matches(scriptName)) {
                    "허용되지 않은 DB schema 파일명입니다: $scriptName"
                }
                val resource = "db/migration/$scriptName"
                val sql =
                    requireNotNull(classLoader.getResourceAsStream(resource)) {
                        "DB schema SQL을 찾을 수 없습니다: $resource"
                    }.bufferedReader().use { it.readText() }

                val nonTransactional = sql.lineSequence().firstOrNull()?.trim() == NON_TRANSACTIONAL_DIRECTIVE
                connection.autoCommit = nonTransactional
                try {
                    connection.createStatement().use { statement ->
                        if (nonTransactional) {
                            sql.splitToSequence(';').filter { it.isNotBlank() }.forEach(statement::execute)
                        } else {
                            statement.execute(sql)
                            connection.commit()
                        }
                    }
                } catch (error: Exception) {
                    if (!nonTransactional) connection.rollback()
                    throw IllegalStateException("DB schema SQL 적용 실패: $resource", error)
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }
}
