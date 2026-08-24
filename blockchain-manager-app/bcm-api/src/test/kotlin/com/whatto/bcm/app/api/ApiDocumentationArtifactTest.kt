package com.whatto.bcm.app.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiDocumentationArtifactTest {
    @Test
    fun `실행 가능한 API 문서가 애플리케이션 classpath에 포함된다`() {
        val loader = javaClass.classLoader

        assertThat(loader.getResource("static/api-docs/index.html")).isNotNull()
        assertThat(loader.getResource("static/api-docs/openapi.yaml")).isNotNull()
        assertThat(loader.getResource("static/api-docs/spec.js")).isNotNull()
        assertThat(loader.getResource("static/api-docs/try-it.js")).isNotNull()
    }
}
