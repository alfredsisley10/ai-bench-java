package com.aibench.config

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BenchConfigTest {

    @TempDir lateinit var fakeHome: Path
    @TempDir lateinit var projectDir: Path

    private var originalUserHome: String? = null
    private lateinit var configDir: Path

    @BeforeEach
    fun redirectHome() {
        // Redirect user.home so resolveConfigDir() lands in our temp tree.
        // (Env vars cannot be set from the JVM; user.home is the only knob.)
        originalUserHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.toString())
        configDir = fakeHome.resolve(".ai-bench")
        Files.createDirectories(configDir)
    }

    @AfterEach
    fun restoreHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome!!)
        } else {
            System.clearProperty("user.home")
        }
    }

    @Test
    fun `default config is produced when no files are present`() {
        val cfg = BenchConfig.load(Environment.LOCAL)

        cfg.environment shouldBe "local"
        cfg.webui.port shouldBe 7777
        cfg.llm.defaultSolver shouldBe "corp-openai"
    }

    @Test
    fun `overlay file overrides values from base file`() {
        Files.writeString(configDir.resolve("bench-config.yaml"), """
            environment: base
            webui:
              port: 7777
              enableReadOnlyAccess: true
            llm:
              defaultSolver: copilot
        """.trimIndent())
        Files.writeString(configDir.resolve("bench-config-local.yaml"), """
            environment: local
            webui:
              port: 9999
              enableReadOnlyAccess: false
            llm:
              defaultSolver: claude
        """.trimIndent())

        val cfg = BenchConfig.load(Environment.LOCAL)

        cfg.environment shouldBe "local"
        cfg.webui.port shouldBe 9999
        cfg.webui.enableReadOnlyAccess shouldBe false
        cfg.llm.defaultSolver shouldBe "claude"
    }

    @Test
    fun `dotenv values resolve env-prefixed config strings`() {
        Files.writeString(configDir.resolve("bench-config.yaml"), """
            database:
              url: env:DB_CONN
              username: env:DB_USER
        """.trimIndent())
        Files.writeString(projectDir.resolve(".env"), """
            DB_CONN=jdbc:h2:mem:fromdotenv
            DB_USER=fred
        """.trimIndent())

        val cfg = BenchConfig.load(Environment.LOCAL, projectDir)

        cfg.database.url shouldBe "jdbc:h2:mem:fromdotenv"
        cfg.database.username shouldBe "fred"
    }

    @Test
    fun `unresolved env reference in dotenv falls back to literal value`() {
        Files.writeString(configDir.resolve("bench-config.yaml"), """
            database:
              url: env:NEVER_SET_X${System.nanoTime()}
        """.trimIndent())

        val cfg = BenchConfig.load(Environment.LOCAL, projectDir)
        cfg.database.url shouldContain "env:NEVER_SET_X"
    }

    @Test
    fun `resolvedDataDir expands tilde to home directory`() {
        val cfg = BenchConfig(dataDir = "~/something")
        val resolved = cfg.resolvedDataDir().toString()
        resolved shouldContain System.getProperty("user.home")
        resolved shouldContain "something"
    }

    @Test
    fun `overlay missing falls back to base config completely`() {
        Files.writeString(configDir.resolve("bench-config.yaml"), """
            environment: prod
            webui:
              port: 8888
        """.trimIndent())
        // No bench-config-openshift-prod.yaml → base alone wins.
        val cfg = BenchConfig.load(Environment.OPENSHIFT_PROD)
        cfg.environment shouldBe "prod"
        cfg.webui.port shouldBe 8888
    }
}
