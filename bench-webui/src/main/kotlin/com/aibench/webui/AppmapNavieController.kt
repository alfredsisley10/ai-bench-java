package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

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

    /**
     * In-flight + recently-completed Verify runs, keyed by run id. Bounded
     * to the last 4 runs so the operator can flip back to a previous run's
     * step output without leaking unbounded memory. One worker thread —
     * the test is short and one-at-a-time keeps the UX (and the bridge
     * load) predictable.
     */
    private val testRuns = ConcurrentHashMap<String, NavieTestRun>()
    private val testExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "appmap-navie-test").apply { isDaemon = true }
    }

    /**
     * Snapshot of one Verify run. Mutable @Volatile fields are safe to
     * read from the polling GET while the worker thread updates them —
     * the JSON serializer reads each field once, races yield slightly
     * stale reads, never torn objects.
     */
    data class NavieTestRun(
        val id: String,
        val startedAt: Instant,
        @Volatile var endedAt: Instant? = null,
        /** queued | running | done | failed */
        @Volatile var status: String = "queued",
        @Volatile var success: Boolean? = null,
        @Volatile var message: String = "",
        val steps: MutableList<NavieTestStep> = CopyOnWriteArrayList()
    )

    /** One stage of a Verify run. Three stages today: locate-cli, cli-version, bridge-probe. */
    data class NavieTestStep(
        val name: String,
        @Volatile var status: String = "pending",   // pending | running | passed | failed
        @Volatile var startedAt: Instant? = null,
        @Volatile var endedAt: Instant? = null,
        @Volatile var command: String = "",
        @Volatile var output: String = "",
        @Volatile var detail: String = "",
        @Volatile var exitCode: Int? = null
    ) {
        val durationMs: Long? get() {
            val s = startedAt ?: return null
            val e = endedAt ?: return null
            return e.toEpochMilli() - s.toEpochMilli()
        }
    }

    private companion object {
        const val NAVIE_BASE_URL = "appmapNavie.baseUrl"
        const val NAVIE_API_KEY = "appmapNavie.apiKey"
        const val NAVIE_DEFAULT_MODEL = "appmapNavie.defaultModel"
        const val NAVIE_TEST_RESULT = "appmapNavie.testResult"
        const val NAVIE_TEST_ID = "appmapNavie.testId"

        /** Default OpenAI-compatible URL the Copilot bridge serves on. */
        const val NAVIE_DEFAULT_BASE_URL = "http://127.0.0.1:11434/v1"

        /**
         * Validated model IDs offered in the "Default model" dropdown.
         * Free-text was prone to typos — this set covers the common
         * Copilot-served catalog (OpenAI + Anthropic + reasoning) plus
         * "auto" for "let the bridge pick". If the user needs a model
         * outside this list they can pin it from the
         * <code>/llm#copilot</code> enumeration page where the bridge's
         * live model list is captured.
         */
        val NAVIE_MODEL_OPTIONS: List<Pair<String, String>> = listOf(
            "auto"                  to "auto — let the bridge pick Copilot's default",
            "gpt-4o"                to "gpt-4o (OpenAI)",
            "gpt-4o-mini"           to "gpt-4o-mini (OpenAI)",
            "gpt-4.1"               to "gpt-4.1 (OpenAI)",
            "gpt-4"                 to "gpt-4 (OpenAI)",
            "gpt-3.5-turbo"         to "gpt-3.5-turbo (OpenAI)",
            "o1"                    to "o1 (OpenAI reasoning)",
            "o1-mini"               to "o1-mini (OpenAI reasoning)",
            "o3-mini"               to "o3-mini (OpenAI reasoning)",
            "claude-3-5-sonnet"     to "claude-3-5-sonnet (Anthropic)",
            "claude-3-7-sonnet"     to "claude-3-7-sonnet (Anthropic)",
            "claude-3-5-haiku"      to "claude-3-5-haiku (Anthropic)"
        )
    }

    @GetMapping("/appmap-navie")
    fun page(model: Model, session: HttpSession): String {
        // Bridge liveness = port sidecar present AND TCP connect on
        // 127.0.0.1 succeeds. File-existence alone can outlive a crashed
        // bridge.
        val copilotPort = Platform.readCopilotPort()
        val copilotSock = copilotPort?.let { "127.0.0.1:$it" } ?: Platform.copilotPortFile()
        val copilotHealthy = copilotPort != null && runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", copilotPort), 500); true
            }
        }.getOrDefault(false)
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
        // Expose the active test id (if any) so the template can mount
        // the live-progress poller for that run. Don't clear the id —
        // the user may refresh the page mid-test, and we want the poll
        // to resume.
        val testId = session.getAttribute(NAVIE_TEST_ID) as? String

        // Probe the OpenAI shim at the saved (or default) base URL. The
        // shim is a separate HTTP server from the bridge IPC socket, so
        // copilotHealthy alone doesn't tell us if `appmap navie` will
        // succeed. Treat 200 / 401 / 403 as "endpoint is up" — the
        // user's auth might still be wrong but the network path works.
        val savedBaseUrl = (session.getAttribute(NAVIE_BASE_URL) as? String)
            ?.takeIf { it.isNotBlank() } ?: NAVIE_DEFAULT_BASE_URL
        val (shimReachable, shimDetail) = runCatching {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("$savedBaseUrl/models"))
                .timeout(java.time.Duration.ofSeconds(2))
                .GET().build()
            val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(2))
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding())
            val code = resp.statusCode()
            (code == 200 || code == 401 || code == 403) to "HTTP $code from $savedBaseUrl/models"
        }.getOrElse { false to connectionSettings.formatProbeException(it) }

        val ready = copilotHealthy && shimReachable && cliPresent && configExists

        model.addAttribute("navieBridgeHealthy", copilotHealthy)
        model.addAttribute("navieShimReachable", shimReachable)
        model.addAttribute("navieShimDetail", shimDetail)
        model.addAttribute("navieBridgeSockPath", copilotSock)
        model.addAttribute("navieCliPath", cliPath ?: "")
        model.addAttribute("navieCliPresent", cliPresent)
        model.addAttribute("navieConfigPath", "~/.ai-bench/appmap-navie.yaml")
        model.addAttribute("navieConfigExists", configExists)
        model.addAttribute("navieBaseUrl", baseUrl)
        model.addAttribute("navieApiKeyPresent", !apiKeyStored.isNullOrBlank())
        model.addAttribute("navieDefaultModel", defaultModel)
        model.addAttribute("navieModelOptions", NAVIE_MODEL_OPTIONS.map {
            mapOf("id" to it.first, "label" to it.second)
        })
        model.addAttribute("navieTestResult", testResult)
        model.addAttribute("navieTestId", testId)
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
        // Constrain to the allowlist so a stale browser cache or hand-
        // crafted POST can't smuggle in an invalid model id.
        val validModelIds = NAVIE_MODEL_OPTIONS.map { it.first }.toSet()
        val cleanModel = defaultModel.trim().takeIf { it in validModelIds } ?: "auto"
        session.setAttribute(NAVIE_DEFAULT_MODEL, cleanModel)
        if (!apiKey.isNullOrBlank()) {
            session.setAttribute(NAVIE_API_KEY, apiKey.trim())
        }
        runCatching { writeYaml(session) }
            .onFailure { log.warn("Failed to write appmap-navie.yaml", it) }
        return "redirect:/appmap-navie"
    }

    /**
     * Verify — basic end-to-end probe. Three quick steps:
     * <ol>
     *   <li>Locate the AppMap CLI binary (no I/O — just path resolution).</li>
     *   <li><code>appmap --version</code> — proves the binary is executable.</li>
     *   <li>Direct HTTP POST to <code>{baseUrl}/chat/completions</code> with
     *       <code>max_tokens=5</code> — proves the bridge speaks OpenAI and
     *       that one round-trip lands in the LLM.</li>
     * </ol>
     *
     * <p>Earlier versions invoked <code>appmap navie "@help …"</code>, which
     * triggered Navie's full agent loop (retrieval, tool calls, multi-turn
     * reasoning) and could fire 100+ Copilot bridge requests for what was
     * supposed to be a connectivity check. The point of Verify is that
     * <em>one</em> request reaches the LLM and comes back; if it does, the
     * harness's own Navie invocations at benchmark time will too. This
     * version does that one request directly.
     *
     * <p>Runs asynchronously: the POST returns immediately with a redirect
     * carrying the run id, and the page polls <code>/appmap-navie/test/{id}/status</code>
     * for live step-by-step progress.
     */
    @PostMapping("/appmap-navie/test")
    fun test(session: HttpSession): String {
        val baseUrl = (session.getAttribute(NAVIE_BASE_URL) as? String)
            ?.takeIf { it.isNotBlank() } ?: NAVIE_DEFAULT_BASE_URL
        val apiKey = session.getAttribute(NAVIE_API_KEY) as? String
        val defaultModel = (session.getAttribute(NAVIE_DEFAULT_MODEL) as? String)
            ?.takeIf { it.isNotBlank() } ?: "auto"

        val runId = UUID.randomUUID().toString().take(8)
        val run = NavieTestRun(id = runId, startedAt = Instant.now()).apply {
            steps += NavieTestStep("Locate AppMap CLI")
            steps += NavieTestStep("Verify CLI is executable")
            steps += NavieTestStep("Probe bridge OpenAI endpoint")
        }
        testRuns[runId] = run
        // Cap memory: keep last 4 runs.
        if (testRuns.size > 4) {
            testRuns.entries.sortedBy { it.value.startedAt }
                .take(testRuns.size - 4)
                .forEach { testRuns.remove(it.key) }
        }
        session.setAttribute(NAVIE_TEST_ID, runId)
        // Wipe the legacy banner so the page renders the new live panel
        // instead of stale "previous test" text.
        session.removeAttribute(NAVIE_TEST_RESULT)

        testExecutor.submit { runVerifySteps(run, baseUrl, apiKey, defaultModel, session) }
        return "redirect:/appmap-navie"
    }

    /** Live status JSON for the polling UI. */
    @GetMapping("/appmap-navie/test/{id}/status")
    @ResponseBody
    fun testStatus(@org.springframework.web.bind.annotation.PathVariable id: String): NavieTestRun? =
        testRuns[id]

    private fun runVerifySteps(
        run: NavieTestRun,
        baseUrl: String,
        apiKey: String?,
        defaultModel: String,
        session: HttpSession
    ) {
        run.status = "running"
        val (locate, version, probe) = Triple(run.steps[0], run.steps[1], run.steps[2])

        // Step 1 — locate CLI ----------------------------------------------
        locate.status = "running"; locate.startedAt = Instant.now()
        val cli = registeredModelsRegistry.appmapCliPath()
        locate.endedAt = Instant.now()
        if (cli == null) {
            locate.status = "failed"
            locate.detail = "AppMap CLI not found on this host. Install per https://appmap.io/docs/install — we look in ~/.appmap/bin/, %APPDATA%/AppMap/bin/ (Windows), and every directory on PATH."
            failRun(run, locate.detail, session)
            return
        }
        locate.status = "passed"
        locate.detail = cli
        locate.command = "(path resolution)"

        // Step 2 — appmap --version ----------------------------------------
        version.status = "running"; version.startedAt = Instant.now()
        version.command = "$cli --version"
        val versionRes = runCli(listOf(cli, "--version"), emptyMap(), 10, null)
        version.output = versionRes.output.trimEnd()
        version.exitCode = versionRes.exitCode
        version.endedAt = Instant.now()
        if (versionRes.exitCode != 0 || versionRes.timedOut) {
            version.status = "failed"
            version.detail = if (versionRes.timedOut) "Timed out after 10s"
                             else "Exit ${versionRes.exitCode}"
            failRun(run, "CLI at $cli failed to run --version (${version.detail}). Output: ${version.output.take(200).ifBlank { "(none)" }}", session)
            return
        }
        val cliVersion = versionRes.output.lineSequence()
            .map { it.trim() }.firstOrNull { it.isNotBlank() } ?: "(unknown)"
        version.status = "passed"
        version.detail = "v$cliVersion"

        // Step 3 — direct HTTP probe ---------------------------------------
        probe.status = "running"; probe.startedAt = Instant.now()
        val effectiveKey = apiKey?.takeIf { it.isNotBlank() } ?: "sk-no-auth-needed"
        probe.command = "POST ${baseUrl.trimEnd('/')}/chat/completions  (model=$defaultModel, max_tokens=5)"
        val probeResult = bridgeChatProbe(baseUrl, effectiveKey, defaultModel)
        probe.output = probeResult.bodyPreview
        probe.exitCode = probeResult.httpStatus
        probe.endedAt = Instant.now()
        if (probeResult.success) {
            probe.status = "passed"
            probe.detail = "HTTP ${probeResult.httpStatus} in ${probe.durationMs}ms · reply: ${probeResult.replySnippet.take(120).ifBlank { "(empty)" }}"
            succeedRun(run, "OK — AppMap CLI v$cliVersion + bridge at $baseUrl returned a response in ${probe.durationMs}ms.", session)
        } else {
            probe.status = "failed"
            probe.detail = probeResult.diagnosticHint
            failRun(run, "Bridge probe failed (HTTP ${probeResult.httpStatus}): ${probeResult.diagnosticHint}", session)
        }
    }

    private fun failRun(run: NavieTestRun, message: String, session: HttpSession) {
        run.success = false
        run.message = message
        run.status = "failed"
        run.endedAt = Instant.now()
        // Legacy banner support — anything reading NAVIE_TEST_RESULT still works.
        session.setAttribute(NAVIE_TEST_RESULT, mapOf("success" to false, "message" to message))
    }

    private fun succeedRun(run: NavieTestRun, message: String, session: HttpSession) {
        run.success = true
        run.message = message
        run.status = "done"
        run.endedAt = Instant.now()
        session.setAttribute(NAVIE_TEST_RESULT, mapOf("success" to true, "message" to message))
    }

    /** Single chat-completions probe. One round-trip, max_tokens=5. */
    private fun bridgeChatProbe(baseUrl: String, apiKey: String, model: String): BridgeProbeResult {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val payload = """{"model":"${model.replace("\"","\\\"")}",""" +
            """"messages":[{"role":"user","content":"Reply with the single word: ok"}],""" +
            """"max_tokens":5,"temperature":0}"""
        return try {
            val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(5))
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            val body = resp.body() ?: ""
            val reply = extractAssistantReply(body)
            val ok = resp.statusCode() == 200 && reply.isNotBlank()
            BridgeProbeResult(
                success = ok,
                httpStatus = resp.statusCode(),
                bodyPreview = body.take(2000),
                replySnippet = reply,
                diagnosticHint = when {
                    ok -> ""
                    resp.statusCode() == 401 -> "Bridge rejected the API key. If the bridge has auth enabled, paste its `sk-aibench-…` token in Bearer token above and re-save."
                    resp.statusCode() == 404 -> "Bridge returned 404. Confirm the base URL ends with /v1 (we POST to {base}/chat/completions)."
                    resp.statusCode() in 500..599 -> "Bridge returned ${resp.statusCode()}. Check the VSCode 'AI Bench Copilot Bridge' output panel for upstream Copilot errors."
                    else -> "HTTP ${resp.statusCode()} — body: ${body.take(300).ifBlank { "(empty)" }}"
                }
            )
        } catch (e: java.net.ConnectException) {
            BridgeProbeResult(false, 0, "", "", "Could not connect to $url. Is the Copilot bridge running? (VSCode command: ai-bench: Start Copilot Bridge)")
        } catch (e: java.net.http.HttpTimeoutException) {
            BridgeProbeResult(false, 0, "", "", "Bridge did not respond within 30s. Upstream Copilot may be slow or hung; check the VSCode output panel.")
        } catch (e: Exception) {
            BridgeProbeResult(false, 0, "", "", "${e.javaClass.simpleName}: ${e.message ?: "(no detail)"}")
        }
    }

    /** Parse "choices[0].message.content" out of a chat-completions response without a JSON dep. */
    private fun extractAssistantReply(body: String): String {
        // Cheap pattern-extract: works for the common shape returned by the
        // bridge. Falls back to "" so an empty/malformed response is treated
        // as an unsuccessful probe.
        val m = Regex("""\"content\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"""").find(body) ?: return ""
        return m.groupValues[1]
            .replace("\\n", " ").replace("\\\"", "\"")
            .replace("\\\\", "\\").trim()
    }

    data class BridgeProbeResult(
        val success: Boolean,
        val httpStatus: Int,
        val bodyPreview: String,
        val replySnippet: String,
        val diagnosticHint: String
    )

    /**
     * Install the AppMap CLI via the npm-global package
     * <code>@appland/appmap</code>. Wraps the call in <code>cmd /c</code>
     * (Windows) or <code>bash -lc</code> (Unix) so the shell's PATH —
     * including npm/nvm/asdf shims — is honored. Returns JSON
     * <code>{ ok, message, output }</code> for the in-page collapsible
     * output panel.
     */
    @PostMapping("/appmap-navie/install/npm")
    @org.springframework.web.bind.annotation.ResponseBody
    fun installViaNpm(): Map<String, Any> {
        val cmdLine = "npm install -g @appland/appmap"
        val cmd = if (Platform.isWindows)
            listOf("cmd", "/c", cmdLine)
        else
            listOf("bash", "-lc", cmdLine)
        val log = StringBuilder()
        log.appendLine("$ $cmdLine")
        return runCatching {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText()
            if (out.isNotBlank()) {
                log.append(out)
                if (!out.endsWith("\n")) log.append("\n")
            }
            if (!finished) {
                proc.destroyForcibly()
                return mapOf("ok" to false,
                    "message" to "Install timed out after 180s.",
                    "output" to log.toString())
            }
            if (proc.exitValue() == 0) {
                val cliPath = registeredModelsRegistry.appmapCliPath()
                if (cliPath != null) {
                    log.appendLine("[ok]   appmap CLI now resolves at: $cliPath")
                    mapOf("ok" to true,
                        "message" to "Installed. Refresh the page to clear the warning.",
                        "output" to log.toString())
                } else {
                    log.appendLine("[warn] npm reported success but appmap is not on PATH yet.")
                    log.appendLine("       Open a new shell so the npm-global bin dir is picked up,")
                    log.appendLine("       then refresh this page.")
                    mapOf("ok" to true,
                        "message" to "npm install succeeded; restart bench-webui (or open a new shell) to pick up PATH.",
                        "output" to log.toString())
                }
            } else {
                log.appendLine("[err]  npm exited ${proc.exitValue()}.")
                mapOf("ok" to false,
                    "message" to "npm install failed (exit ${proc.exitValue()}). Is Node.js / npm on PATH?",
                    "output" to log.toString())
            }
        }.getOrElse { e ->
            log.appendLine("[err]  ${e.javaClass.simpleName}: ${e.message}")
            mapOf("ok" to false,
                "message" to "Failed to spawn npm: ${e.message}. Install Node.js first.",
                "output" to log.toString())
        }
    }

    /**
     * Quick "is npm reachable?" probe so users can confirm prerequisites
     * before kicking off an install. Same shell-wrapping pattern as the
     * install endpoint so PATH semantics match.
     */
    /**
     * Auto-detect the AI-Bridge's OpenAI shim, apply it as the saved
     * Navie base URL, and run the same end-to-end CLI round-trip the
     * <em>Test</em> button performs — all in one click. Probes the
     * configured shim port (default 11434) plus a couple of common
     * neighbors in case the operator changed
     * <code>aiBench.copilotBridge.openAiPort</code> in the VSCode
     * extension.
     */
    @PostMapping("/appmap-navie/detect-bridge")
    @org.springframework.web.bind.annotation.ResponseBody
    fun detectBridge(session: HttpSession): Map<String, Any> {
        val log = StringBuilder()
        log.appendLine("[info] Probing for AI-Bridge OpenAI shim on loopback…")
        // Try the default first, then a couple of common alternatives.
        // The bridge IPC port file (~/.ai-bench-copilot.port) is the
        // bridge-control socket, NOT the OpenAI shim — so it can't tell
        // us the shim port directly. We try the documented default and
        // a small probe range and pick whatever answers.
        val candidatePorts = linkedSetOf(11434, 8088, 8089, 11435, 11436, 11437)
        var found: Pair<Int, String>? = null
        for (port in candidatePorts) {
            val baseUrl = "http://127.0.0.1:$port/v1"
            log.appendLine("[info] Trying $baseUrl/models …")
            val (ok, code, snippet) = probeShim(baseUrl)
            if (ok) {
                log.appendLine("[ok]   HTTP $code from $baseUrl")
                found = port to baseUrl
                break
            } else {
                log.appendLine("[skip] $baseUrl → $snippet")
            }
        }
        if (found == null) {
            return mapOf(
                "ok" to false,
                "message" to "Couldn't find an OpenAI shim on any of: " +
                    candidatePorts.joinToString(", ") { "127.0.0.1:$it" } +
                    ". Open the AI-Bridge VSCode extension's webview, toggle " +
                    "'Enable OpenAI shim', and retry.",
                "baseUrl" to "",
                "output" to log.toString()
            )
        }
        val (_, baseUrl) = found
        // Apply: stash in session (the page reads NAVIE_BASE_URL on next
        // render) and persist YAML so the harness picks up the change too.
        session.setAttribute(NAVIE_BASE_URL, baseUrl)
        if (session.getAttribute(NAVIE_DEFAULT_MODEL) == null) {
            session.setAttribute(NAVIE_DEFAULT_MODEL, "auto")
        }
        runCatching { writeYaml(session) }
            .onFailure { log.appendLine("[warn] Failed to persist YAML: ${it.message}") }
        log.appendLine("[ok]   Saved baseUrl='$baseUrl' to session + ~/.ai-bench/appmap-navie.yaml")
        return mapOf(
            "ok" to true,
            "message" to "Detected and applied $baseUrl. Click Test via AppMap CLI to confirm the full round-trip.",
            "baseUrl" to baseUrl,
            "output" to log.toString()
        )
    }

    /**
     * Send a single GET to <code>${baseUrl}/models</code> with a 2-second
     * timeout. Treats 200 as a hit; treats 401/403 as also a hit (the
     * shim is up, just rejecting our anonymous request) since the
     * point of detection is "is the endpoint live?", not "do we have
     * auth right." Returns (ok, status, hintForLog).
     */
    private fun probeShim(baseUrl: String): Triple<Boolean, Int, String> {
        return runCatching {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("$baseUrl/models"))
                .timeout(java.time.Duration.ofSeconds(2))
                .GET().build()
            val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(2))
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding())
            val code = resp.statusCode()
            // 200 = open; 401/403 = up but auth required; either way the
            // shim is live and the user can fix auth in the form.
            val ok = code == 200 || code == 401 || code == 403
            Triple(ok, code, "HTTP $code")
        }.getOrElse {
            Triple(false, -1, connectionSettings.formatProbeException(it))
        }
    }

    @PostMapping("/appmap-navie/install/check-npm")
    @org.springframework.web.bind.annotation.ResponseBody
    fun checkNpm(): Map<String, Any> {
        val cmdLine = "npm --version && node --version"
        val cmd = if (Platform.isWindows)
            listOf("cmd", "/c", cmdLine)
        else
            listOf("bash", "-lc", cmdLine)
        val log = StringBuilder()
        log.appendLine("$ $cmdLine")
        return runCatching {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText()
            if (out.isNotBlank()) {
                log.append(out)
                if (!out.endsWith("\n")) log.append("\n")
            }
            if (!finished) {
                proc.destroyForcibly()
                return mapOf("ok" to false, "message" to "Probe timed out.", "output" to log.toString())
            }
            if (proc.exitValue() == 0) {
                mapOf("ok" to true,
                    "message" to "npm + node are reachable. Safe to run the install.",
                    "output" to log.toString())
            } else {
                mapOf("ok" to false,
                    "message" to "npm/node not reachable. Install Node.js, then retry.",
                    "output" to log.toString())
            }
        }.getOrElse { e ->
            log.appendLine("[err]  ${e.javaClass.simpleName}: ${e.message}")
            mapOf("ok" to false,
                "message" to "Failed to spawn shell: ${e.message}",
                "output" to log.toString())
        }
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
