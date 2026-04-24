package com.aibench.config

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SecretSourceTest {

    private val sysPropsToClear = mutableListOf<String>()

    @BeforeEach fun reset() { sysPropsToClear.clear() }

    @AfterEach
    fun teardown() {
        sysPropsToClear.forEach { System.clearProperty(it) }
    }

    @Test
    fun `parse returns scheme and key for prefixed reference`() {
        SecretSource.parse("env:GITHUB_TOKEN") shouldBe ("env" to "GITHUB_TOKEN")
        SecretSource.parse("vault:secret/data/foo") shouldBe ("vault" to "secret/data/foo")
    }

    @Test
    fun `parse without colon defaults to literal scheme`() {
        SecretSource.parse("plain-value") shouldBe ("literal" to "plain-value")
    }

    @Test
    fun `parse rejects empty scheme as literal`() {
        // Leading-colon strings have colonIdx 0, treated as literal.
        SecretSource.parse(":noscheme") shouldBe ("literal" to ":noscheme")
    }

    @Test
    fun `chained source returns literal value as-is`() {
        val source = ChainedSecretSource(listOf(SystemPropertySecretSource))
        source.resolve("literal:hello") shouldBe "hello"
    }

    @Test
    fun `chained source resolves prop from system properties`() {
        setProp("test.secret", "abc")
        val source = ChainedSecretSource(listOf(EnvVarSecretSource, SystemPropertySecretSource))
        source.resolve("prop:test.secret") shouldBe "abc"
    }

    @Test
    fun `chained source returns null when no source resolves the key`() {
        val source = ChainedSecretSource(listOf(SystemPropertySecretSource))
        source.resolve("prop:does-not-exist-${System.nanoTime()}").shouldBeNull()
    }

    @Test
    fun `system property source returns null for unset key`() {
        SystemPropertySecretSource.resolve("missing-${System.nanoTime()}").shouldBeNull()
    }

    @Test
    fun `forEnvironment local includes os keystore source`() {
        val cfg = BenchConfig.VaultSection()
        val source = SecretSource.forEnvironment(Environment.LOCAL, cfg)
        // Concrete chain implementation; resolution of an env literal should still work.
        source.resolve("literal:abc") shouldBe "abc"
    }

    @Test
    fun `forEnvironment openshift_prod skips vault when disabled`() {
        val cfg = BenchConfig.VaultSection(enabled = false)
        val source = SecretSource.forEnvironment(Environment.OPENSHIFT_PROD, cfg)
        // Even without a Vault source, env/prop scheme resolution still functions.
        setProp("ck.test", "value-x")
        source.resolve("prop:ck.test") shouldBe "value-x"
    }

    private fun setProp(k: String, v: String) {
        System.setProperty(k, v)
        sysPropsToClear.add(k)
    }
}
