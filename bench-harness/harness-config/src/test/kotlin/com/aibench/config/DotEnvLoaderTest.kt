package com.aibench.config

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DotEnvLoaderTest {

    @Test
    fun `parses simple key-value pairs`() {
        val content = """
            DB_HOST=localhost
            DB_PORT=5432
        """.trimIndent()
        DotEnvLoader.parse(content) shouldContainExactly mapOf(
            "DB_HOST" to "localhost",
            "DB_PORT" to "5432"
        )
    }

    @Test
    fun `strips double and single quotes`() {
        val content = """
            SECRET="my-secret"
            TOKEN='tok-123'
        """.trimIndent()
        DotEnvLoader.parse(content) shouldContainExactly mapOf(
            "SECRET" to "my-secret",
            "TOKEN" to "tok-123"
        )
    }

    @Test
    fun `skips comments and blank lines`() {
        val content = """
            # this is a comment

            KEY=value

            # another comment
        """.trimIndent()
        DotEnvLoader.parse(content) shouldContainExactly mapOf("KEY" to "value")
    }

    @Test
    fun `handles equals in value`() {
        val content = "CONN=jdbc:h2:mem:test;MODE=PostgreSQL"
        DotEnvLoader.parse(content) shouldContainExactly mapOf(
            "CONN" to "jdbc:h2:mem:test;MODE=PostgreSQL"
        )
    }

    @Test
    fun `empty content returns empty map`() {
        DotEnvLoader.parse("") shouldBe emptyMap()
    }
}
