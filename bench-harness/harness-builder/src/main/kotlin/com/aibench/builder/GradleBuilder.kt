package com.aibench.builder

import com.aibench.core.Bug
import com.aibench.core.BuildOutcome
import com.aibench.core.Builder
import com.aibench.core.TestOutcome
import org.gradle.tooling.GradleConnector
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/**
 * Builder that drives Gradle projects via the Tooling API. Wires an
 * enterprise init script in front of every invocation when corporate config
 * is present. Captures stdout/stderr for diagnostics.
 */
class GradleBuilder(
    private val cfg: EnterpriseBuildConfig = EnterpriseBuildConfig(),
    private val initScriptWriter: GradleInitScriptWriter = GradleInitScriptWriter(cfg)
) : Builder {

    override fun build(path: Path): BuildOutcome = run(path, listOf("build", "-x", "test"))
        .let { BuildOutcome(it.success, it.log) }

    override fun testAll(path: Path, excludeHidden: Bug.HiddenTest?): TestOutcome {
        val args = mutableListOf("test")
        if (excludeHidden != null) {
            args += listOf("--tests", "*", "--exclude-task-filter", excludeHidden.`class` + "." + excludeHidden.method)
        }
        return run(path, args).let {
            TestOutcome(it.success, passed = 0, failed = 0, log = it.log)  // TODO: parse XML
        }
    }

    override fun runSingleTest(path: Path, test: Bug.HiddenTest): TestOutcome {
        val args = listOf("test", "--tests", "${test.`class`}.${test.method}")
        return run(path, args).let { TestOutcome(it.success, passed = if (it.success) 1 else 0, failed = if (it.success) 0 else 1, log = it.log) }
    }

    private data class Run(val success: Boolean, val log: String)

    private fun run(path: Path, tasks: List<String>): Run {
        val initScript = initScriptWriter.write(path.resolve(".ai-bench"))
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val connector = GradleConnector.newConnector().forProjectDirectory(path.toFile())
        try {
            connector.connect().use { connection ->
                val launcher = connection.newBuild()
                    .forTasks(*tasks.toTypedArray())
                    .setStandardOutput(out)
                    .setStandardError(err)
                    .addArguments("--init-script", initScript.toAbsolutePath().toString())
                    .addArguments(*cfg.extraGradleArgs.toTypedArray())
                cfg.httpsProxy?.let {
                    val (host, port) = it.removePrefix("http://").removePrefix("https://").split(":")
                    launcher.addJvmArguments(
                        "-Dhttps.proxyHost=$host",
                        "-Dhttps.proxyPort=$port"
                    )
                }
                cfg.corpTruststore?.let {
                    launcher.addJvmArguments(
                        "-Djavax.net.ssl.trustStore=${it.toAbsolutePath()}",
                        "-Djavax.net.ssl.trustStorePassword=${cfg.corpTruststorePassword.orEmpty()}"
                    )
                }
                launcher.run()
            }
            return Run(success = true, log = out.toString() + err.toString())
        } catch (t: Throwable) {
            return Run(success = false, log = (out.toString() + err.toString() + "\n" + (t.message ?: "")))
        } finally {
            initScriptWriter.delete(initScript)
        }
    }
}
