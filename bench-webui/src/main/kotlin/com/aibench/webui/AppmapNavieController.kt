package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * AppMap Navie setup wizard. Lives on its own page (not under /llm)
 * because Navie is a <em>context provider</em>, not an LLM — it rides
 * on top of whatever OpenAI-compatible endpoint it's pointed at (most
 * commonly the local Copilot bridge's HTTP shim) and layers
 * AppMap-driven retrieval over the prompt. Surfacing it under /llm
 * conflated the two concerns, so it gets its own route.
 *
 * <p>The page auto-populates every field with the Copilot bridge's
 * default OpenAI-shim coordinates (loopback, port 11434, no auth) so
 * the common path is a single Save click.
 *
 * <p>Persistence mirrors the Corp-OpenAI wizard:
 * <ul>
 *   <li>Session state holds the live, session-scoped values and the
 *       test-result banner.</li>
 *   <li>{@code ~/.ai-bench/appmap-navie.yaml} persists the harness-visible
 *       config; secrets are not written to disk, only an env-var NAME
 *       pointer is.</li>
 * </ul>
 */
@Controller
class AppmapNavieController(
    private val connectionSettings: ConnectionSettings,
    private val registeredModelsRegistry: RegisteredModelsRegistry
) {

    private val log = LoggerFactory.getLogger(AppmapNavieController::class.java)

    private companion object {
        const val NAVIE_BASE_URL = "appmapNavie.baseUrl"
        const val NAVIE_API_KEY = "appmapNavie.apiKey"
        const val NAVIE_DEFAULT_MODEL = "appmapNavie.defaultModel"
        const val NAVIE_TEST_RESULT = "appmapNavie.testResult"

        /** Default OpenAI-compatible URL the Copilot bridge serves on. */
        const val NAVIE_DEFAULT_BASE_URL = "http://127.0.0.1:11434/v1"
    }

    @GetMapping("/appmap-navie")
    fun page(model: Model, session: HttpSession): String {
        val copilotSock = System.getenv("AI_BENCH_COPILOT_SOCK") ?: Platform.defaultCopilotSocket()
        val copilotHealthy = java.io.File(copilotSock).exists()
        val cliPath = registeredModelsRegistry.appmapCliPath()
        val cliPresent = cliPath != null
        val configPath = System.getProperty("user.home") + "/.ai-bench/appmap-navie.yaml"
        val configExists = java.io.File(configPath).exists()

        val baseUrl = (session.getAttribute(NAVIE_BASE_URL) as? String)
            ?.takeIf { it.isNotBlank() } ?: NAVIE_DEFAULT_BASE_URL
        val apiKeyStored = session.getAttribute(NAVIE_API_KEY) as? String
        val defaultModel = (session.getAttribute(NAVIE_DEFAULT_MODEL) as? String)
            ?.takeIf { it.isNotBlank() } ?: "auto"
        val testResult = session.getAttribute(NAVIE_TEST_RESULT) as? Map<*, *>
        session.removeAttribute(NAVIE_TEST_RESULT)

        val ready = copilotHealthy && cliPresent && configExists

        model.addAttribute("navieBridgeHealthy", copilotHealthy)
        model.addAttribute("navieBridgeSockPath", copilotSock)
        model.addAttribute("navieCliPath", cliPath ?: "")
        model.addAttribute("navieCliPresent", cliPresent)
        model.addAttribute("navieConfigPath", "~/.ai-bench/appmap-navie.yaml")
        model.addAttribute("navieConfigExists", configExists)
        model.addAttribute("navieBaseUrl", baseUrl)
        model.addAttribute("navieApiKeyPresent", !apiKeyStored.isNullOrBlank())
        model.addAttribute("navieDefaultModel", defaultModel)
        model.addAttribute("navieTestResult", testResult)
        model.addAttribute("navieReady", ready)
        model.addAttribute("navieDefaultsBaseUrl", NAVIE_DEFAULT_BASE_URL)
        model.addAttribute("navieIsWindows", Platform.isWindows)
        model.addAttribute("navieIsMac", Platform.isMac)
        return "appmap-navie"
    }

    @PostMapping("/appmap-navie/save")
    fun save(
        @RequestParam baseUrl: String,
        @RequestParam(required = false) apiKey: String?,
        @RequestParam(required = false, defaultValue = "auto") defaultModel: String,
        session: HttpSession
    ): String {
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { NAVIE_DEFAULT_BASE_URL }
        session.setAttribute(NAVIE_BASE_URL, cleanBase)
        session.setAttribute(NAVIE_DEFAULT_MODEL, defaultModel.trim().ifBlank { "auto" })
        if (!apiKey.isNullOrBlank()) {
            session.setAttribute(NAVIE_API_KEY, apiKey.trim())
        }
        runCatching { writeYaml(session) }
            .onFailure { log.warn("Failed to write appmap-navie.yaml", it) }
        return "redirect:/appmap-navie"
    }

    /**
     * Real end-to-end CLI smoke test — spawns the AppMap CLI binary
     * twice:
     * <ol>
     *   <li><code>appmap --version</code> to confirm the binary is
     *       executable on this host.</li>
     *   <li><code>appmap navie "@help …"</code> with
     *       {@code OPENAI_BASE_URL} + {@code OPENAI_API_KEY} pointed
     *       at the saved endpoint — verifies the full CLI → bridge
     *       OpenAI-shim → Copilot chain rather than a curl-only
     *       look-alike.</li>
     * </ol>
     */
    @PostMapping("/appmap-navie/test")
    fun test(session: HttpSession): String {
        val baseUrl = (session.getAttribute(NAVIE_BASE_URL) as? String)
            ?.takeIf { it.isNotBlank() } ?: NAVIE_DEFAULT_BASE_URL
        val apiKey = session.getAttribute(NAVIE_API_KEY) as? String
        val cli = registeredModelsRegistry.appmapCliPath()
        if (cli == null) {
            session.setAttribute(NAVIE_TEST_RESULT, mapOf(
                "success" to false,
                "message" to "AppMap CLI not found on this host. Install per " +
                    "https://appmap.io/docs/install — we look in " +
                    "~/.appmap/bin/, %APPDATA%/AppMap/bin/ (Windows), " +
                    "and every directory on PATH."
            ))
            return "redirect:/appmap-navie"
        }

        val versionRes = runCli(listOf(cli, "--version"), emptyMap(), 10, null)
        if (versionRes.exitCode != 0 || versionRes.timedOut) {
            session.setAttribute(NAVIE_TEST_RESULT, mapOf(
                "success" to false,
                "message" to "CLI at $cli failed to run `appmap --version` " +
                    "(exit=${versionRes.exitCode}${if (versionRes.timedOut) ", timed out" else ""}): " +
                    versionRes.output.take(200).ifBlank { "(no output)" }
            ))
            return "redirect:/appmap-navie"
        }
        val cliVersion = versionRes.output.lineSequence()
            .map { it.trim() }.firstOrNull { it.isNotBlank() } ?: "(unknown)"

        // LangChain's OpenAI client rejects blank api_key, so inject a
        // placeholder the bridge will ignore when auth is off.
        val env = mutableMapOf(
            "OPENAI_BASE_URL" to baseUrl,
            "OPENAI_API_KEY" to (apiKey?.takeIf { it.isNotBlank() } ?: "sk-no-auth-needed")
        )
        val tmpDir = java.nio.file.Files.createTempDirectory("ai-bench-navie-test-").toFile()
        tmpDir.deleteOnExit()

        val navieRes = runCli(
            listOf(cli, "navie", "--directory", tmpDir.absolutePath,
                   "@help Reply with one short sentence confirming this Navie connection works."),
            env, 75, tmpDir
        )
        runCatching { tmpDir.deleteRecursively() }

        val authKeyMissing = navieRes.output.contains("OpenAI or Azure OpenAI API key")
        val connFailure = navieRes.output.contains("ECONNREFUSED") ||
                          navieRes.output.contains("ENOTFOUND") ||
                          navieRes.output.contains("fetch failed")

        val result = when {
            navieRes.timedOut -> mapOf(
                "success" to false,
                "message" to "AppMap CLI v$cliVersion: navie call to $baseUrl timed out after 75s. " +
                    "The endpoint is reachable but the upstream LLM may be slow or hung. " +
                    "stderr tail: ${navieRes.output.takeLast(300).replace("\n", " ")}"
            )
            connFailure -> mapOf(
                "success" to false,
                "message" to "AppMap CLI v$cliVersion could not connect to $baseUrl. " +
                    "Is the Copilot bridge running with the OpenAI shim enabled? " +
                    "(VSCode command: ai-bench: Start Copilot Bridge)"
            )
            authKeyMissing -> mapOf(
                "success" to false,
                "message" to "AppMap CLI v$cliVersion rejected the OpenAI API key. " +
                    "If the bridge has auth enabled, paste its `sk-aibench-…` token " +
                    "in Bearer token above and re-save."
            )
            navieRes.exitCode == 0 -> {
                val preview = navieRes.output.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("Requesting") && !it.startsWith("Received completion") }
                    .toList()
                    .takeLast(3)
                    .joinToString(" ⏎ ")
                mapOf(
                    "success" to true,
                    "message" to "OK — AppMap CLI v$cliVersion routed through $baseUrl. " +
                        "Response preview: ${preview.take(300).ifBlank { "(response captured)" }}"
                )
            }
            else -> mapOf(
                "success" to false,
                "message" to "AppMap CLI v$cliVersion exited ${navieRes.exitCode} calling navie. " +
                    "Last 400 chars: ${navieRes.output.takeLast(400).replace("\n", " ")}"
            )
        }
        session.setAttribute(NAVIE_TEST_RESULT, result)
        return "redirect:/appmap-navie"
    }

    @PostMapping("/appmap-navie/reset")
    fun reset(session: HttpSession): String {
        listOf(NAVIE_BASE_URL, NAVIE_API_KEY, NAVIE_DEFAULT_MODEL, NAVIE_TEST_RESULT)
            .forEach { session.removeAttribute(it) }
        runCatching {
            java.io.File(System.getProperty("user.home"), ".ai-bench/appmap-navie.yaml")
                .delete()
        }
        return "redirect:/appmap-navie"
    }

    private data class CmdResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Spawn an AppMap CLI command with a merged environment. Works on
     * macOS, Linux, and Windows (where the CLI may be a {@code .cmd}
     * shim) because ProcessBuilder handles argv quoting per-platform as
     * long as the absolute executable path sits in {@code cmd[0]}.
     */
    private fun runCli(cmd: List<String>, env: Map<String, String>,
                       timeoutSec: Long, workingDir: java.io.File?): CmdResult {
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            if (workingDir != null) pb.directory(workingDir)
            pb.environment().putAll(env)
            val proc = pb.start()
            val finished = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                val partial = runCatching { proc.inputStream.bufferedReader().readText() }
                    .getOrDefault("")
                proc.destroyForcibly()
                CmdResult(-1, partial, timedOut = true)
            } else {
                val out = proc.inputStream.bufferedReader().readText()
                CmdResult(proc.exitValue(), out, timedOut = false)
            }
        } catch (e: Exception) {
            log.warn("runCli failed: cmd={}", cmd, e)
            CmdResult(-1, "${e.javaClass.simpleName}: ${e.message}", timedOut = false)
        }
    }

    /**
     * Persist {@code ~/.ai-bench/appmap-navie.yaml} for the harness to
     * consume. The bearer token itself is NOT written — an env-var
     * pointer is, and the harness is expected to read the value from
     * the process environment at runtime.
     */
    private fun writeYaml(session: HttpSession): String {
        val baseUrl = (session.getAttribute(NAVIE_BASE_URL) as? String)
            ?.takeIf { it.isNotBlank() } ?: NAVIE_DEFAULT_BASE_URL
        val defaultModel = (session.getAttribute(NAVIE_DEFAULT_MODEL) as? String)
            ?.takeIf { it.isNotBlank() } ?: "auto"
        val apiKeyPresent = !(session.getAttribute(NAVIE_API_KEY) as? String).isNullOrBlank()
        val dir = java.io.File(System.getProperty("user.home"), ".ai-bench")
        dir.mkdirs()
        val file = java.io.File(dir, "appmap-navie.yaml")
        val yaml = buildString {
            appendLine("# Written by bench-webui AppMap Navie wizard.")
            appendLine("# Tells the harness how to invoke `appmap navie` so it")
            appendLine("# routes through the Copilot bridge's OpenAI shim.")
            appendLine()
            appendLine("baseUrl: \"$baseUrl\"")
            appendLine("defaultModel: \"$defaultModel\"")
            if (apiKeyPresent) {
                appendLine("# Bearer token is held in the WebUI session and injected")
                appendLine("# into the `appmap navie` subprocess as OPENAI_API_KEY at run time.")
                appendLine("apiKeyEnv: \"OPENAI_API_KEY\"")
            } else {
                appendLine("# No bearer token configured — bridge must be in unauthenticated")
                appendLine("# loopback mode (the default).")
                appendLine("apiKeyEnv: null")
            }
        }
        file.writeText(yaml)
        return file.absolutePath
    }
}
