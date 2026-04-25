package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * LLM provider configuration + guided setup wizard.
 *
 * <p>Two providers are supported:
 * <ul>
 *   <li><b>GitHub Copilot (VSCode Bridge)</b> — the user installs a VSCode
 *       extension that publishes a local Unix socket; the harness talks to
 *       Copilot through that bridge. The wizard walks the user through
 *       extension install, bridge start, and a socket-health probe.</li>
 *   <li><b>Corporate OpenAI Gateway</b> — two-hop flow: first an OAuth2
 *       client-credentials exchange at the Apigee login URL to mint an
 *       access token, then REST calls to an OpenAI-compatible endpoint
 *       that expects the bearer token PLUS custom headers (tenant id,
 *       correlation id, etc.) required by the corporate gateway.</li>
 * </ul>
 *
 * <p>Secrets the operator pastes into the wizard never touch disk: they
 * live in {@link HttpSession} attributes and are consumed on demand by
 * the Test endpoints. The persisted <code>~/.ai-bench/corp-openai.yaml</code>
 * references env-var NAMES (matching the harness's
 * {@code CorpOpenAiConfig} schema) so the harness can re-read credentials
 * from a proper secret store.
 */
@Controller
class LlmConfigController(
    private val connectionSettings: ConnectionSettings,
    private val secretStore: SecretStore,
    private val priceCatalog: ModelPriceCatalog,
    private val registeredModelsRegistry: RegisteredModelsRegistry
) {

    private val log = LoggerFactory.getLogger(LlmConfigController::class.java)
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    data class ModelInfo(
        val id: String,
        val displayName: String,
        val provider: String,
        val modelIdentifier: String,
        val status: String,
        val costPer1kPrompt: Double = 0.0,
        val costPer1kCompletion: Double = 0.0,
        val editable: Boolean = true
    )

    data class ProviderStatus(
        val name: String,
        val displayName: String,
        val status: String,
        val detail: String
    )

    /** Session keys — isolated so individual steps can be cleared on reset. */
    private companion object {
        const val APIGEE_LOGIN_URL = "corpOpenAi.apigee.loginUrl"
        const val APIGEE_CLIENT_ID = "corpOpenAi.apigee.clientId"
        const val APIGEE_CLIENT_SECRET = "corpOpenAi.apigee.clientSecret"
        const val APIGEE_SCOPE = "corpOpenAi.apigee.scope"
        const val APIGEE_TOKEN = "corpOpenAi.apigee.token"
        const val APIGEE_TOKEN_EXPIRES = "corpOpenAi.apigee.tokenExpires"

        const val OAI_BASE_URL = "corpOpenAi.baseUrl"
        const val OAI_DEFAULT_MODEL = "corpOpenAi.defaultModel"
        const val OAI_CUSTOM_HEADERS = "corpOpenAi.customHeaders"
        const val OAI_TEST_RESULT = "corpOpenAi.testResult"

        const val CLIENT_ID_ENV_NAME = "corpOpenAi.clientIdEnvName"
        const val CLIENT_SECRET_ENV_NAME = "corpOpenAi.clientSecretEnvName"
        /** Where the client_secret actually lives: "memory" or "keystore". */
        const val SECRET_STORAGE = "corpOpenAi.secretStorage"
        /** Persistent account name used when storage=keystore. */
        const val KEYSTORE_ACCOUNT = "corpOpenAi.keystoreAccount"

        const val COPILOT_PROBE_RESULT = "copilot.probeResult"
        /** Session key holding the most recent Copilot model enumeration. */
        const val COPILOT_MODELS = "copilot.models"

        // AppMap Navie is intentionally absent from this page entirely —
        // it's a context provider, not an LLM, and lives on /appmap-navie.

        /** Capabilities probe result stored per session. */
        const val OAI_CAPABILITIES = "corpOpenAi.capabilities"

        /** Sentinel stored in session when the real secret lives in keystore. */
        const val KEYSTORE_SENTINEL = "__KEYSTORE__"

        /** Prefix written to session when a secret lives in an env var
         *  (typical for HashiCorp Vault Agent / OpenShift Vault
         *  injectors that write secrets into the process environment at
         *  launch time). The suffix after the colon is the env-var name. */
        const val VAULT_ENV_PREFIX = "__VAULT_ENV__:"

        /** List of model IDs returned by GET /models. */
        const val OAI_AVAILABLE_MODELS = "corpOpenAi.availableModels"
    }

    private fun getSessionModels(session: HttpSession): MutableList<ModelInfo> {
        @Suppress("UNCHECKED_CAST")
        return session.getAttribute("llmModels") as? MutableList<ModelInfo>
            ?: mutableListOf<ModelInfo>().also { session.setAttribute("llmModels", it) }
    }

    @GetMapping("/llm")
    fun config(model: Model, session: HttpSession): String {
        // --- Copilot bridge health ------------------------------------------
        val copilotSock = Platform.defaultCopilotSocket()
        val copilotHealthy = java.io.File(copilotSock).exists()
        val copilotExtId = System.getenv("AI_BENCH_COPILOT_EXT_ID") ?: "ai-bench.copilot-bridge"
        model.addAttribute("copilotSockPath", copilotSock)
        model.addAttribute("copilotBridgeHealthy", copilotHealthy)
        model.addAttribute("copilotExtId", copilotExtId)
        val vsix = locateCopilotVsix()
        model.addAttribute("copilotVsixPresent", vsix != null)
        model.addAttribute("copilotVsixPath", vsix?.displayPath ?: "")
        model.addAttribute("copilotVsixSize", vsix?.size ?: 0L)
        model.addAttribute("copilotProbeResult", session.getAttribute(COPILOT_PROBE_RESULT))
        session.removeAttribute(COPILOT_PROBE_RESULT)

        // --- Corporate OpenAI wizard state ---------------------------------
        val apigeeLoginUrl = session.getAttribute(APIGEE_LOGIN_URL) as? String ?: ""
        val apigeeClientIdPresent = !(session.getAttribute(APIGEE_CLIENT_ID) as? String).isNullOrBlank()
        val secretStorage = (session.getAttribute(SECRET_STORAGE) as? String) ?: "memory"
        val keystoreAccount = session.getAttribute(KEYSTORE_ACCOUNT) as? String
        val apigeeClientSecretPresent = resolveClientSecret(session) != null
        val apigeeScope = session.getAttribute(APIGEE_SCOPE) as? String ?: ""
        val apigeeToken = session.getAttribute(APIGEE_TOKEN) as? String
        val apigeeTokenExpires = session.getAttribute(APIGEE_TOKEN_EXPIRES) as? Long ?: 0L

        val oaiBaseUrl = session.getAttribute(OAI_BASE_URL) as? String ?: ""
        val oaiDefaultModel = session.getAttribute(OAI_DEFAULT_MODEL) as? String ?: "gpt-4o"
        val oaiCustomHeaders = session.getAttribute(OAI_CUSTOM_HEADERS) as? String ?: ""
        val oaiHeaderRows = parseHeaderSpecs(oaiCustomHeaders).map { s ->
            val (display, source) = when {
                s.valueOrSentinel == KEYSTORE_SENTINEL -> "" to "keystore"
                s.valueOrSentinel.startsWith(VAULT_ENV_PREFIX) ->
                    s.valueOrSentinel.removePrefix(VAULT_ENV_PREFIX) to "vault"
                else -> s.valueOrSentinel to "memory"
            }
            mapOf("name" to s.name, "value" to display,
                  "secret" to s.secret, "source" to source)
        }
        val oaiTestResult = session.getAttribute(OAI_TEST_RESULT) as? Map<*, *>
        session.removeAttribute(OAI_TEST_RESULT)

        val clientIdEnvName = session.getAttribute(CLIENT_ID_ENV_NAME) as? String ?: "CORP_LLM_CLIENT_ID"
        val clientSecretEnvName = session.getAttribute(CLIENT_SECRET_ENV_NAME) as? String ?: "CORP_LLM_CLIENT_SECRET"

        model.addAttribute("apigeeLoginUrl", apigeeLoginUrl)
        model.addAttribute("apigeeClientId", session.getAttribute(APIGEE_CLIENT_ID) as? String ?: "")
        model.addAttribute("apigeeClientIdPresent", apigeeClientIdPresent)
        model.addAttribute("apigeeClientSecretPresent", apigeeClientSecretPresent)
        model.addAttribute("apigeeScope", apigeeScope)
        model.addAttribute("apigeeTokenMasked", apigeeToken?.let { maskToken(it) } ?: "")
        model.addAttribute("apigeeTokenAlive",
            apigeeToken != null && apigeeTokenExpires > System.currentTimeMillis())
        model.addAttribute("apigeeExpiresInSec",
            if (apigeeTokenExpires == 0L) 0
            else ((apigeeTokenExpires - System.currentTimeMillis()) / 1000).coerceAtLeast(0))

        model.addAttribute("oaiBaseUrl", oaiBaseUrl)
        model.addAttribute("oaiDefaultModel", oaiDefaultModel)
        model.addAttribute("oaiCustomHeaders", oaiCustomHeaders)
        model.addAttribute("oaiHeaderRows", oaiHeaderRows)
        model.addAttribute("oaiTestResult", oaiTestResult)

        model.addAttribute("clientIdEnvName", clientIdEnvName)
        model.addAttribute("clientSecretEnvName", clientSecretEnvName)

        // Secret-storage attributes for the wizard UI.
        model.addAttribute("secretStorage", secretStorage)
        model.addAttribute("keystoreAvailable", secretStore.available())
        model.addAttribute("keystoreBackendName", secretStore.humanName())
        model.addAttribute("keystoreAccount", keystoreAccount ?: "")

        val corpConfigPath = System.getProperty("user.home") + "/.ai-bench/corp-openai.yaml"
        val corpConfigExists = java.io.File(corpConfigPath).exists()
        model.addAttribute("corpConfigPath", "~/.ai-bench/corp-openai.yaml")
        model.addAttribute("corpConfigExists", corpConfigExists)

        // Step completion booleans drive the wizard's progress indicator.
        val apigeeConfigured = apigeeLoginUrl.isNotBlank() && apigeeClientIdPresent && apigeeClientSecretPresent
        val apigeeVerified = apigeeToken != null
        val openAiConfigured = oaiBaseUrl.isNotBlank()
        val openAiVerified = (oaiTestResult?.get("success") as? Boolean) == true || corpConfigExists
        model.addAttribute("apigeeConfigured", apigeeConfigured)
        model.addAttribute("apigeeVerified", apigeeVerified)
        model.addAttribute("openAiConfigured", openAiConfigured)
        model.addAttribute("openAiVerified", openAiVerified)

        val allGreen = apigeeConfigured && apigeeVerified && openAiConfigured && openAiVerified
        model.addAttribute("corpWizardComplete", allGreen)

        // AppMap Navie is a context provider, not an LLM. It has its own
        // /appmap-navie page, and it is deliberately excluded from both
        // the Provider Status summary and the Registered Models list on
        // this page so operators don't confuse it with an LLM model.

        // --- Provider status (legacy summary) ------------------------------
        val corpCredentials = apigeeClientIdPresent && apigeeClientSecretPresent
        model.addAttribute("providers", listOf(
            ProviderStatus("copilot", "GitHub Copilot (VSCode Bridge)",
                if (copilotHealthy) "available" else "unavailable",
                if (copilotHealthy) "Bridge running at $copilotSock"
                else "Bridge not detected at $copilotSock. Follow the Copilot wizard below."),
            ProviderStatus("corp-openai", "Corporate OpenAI Gateway",
                if (allGreen) "available"
                else if (apigeeVerified && openAiConfigured) "partial"
                else "unavailable",
                when {
                    allGreen -> "Apigee + OpenAI endpoint both verified. Config persisted to ~/.ai-bench/corp-openai.yaml."
                    apigeeVerified -> "Apigee token obtained. Continue to step 3 (configure OpenAI endpoint)."
                    apigeeConfigured -> "Apigee settings captured. Run step 2 (test Apigee login)."
                    else -> "Walk through the Corporate OpenAI wizard below."
                })
        ))

        // Registered-Models table shows LLMs only. Context providers
        // (appmap-navie) have their own setup page and must not appear
        // here — they'd be misleading in a list of per-token-priced
        // models. The Quick Benchmark dropdown on /demo still reads the
        // unfiltered registry, so Navie remains selectable at run time.
        model.addAttribute("models",
            registeredModelsRegistry.availableModels(session)
                .filter { it.provider != "appmap-navie" })

        // Apply any per-session catalog overrides so the rendered prices
        // reflect what the operator typed in. Overrides keyed by catalog
        // id; values are Pair<promptCost, completionCost>.
        @Suppress("UNCHECKED_CAST")
        val overrides = (session.getAttribute("priceCatalogOverrides")
            as? Map<String, Pair<Double, Double?>>) ?: emptyMap()
        val catalogView = priceCatalog.catalog.map { c ->
            val o = overrides[c.id]
            mapOf(
                "id" to c.id,
                "displayName" to c.displayName,
                "provider" to c.provider,
                "modelIdentifier" to c.modelIdentifier,
                "modality" to c.modality,
                "costPer1kPrompt" to (o?.first ?: c.costPer1kPrompt),
                "costPer1kCompletion" to (o?.second ?: c.costPer1kCompletion),
                "notes" to c.notes,
                "overridden" to (o != null)
            )
        }
        model.addAttribute("priceCatalog", catalogView)
        model.addAttribute("priceCatalogLastUpdated", priceCatalog.lastUpdated)
        model.addAttribute("priceCatalogSources", priceCatalog.sources)
        @Suppress("UNCHECKED_CAST")
        val copilotModels = (session.getAttribute(COPILOT_MODELS) as? List<Map<String, Any?>>) ?: emptyList()
        model.addAttribute("copilotEnumeratedModels", copilotModels)
        // Models enumerated from the gateway — populated by Step 4 Test.
        @Suppress("UNCHECKED_CAST")
        val availableModels = session.getAttribute(OAI_AVAILABLE_MODELS) as? List<String> ?: emptyList()
        model.addAttribute("availableModels", availableModels)
        model.addAttribute("oaiCapabilities", session.getAttribute(OAI_CAPABILITIES))
        return "llm-config"
    }

    /**
     * Returns the public-model pricing catalog as JSON. The Add-Model
     * form calls this to prefill cost fields when the operator picks a
     * known model from the datalist.
     */
    @GetMapping("/llm/catalog")
    @ResponseBody
    fun catalog(): Map<String, Any> = mapOf(
        "lastUpdated" to priceCatalog.lastUpdated,
        "sources" to priceCatalog.sources.map { mapOf("name" to it.first, "url" to it.second) },
        "models" to priceCatalog.catalog
    )

    /** Persist a per-session price override for a single catalog entry. */
    @PostMapping("/llm/catalog/{id}/override")
    fun overrideCatalogPrice(
        @PathVariable id: String,
        @RequestParam costPer1kPrompt: Double,
        @RequestParam(required = false) costPer1kCompletion: Double?,
        session: HttpSession
    ): String {
        @Suppress("UNCHECKED_CAST")
        val current = (session.getAttribute("priceCatalogOverrides")
            as? MutableMap<String, Pair<Double, Double?>>) ?: mutableMapOf()
        current[id] = costPer1kPrompt to costPer1kCompletion
        session.setAttribute("priceCatalogOverrides", current)
        return "redirect:/llm#public-model-catalog"
    }

    /** Drop a single override (revert to published price). */
    @PostMapping("/llm/catalog/{id}/override/clear")
    fun clearCatalogOverride(@PathVariable id: String, session: HttpSession): String {
        @Suppress("UNCHECKED_CAST")
        val current = (session.getAttribute("priceCatalogOverrides")
            as? MutableMap<String, Pair<Double, Double?>>)
        current?.remove(id)
        return "redirect:/llm#public-model-catalog"
    }

    // ======================================================================
    //  COPILOT WIZARD
    // ======================================================================

    /**
     * Locate the bundled Copilot bridge VSIX. Search order:
     * <ol>
     *   <li>{@code AI_BENCH_COPILOT_VSIX} env var (explicit override)</li>
     *   <li>Repo-local build output
     *       {@code ../tools/copilot-bridge-extension/copilot-bridge.vsix}</li>
     *   <li>{@code static/dist/copilot-bridge.vsix} on the classpath
     *       (published into the jar if the distribution pre-packages
     *       the VSIX)</li>
     * </ol>
     * Returns null if no built artifact is present; the UI surfaces a
     * "not built yet — run <code>npm run package</code>" hint in that
     * case.
     */
    /**
     * Resolved location of the bundled Copilot bridge VSIX. Abstracts over
     * three possible sources so callers don't care whether it lives on disk
     * or inside the Spring Boot fat jar:
     *   - explicit env-var path (AI_BENCH_COPILOT_VSIX)
     *   - sibling-checkout layout used in dev (../tools/copilot-bridge-extension/...)
     *   - classpath resource bundled at static/dist/copilot-bridge.vsix
     */
    private data class CopilotVsixSource(
        /** Filesystem path or "classpath:..." -- shown in the UI for diagnostics. */
        val displayPath: String,
        /** Bytes the resource will stream. -1 if unknown. */
        val size: Long,
        /** Filename used in Content-Disposition. */
        val name: String,
        /** Caller-provided stream factory. Each call returns a fresh InputStream. */
        val openStream: () -> java.io.InputStream
    )

    private fun locateCopilotVsix(): CopilotVsixSource? {
        System.getenv("AI_BENCH_COPILOT_VSIX")?.takeIf { it.isNotBlank() }?.let { path ->
            val f = java.io.File(path)
            if (f.isFile) return CopilotVsixSource(f.absolutePath, f.length(), f.name) { f.inputStream() }
        }
        val repoLocal = java.io.File(
            System.getProperty("user.dir"),
            "../tools/copilot-bridge-extension/copilot-bridge.vsix"
        ).canonicalFile
        if (repoLocal.isFile) {
            return CopilotVsixSource(repoLocal.absolutePath, repoLocal.length(), repoLocal.name) { repoLocal.inputStream() }
        }
        // Spring Boot fat-jar fallback. ClassLoader.getResource returns a
        // jar:file:.../bench-webui.jar!/static/dist/copilot-bridge.vsix URL
        // which is NOT convertible to java.io.File ("URI is not hierarchical"
        // — caused a 500 on /llm in earlier code). Use Spring's
        // ClassPathResource so we can report contentLength() and stream
        // bytes through the classloader instead.
        val resource = ClassPathResource("static/dist/copilot-bridge.vsix")
        if (resource.exists()) {
            val size = runCatching { resource.contentLength() }.getOrDefault(0L)
            return CopilotVsixSource(
                displayPath = "classpath:static/dist/copilot-bridge.vsix",
                size        = if (size > 0) size else 0L,
                name        = "copilot-bridge.vsix"
            ) { resource.inputStream }
        }
        return null
    }

    /**
     * Stream the bundled Copilot bridge VSIX to the browser. Returns
     * 404 with a guidance message when the artifact hasn't been built
     * yet so the wizard UI can show the "build it first" hint.
     */
    @GetMapping("/llm/copilot/download-bridge")
    fun downloadBridge(response: jakarta.servlet.http.HttpServletResponse) {
        val vsix = locateCopilotVsix()
        if (vsix == null) {
            response.status = 404
            response.contentType = "text/plain; charset=utf-8"
            response.writer.use {
                it.println("Copilot bridge VSIX not found.")
                it.println()
                it.println("Build it from the repo:")
                it.println("  cd tools/copilot-bridge-extension")
                it.println("  npm install && npm run package")
                it.println()
                it.println("The script writes copilot-bridge.vsix next to package.json, and")
                it.println("this endpoint will serve it automatically on the next request.")
            }
            return
        }
        response.contentType = "application/octet-stream"
        response.setHeader("Content-Disposition",
            "attachment; filename=\"${vsix.name}\"")
        if (vsix.size > 0) response.setContentLengthLong(vsix.size)
        vsix.openStream().use { it.copyTo(response.outputStream) }
    }

    /**
     * Dial the Copilot bridge's Unix socket and enumerate every model
     * GitHub Copilot is publishing to the current VSCode session. The
     * bridge replies with an {@code auto} field holding the model id
     * that the synthetic {@code "auto"} selector resolves to (first
     * model in the list by VSCode's own order, matching Copilot's
     * "let GitHub pick" UX).
     */
    @PostMapping("/llm/copilot/list-models")
    fun copilotListModels(session: HttpSession): String {
        val sock = Platform.defaultCopilotSocket()
        val f = java.io.File(sock)
        val result = when {
            !f.exists() -> mapOf("success" to false,
                "message" to "No socket at $sock. Install + start the Copilot bridge first (Steps 1–3 above).")
            else -> runCatching {
                val addr = java.net.UnixDomainSocketAddress.of(sock)
                java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { ch ->
                    ch.connect(addr)
                    val request = "{\"op\":\"list-models\"}\n"
                    ch.write(java.nio.ByteBuffer.wrap(request.toByteArray(Charsets.UTF_8)))
                    val buf = java.nio.ByteBuffer.allocate(32 * 1024)
                    val sb = StringBuilder()
                    // Expect a single \n-terminated JSON line from the bridge.
                    val deadline = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < deadline) {
                        buf.clear()
                        val n = ch.read(buf)
                        if (n <= 0) break
                        sb.append(String(buf.array(), 0, n, Charsets.UTF_8))
                        if (sb.contains('\n')) break
                    }
                    val line = sb.toString().substringBefore('\n').ifBlank {
                        return@runCatching mapOf("success" to false,
                            "message" to "Bridge did not respond within 10s — try restarting the VSCode extension.")
                    }
                    @Suppress("UNCHECKED_CAST")
                    val parsed = mapper.readValue(line, Map::class.java) as Map<String, Any?>
                    if (parsed["ok"] != true) {
                        mapOf("success" to false,
                              "message" to "Bridge reported error: ${parsed["error"] ?: "(no detail)"}")
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val rawModels = (parsed["models"] as? List<Map<String, Any?>>) ?: emptyList()
                        val models = rawModels.map { m ->
                            mapOf(
                                "id" to (m["id"] as? String ?: ""),
                                "name" to (m["name"] as? String ?: m["id"] as? String ?: "?"),
                                "vendor" to (m["vendor"] as? String ?: "copilot"),
                                "family" to (m["family"] as? String ?: ""),
                                "version" to (m["version"] as? String ?: ""),
                                "maxInputTokens" to (m["maxInputTokens"] as? Number ?: 0),
                                "auto" to (m["auto"] as? Boolean ?: false)
                            )
                        }
                        val autoId = parsed["auto"] as? String
                        session.setAttribute(COPILOT_MODELS, models)
                        mapOf(
                            "success" to true,
                            "message" to "Enumerated ${models.size} Copilot model(s)" +
                                (autoId?.let { "; 'auto' resolves to $it" } ?: "") + ".",
                            "models" to models,
                            "auto" to autoId
                        )
                    }
                }
            }.getOrElse {
                mapOf("success" to false,
                      "message" to "Bridge call failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        session.setAttribute(COPILOT_PROBE_RESULT, result)
        return "redirect:/llm#copilot"
    }

    /**
     * Register a single enumerated Copilot model into the session's
     * registered-models list. Idempotent — if a model with the same
     * id already exists, the call is a no-op (no duplicate row, no
     * cost-override clobber).
     *
     * <p>Costs land at $0 because Copilot is seat-priced rather than
     * per-token; the operator can revise later via the catalog
     * override flow if their org allocates internal chargeback per
     * call.
     */
    @PostMapping("/llm/copilot/register-model")
    fun registerCopilotModel(
        @RequestParam modelId: String,
        @RequestParam(required = false) displayName: String?,
        session: HttpSession
    ): String {
        val cleanId = "copilot-" + modelId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
        val cleanName = (displayName?.trim()?.ifBlank { null }
            ?: "Copilot — ${modelId.trim()}")
        val models = getSessionModels(session)
        if (models.none { it.id == cleanId }) {
            models.add(ModelInfo(
                id = cleanId,
                displayName = cleanName,
                provider = "copilot",
                modelIdentifier = modelId.trim(),
                status = "available",
                costPer1kPrompt = 0.0,
                costPer1kCompletion = 0.0
            ))
        }
        return "redirect:/llm#copilot"
    }

    /**
     * Bulk-register every Copilot model the bridge enumerated. Skips
     * any that are already registered (matched by the same generated
     * id). Convenient one-click sync after listing models.
     */
    @PostMapping("/llm/copilot/register-all")
    fun registerAllCopilotModels(session: HttpSession): String {
        @Suppress("UNCHECKED_CAST")
        val enumerated = (session.getAttribute(COPILOT_MODELS) as? List<Map<String, Any?>>)
            ?: return "redirect:/llm#copilot"
        val models = getSessionModels(session)
        val existingIds = models.map { it.id }.toMutableSet()
        // Also register a synthetic "auto" row so callers that just
        // want "whatever Copilot picks first" have an entry.
        val rows = mutableListOf<Pair<String, String>>()
        rows.add("auto" to "Copilot (auto-routed)")
        for (m in enumerated) {
            val id = m["id"] as? String ?: continue
            val name = m["name"] as? String ?: id
            rows.add(id to name)
        }
        for ((rawId, name) in rows) {
            val cleanId = "copilot-" + rawId.replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
            if (existingIds.add(cleanId)) {
                models.add(ModelInfo(
                    id = cleanId,
                    displayName = name,
                    provider = "copilot",
                    modelIdentifier = rawId,
                    status = "available",
                    costPer1kPrompt = 0.0,
                    costPer1kCompletion = 0.0
                ))
            }
        }
        return "redirect:/llm#copilot"
    }

    @PostMapping("/llm/copilot/probe")
    fun copilotProbe(session: HttpSession): String {
        val sock = Platform.defaultCopilotSocket()
        val f = java.io.File(sock)
        val result = when {
            !f.exists() -> mapOf("success" to false,
                "message" to "No socket at $sock. Install the VSCode extension and start the bridge (Step 1 + 2 below).")
            !f.canRead() -> mapOf("success" to false,
                "message" to "Socket exists at $sock but is not readable. Check permissions.")
            else -> mapOf("success" to true,
                "message" to "Bridge socket is live at $sock.")
        }
        session.setAttribute(COPILOT_PROBE_RESULT, result)
        return "redirect:/llm#copilot"
    }

    // ======================================================================
    //  CORPORATE OPENAI WIZARD — Step 1: Apigee credentials
    // ======================================================================

    @PostMapping("/llm/corp-openai/apigee/save")
    fun saveApigee(
        @RequestParam loginUrl: String,
        @RequestParam clientId: String,
        @RequestParam(required = false) clientSecret: String?,
        @RequestParam(required = false, defaultValue = "") scope: String,
        @RequestParam(required = false, defaultValue = "CORP_LLM_CLIENT_ID") clientIdEnvName: String,
        @RequestParam(required = false, defaultValue = "CORP_LLM_CLIENT_SECRET") clientSecretEnvName: String,
        @RequestParam(required = false, defaultValue = "memory") secretStorage: String,
        session: HttpSession
    ): String {
        val cleanClientId = clientId.trim()
        session.setAttribute(APIGEE_LOGIN_URL, loginUrl.trim())
        session.setAttribute(APIGEE_CLIENT_ID, cleanClientId)
        session.setAttribute(APIGEE_SCOPE, scope.trim())
        session.setAttribute(CLIENT_ID_ENV_NAME, clientIdEnvName.trim().ifBlank { "CORP_LLM_CLIENT_ID" })
        session.setAttribute(CLIENT_SECRET_ENV_NAME, clientSecretEnvName.trim().ifBlank { "CORP_LLM_CLIENT_SECRET" })
        // Normalize the storage choice — fall back to memory if the
        // requested backend isn't usable on this host.
        val effectiveStorage = when {
            secretStorage == "keystore" && secretStore.available() -> "keystore"
            secretStorage == "vault" -> "vault"
            else -> "memory"
        }
        session.setAttribute(SECRET_STORAGE, effectiveStorage)

        if (!clientSecret.isNullOrBlank()) {
            // Leave the existing secret in place if the user submitted a
            // blank (edit-without-re-pasting ergonomics — password field).
            val account = "apigee:$cleanClientId"
            session.setAttribute(KEYSTORE_ACCOUNT, account)
            when (effectiveStorage) {
                "keystore" -> {
                    val stored = secretStore.put(account, clientSecret)
                    if (stored) {
                        // Session holds only the placeholder so the raw
                        // secret never sits in Tomcat's session store.
                        session.setAttribute(APIGEE_CLIENT_SECRET, KEYSTORE_SENTINEL)
                    } else {
                        // Fall back gracefully.
                        session.setAttribute(APIGEE_CLIENT_SECRET, clientSecret)
                        session.setAttribute(SECRET_STORAGE, "memory")
                    }
                }
                "vault" -> {
                    // In vault mode the "clientSecret" text box is a
                    // reference, not a secret — the user types the env
                    // var NAME that Vault Agent / the OpenShift injector
                    // will populate at launch time. Mirror that into the
                    // YAML-persisted clientSecretEnvName so the harness
                    // reads from the same env var at run time.
                    val envName = clientSecret.trim()
                    session.setAttribute(APIGEE_CLIENT_SECRET, VAULT_ENV_PREFIX + envName)
                    session.setAttribute(CLIENT_SECRET_ENV_NAME, envName)
                }
                else -> session.setAttribute(APIGEE_CLIENT_SECRET, clientSecret)
            }
        }
        // Invalidate any prior token since creds may have changed.
        session.removeAttribute(APIGEE_TOKEN)
        session.removeAttribute(APIGEE_TOKEN_EXPIRES)
        return "redirect:/llm#corp-openai"
    }

    // ======================================================================
    //  CORPORATE OPENAI WIZARD — Step 2: Test Apigee token exchange
    // ======================================================================

    @PostMapping("/llm/corp-openai/apigee/test")
    fun testApigee(session: HttpSession): String {
        val loginUrl = session.getAttribute(APIGEE_LOGIN_URL) as? String
        val clientId = session.getAttribute(APIGEE_CLIENT_ID) as? String
        val clientSecret = resolveClientSecret(session)
        val scope = session.getAttribute(APIGEE_SCOPE) as? String ?: ""
        if (loginUrl.isNullOrBlank() || clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            session.setAttribute(OAI_TEST_RESULT, mapOf("success" to false,
                "step" to "apigee",
                "message" to "Complete Step 1: save loginUrl, clientId, and clientSecret first."))
            return "redirect:/llm#corp-openai"
        }

        val result = try {
            val formBody = buildString {
                append("grant_type=client_credentials")
                append("&client_id=").append(urlEncode(clientId))
                append("&client_secret=").append(urlEncode(clientSecret))
                if (scope.isNotBlank()) append("&scope=").append(urlEncode(scope))
            }
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(loginUrl))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(formBody))
                .build()
            val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(15))
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) {
                val body = resp.body()
                val accessToken = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                val expiresIn = Regex("\"expires_in\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 1800L
                if (accessToken != null) {
                    session.setAttribute(APIGEE_TOKEN, accessToken)
                    session.setAttribute(APIGEE_TOKEN_EXPIRES, System.currentTimeMillis() + expiresIn * 1000L)
                    mapOf("success" to true, "step" to "apigee",
                        "message" to "Apigee token obtained (${expiresIn}s TTL). Preview: ${maskToken(accessToken)}")
                } else {
                    mapOf("success" to false, "step" to "apigee",
                        "message" to "Apigee responded HTTP ${resp.statusCode()} but no access_token field: ${body.take(200)}")
                }
            } else {
                mapOf("success" to false, "step" to "apigee",
                    "message" to "Apigee returned HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
            }
        } catch (e: Exception) {
            log.warn("Apigee token exchange failed", e)
            mapOf("success" to false, "step" to "apigee",
                "message" to "Apigee call failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        session.setAttribute(OAI_TEST_RESULT, result)
        return "redirect:/llm#corp-openai"
    }

    // ======================================================================
    //  CORPORATE OPENAI WIZARD — Step 3: Configure OpenAI endpoint
    // ======================================================================

    /**
     * Structured-row header editor. Every row in the Step 3 editor
     * submits three parallel values:
     * <ul>
     *   <li>{@code headerName} — header name</li>
     *   <li>{@code headerValue} — cleartext value OR a sentinel
     *       placeholder when the row previously had a persisted secret
     *       that the operator didn't re-paste</li>
     *   <li>{@code headerSecret} — "true" or "false" per row; mirrored
     *       from the row's checkbox by JS so the array index aligns
     *       with the name/value arrays</li>
     * </ul>
     * Blank rows are dropped. Secret rows where the value is blank or
     * the existing sentinel/vault-ref are preserved unchanged (so the
     * user can re-save Step 3 without re-typing the secret).
     */
    @PostMapping("/llm/corp-openai/openai/save")
    fun saveOpenAi(
        @RequestParam baseUrl: String,
        @RequestParam(required = false, defaultValue = "gpt-4o") defaultModel: String,
        @RequestParam(required = false) headerName: List<String>?,
        @RequestParam(required = false) headerValue: List<String>?,
        @RequestParam(required = false) headerSecret: List<String>?,
        session: HttpSession
    ): String {
        session.setAttribute(OAI_BASE_URL, baseUrl.trim().trimEnd('/'))
        session.setAttribute(OAI_DEFAULT_MODEL, defaultModel.trim().ifBlank { "gpt-4o" })

        val storage = (session.getAttribute(SECRET_STORAGE) as? String) ?: "memory"
        val names = headerName ?: emptyList()
        val values = headerValue ?: emptyList()
        val secrets = headerSecret ?: emptyList()

        // Map prior on-disk values by header name so an unchanged secret
        // row preserves its existing sentinel / vault-ref.
        val priorByName = parseHeaderSpecs(session.getAttribute(OAI_CUSTOM_HEADERS) as? String ?: "")
            .associateBy { it.name }

        val rebuiltSpecs = (0 until names.size).mapNotNull { i ->
            val rawName = names[i].trim()
            if (rawName.isBlank()) return@mapNotNull null
            val rawValue = values.getOrNull(i)?.trim() ?: ""
            val isSecret = secrets.getOrNull(i)?.equals("true", ignoreCase = true) == true

            if (!isSecret) {
                return@mapNotNull HeaderSpec(rawName, rawValue, secret = false)
            }

            // Secret row — figure out where to put the value.
            val prior = priorByName[rawName]
            val storedValue = when {
                rawValue.isBlank() -> prior?.valueOrSentinel ?: ""    // no change
                rawValue == KEYSTORE_SENTINEL -> rawValue              // already opaque
                rawValue.startsWith(VAULT_ENV_PREFIX) -> rawValue      // already opaque
                storage == "keystore" && secretStore.available() -> {
                    val account = "header:$rawName"
                    if (secretStore.put(account, rawValue)) KEYSTORE_SENTINEL
                    else rawValue   // keystore failed → in-session cleartext
                }
                storage == "vault" -> VAULT_ENV_PREFIX + rawValue
                else -> rawValue    // memory mode
            }
            HeaderSpec(rawName, storedValue, secret = true)
        }

        val rebuilt = rebuiltSpecs.joinToString("\n") { s ->
            (if (s.secret) "!" else "") + s.name + ": " + s.valueOrSentinel
        }
        session.setAttribute(OAI_CUSTOM_HEADERS, rebuilt)
        return "redirect:/llm#corp-openai"
    }

    /**
     * Returns the parsed header list for the template renderer so the
     * row editor can show name / value / secret per row.
     */
    @GetMapping("/llm/corp-openai/openai/headers")
    @ResponseBody
    fun listHeaders(session: HttpSession): List<Map<String, Any?>> {
        val specs = parseHeaderSpecs(session.getAttribute(OAI_CUSTOM_HEADERS) as? String ?: "")
        return specs.map { s ->
            val (display, source) = when {
                s.valueOrSentinel == KEYSTORE_SENTINEL ->
                    "" to "keystore"
                s.valueOrSentinel.startsWith(VAULT_ENV_PREFIX) ->
                    s.valueOrSentinel.removePrefix(VAULT_ENV_PREFIX) to "vault"
                else -> s.valueOrSentinel to "memory"
            }
            mapOf(
                "name" to s.name,
                "value" to display,
                "secret" to s.secret,
                "source" to source
            )
        }
    }

    // ======================================================================
    //  CORPORATE OPENAI WIZARD — Step 4: Test OpenAI endpoint + persist YAML
    // ======================================================================

    @PostMapping("/llm/corp-openai/openai/test")
    fun testOpenAi(session: HttpSession): String {
        val token = session.getAttribute(APIGEE_TOKEN) as? String
        val tokenExpires = session.getAttribute(APIGEE_TOKEN_EXPIRES) as? Long ?: 0L
        val baseUrl = session.getAttribute(OAI_BASE_URL) as? String

        if (token.isNullOrBlank() || tokenExpires < System.currentTimeMillis()) {
            session.setAttribute(OAI_TEST_RESULT, mapOf("success" to false, "step" to "openai",
                "message" to "Apigee token is missing or expired. Re-run Step 2 (Test Apigee login) first."))
            return "redirect:/llm#corp-openai"
        }
        if (baseUrl.isNullOrBlank()) {
            session.setAttribute(OAI_TEST_RESULT, mapOf("success" to false, "step" to "openai",
                "message" to "Complete Step 3: save the OpenAI gateway base URL first."))
            return "redirect:/llm#corp-openai"
        }

        val headerSpecs = parseHeaderSpecs(session.getAttribute(OAI_CUSTOM_HEADERS) as? String ?: "")
        val resolvedHeaders = resolveHeaders(headerSpecs)
        val unresolvedSecrets = resolvedHeaders.filter { it.second == null }.map { it.first }

        val result = try {
            val builder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("$baseUrl/models"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
            resolvedHeaders.forEach { (k, v) -> if (v != null) builder.header(k, v) }
            val req = builder.GET().build()
            val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(15))
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) {
                val body = resp.body()
                val modelIds = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                    .findAll(body).map { it.groupValues[1] }.distinct().toList()
                session.setAttribute(OAI_AVAILABLE_MODELS, modelIds)

                // All four wizard steps passed — persist the YAML so the
                // harness picks up the same gateway on its next run.
                val yamlWritten = runCatching { writeCorpOpenAiYaml(session) }.getOrElse {
                    log.warn("Failed to write corp-openai.yaml", it)
                    null
                }
                val warnings = buildList {
                    if (unresolvedSecrets.isNotEmpty())
                        add("could not resolve secret header(s): ${unresolvedSecrets.joinToString(", ")}")
                }.joinToString(". ").let { if (it.isNotEmpty()) "  Warning: $it." else "" }
                mapOf("success" to true, "step" to "openai",
                    "message" to "Gateway reachable at $baseUrl/models (HTTP ${resp.statusCode()}" +
                        ", ${modelIds.size} model(s) enumerated" +
                        (if (resolvedHeaders.isNotEmpty()) ", ${resolvedHeaders.size} custom header(s) sent" else "") +
                        "). " +
                        (yamlWritten?.let { "Config written to $it." } ?: "YAML write failed — check logs.") +
                        warnings)
            } else {
                mapOf("success" to false, "step" to "openai",
                    "message" to "Gateway returned HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
            }
        } catch (e: Exception) {
            log.warn("OpenAI gateway test failed", e)
            mapOf("success" to false, "step" to "openai",
                "message" to "Gateway call failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        session.setAttribute(OAI_TEST_RESULT, result)
        return "redirect:/llm#corp-openai"
    }

    // ======================================================================
    //  CORPORATE OPENAI WIZARD — Step 5: Probe OpenAI API capabilities
    // ======================================================================

    /**
     * Send lightweight probe requests against each canonical OpenAI API
     * endpoint so the operator sees at a glance which surfaces the
     * corporate gateway actually implements. Each probe records one of
     * four outcomes: {@code supported}, {@code unsupported} (404/501),
     * {@code forbidden} (401/403 — typically the scope doesn't grant
     * access), or {@code error} (connection refused, 5xx, etc.).
     *
     * <p>Probes use minimal-cost payloads where possible: the chat and
     * embeddings probes request a single token / single input string.
     * All probes time out at 10 seconds to avoid hanging the UI if the
     * gateway has unusual behavior.
     */
    @PostMapping("/llm/corp-openai/probe-capabilities")
    fun probeCapabilities(session: HttpSession): String {
        val token = session.getAttribute(APIGEE_TOKEN) as? String
        val tokenExpires = session.getAttribute(APIGEE_TOKEN_EXPIRES) as? Long ?: 0L
        val baseUrl = session.getAttribute(OAI_BASE_URL) as? String
        if (token.isNullOrBlank() || tokenExpires < System.currentTimeMillis() || baseUrl.isNullOrBlank()) {
            session.setAttribute(OAI_CAPABILITIES, mapOf(
                "ok" to false,
                "message" to "Complete steps 1–4 first, and re-run Step 2 if the Apigee token has expired.",
                "results" to emptyList<Any>()))
            return "redirect:/llm#corp-openai"
        }
        val defaultModel = session.getAttribute(OAI_DEFAULT_MODEL) as? String ?: "gpt-4o"
        val headerSpecs = parseHeaderSpecs(session.getAttribute(OAI_CUSTOM_HEADERS) as? String ?: "")
        val resolved = resolveHeaders(headerSpecs).mapNotNull { (k, v) -> v?.let { k to it } }

        data class Probe(val label: String, val method: String, val path: String,
                         val body: String? = null, val spec: String)

        val probes = listOf(
            Probe("List models",               "GET",  "/models",               spec = "Standard"),
            Probe("Chat completions",          "POST", "/chat/completions",
                  body = """{"model":"$defaultModel","messages":[{"role":"user","content":"ping"}],"max_tokens":1}""",
                  spec = "Standard"),
            Probe("Legacy completions",        "POST", "/completions",
                  body = """{"model":"$defaultModel","prompt":"ping","max_tokens":1}""",
                  spec = "Deprecated / may be removed"),
            Probe("Embeddings",                "POST", "/embeddings",
                  body = """{"model":"text-embedding-3-small","input":"ping"}""",
                  spec = "Standard"),
            Probe("Moderations",               "POST", "/moderations",
                  body = """{"input":"ping"}""", spec = "Standard"),
            Probe("Image generation",          "POST", "/images/generations",
                  body = """{"prompt":"ping","n":1,"size":"256x256"}""",
                  spec = "Standard (often disabled in corp gateways)"),
            Probe("Files list",                "GET",  "/files",               spec = "Standard"),
            Probe("Fine-tuning jobs list",     "GET",  "/fine_tuning/jobs",    spec = "Standard"),
            Probe("Assistants (beta)",         "GET",  "/assistants",          spec = "Beta surface"),
            Probe("Audio speech",              "POST", "/audio/speech",
                  body = """{"model":"tts-1","input":"ping","voice":"alloy"}""",
                  spec = "Standard (rarely in corp gateways)")
        )

        val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(10))
        val results = probes.map { probe ->
            val bldr = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("$baseUrl${probe.path}"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
            resolved.forEach { (k, v) -> bldr.header(k, v) }
            val req = when (probe.method) {
                "GET" -> bldr.GET().build()
                "POST" -> bldr.header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(probe.body ?: "{}")).build()
                else -> bldr.GET().build()
            }
            val outcome = try {
                val r = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                val hdrStr = r.headers().map().entries.joinToString(", ") { "${it.key}=${it.value.firstOrNull() ?: ""}" }
                val customHints = detectCustomizationHints(r.headers().map(), r.body())
                val verdict = when {
                    r.statusCode() in 200..299 -> "supported"
                    r.statusCode() == 401 || r.statusCode() == 403 -> "forbidden"
                    r.statusCode() == 404 || r.statusCode() == 405 || r.statusCode() == 501 -> "unsupported"
                    r.statusCode() in 400..499 -> "supported" // e.g. 400 "model not found" still proves endpoint exists
                    else -> "error"
                }
                mapOf(
                    "label" to probe.label, "method" to probe.method, "path" to probe.path,
                    "spec" to probe.spec,
                    "status" to r.statusCode(),
                    "verdict" to verdict,
                    "customization" to customHints,
                    "snippet" to r.body().take(140)
                )
            } catch (e: Exception) {
                mapOf(
                    "label" to probe.label, "method" to probe.method, "path" to probe.path,
                    "spec" to probe.spec,
                    "status" to 0,
                    "verdict" to "error",
                    "customization" to emptyList<String>(),
                    "snippet" to (e.javaClass.simpleName + ": " + (e.message ?: ""))
                )
            }
            outcome
        }

        val supported = results.count { it["verdict"] == "supported" }
        val total = results.size
        session.setAttribute(OAI_CAPABILITIES, mapOf(
            "ok" to true,
            "message" to "Probed $total endpoints: $supported supported, " +
                "${results.count { it["verdict"] == "unsupported" }} missing, " +
                "${results.count { it["verdict"] == "forbidden" }} forbidden, " +
                "${results.count { it["verdict"] == "error" }} error.",
            "results" to results
        ))
        return "redirect:/llm#corp-openai"
    }

    /**
     * Look for well-known signals that the gateway has customized the
     * response envelope — extra metadata fields, different error shapes,
     * corporate correlation IDs, etc. Strictly heuristic; results land in
     * the capabilities card as hints for the operator.
     */
    private fun detectCustomizationHints(headers: Map<String, List<String>>, body: String): List<String> {
        val hints = mutableListOf<String>()
        headers.keys.forEach { k ->
            val lower = k.lowercase()
            if (lower.startsWith("x-") && !lower.startsWith("x-ratelimit")
                && !lower.startsWith("x-request-id") && !lower.startsWith("x-openai"))
                hints += "custom response header: $k"
        }
        if (body.contains("\"corp_metadata\"") || body.contains("\"tenant_id\"")
            || body.contains("\"correlation_id\"")) {
            hints += "response body includes corporate metadata fields"
        }
        if (body.contains("\"code\"") && body.contains("\"type\"") && !body.contains("\"error\"")) {
            hints += "non-standard response envelope"
        }
        return hints
    }

    @PostMapping("/llm/corp-openai/reset")
    fun resetCorpOpenAi(session: HttpSession): String {
        listOf(APIGEE_LOGIN_URL, APIGEE_CLIENT_ID, APIGEE_CLIENT_SECRET, APIGEE_SCOPE,
               APIGEE_TOKEN, APIGEE_TOKEN_EXPIRES, OAI_BASE_URL, OAI_DEFAULT_MODEL,
               OAI_CUSTOM_HEADERS, OAI_TEST_RESULT, CLIENT_ID_ENV_NAME, CLIENT_SECRET_ENV_NAME)
            .forEach { session.removeAttribute(it) }
        return "redirect:/llm#corp-openai"
    }

    // AppMap Navie wizard has moved to /appmap-navie (see
    // AppmapNavieController). Only the Provider Status summary row
    // remains on this page.

    // ======================================================================
    //  Legacy model CRUD (kept for manual additions)
    // ======================================================================

    @PostMapping("/llm/add-model")
    fun addModel(
        @RequestParam id: String,
        @RequestParam displayName: String,
        @RequestParam provider: String,
        @RequestParam modelIdentifier: String,
        @RequestParam(defaultValue = "0.0") costPer1kPrompt: Double,
        @RequestParam(defaultValue = "0.0") costPer1kCompletion: Double,
        session: HttpSession
    ): String {
        val models = getSessionModels(session)
        models.add(ModelInfo(id, displayName, provider, modelIdentifier, "configured", costPer1kPrompt, costPer1kCompletion))
        return "redirect:/llm"
    }

    @PostMapping("/llm/models/{modelId}/update")
    fun updateModel(
        @PathVariable modelId: String,
        @RequestParam displayName: String,
        @RequestParam costPer1kPrompt: Double,
        @RequestParam costPer1kCompletion: Double,
        session: HttpSession
    ): String {
        val models = getSessionModels(session)
        val idx = models.indexOfFirst { it.id == modelId }
        if (idx >= 0) {
            models[idx] = models[idx].copy(displayName = displayName, costPer1kPrompt = costPer1kPrompt, costPer1kCompletion = costPer1kCompletion)
        }
        return "redirect:/llm"
    }

    @PostMapping("/llm/models/{modelId}/delete")
    fun deleteModel(@PathVariable modelId: String, session: HttpSession): String {
        getSessionModels(session).removeIf { it.id == modelId }
        return "redirect:/llm"
    }

    @GetMapping("/llm/providers/{provider}/enumerate")
    @ResponseBody
    fun enumerateModels(@PathVariable provider: String): Map<String, Any> = when (provider) {
        "copilot" -> mapOf(
            "provider" to "copilot",
            "note" to "Copilot model selection is managed by GitHub. The bridge forwards to whichever model your Copilot entitlement provides.",
            "models" to listOf(mapOf("id" to "copilot", "name" to "Copilot (entitlement default)"))
        )
        "corp-openai" -> mapOf(
            "provider" to "corp-openai",
            "note" to "Models available through your corporate OpenAI gateway. Add models manually with identifiers from your API catalog.",
            "models" to listOf(
                mapOf("id" to "gpt-4o", "name" to "GPT-4o"),
                mapOf("id" to "gpt-4o-mini", "name" to "GPT-4o Mini"),
                mapOf("id" to "gpt-4-turbo", "name" to "GPT-4 Turbo"),
                mapOf("id" to "o1-preview", "name" to "o1 Preview"),
                mapOf("id" to "o1-mini", "name" to "o1 Mini")
            )
        )
        else -> mapOf("error" to "Unknown provider: $provider")
    }

    // ======================================================================
    //  Helpers
    // ======================================================================

    /**
     * Return the cleartext client secret, reading from keystore if the
     * session only holds the sentinel, or null if we don't have one.
     */
    private fun resolveClientSecret(session: HttpSession): String? {
        val stored = session.getAttribute(APIGEE_CLIENT_SECRET) as? String ?: return null
        return when {
            stored == KEYSTORE_SENTINEL -> {
                val account = session.getAttribute(KEYSTORE_ACCOUNT) as? String ?: return null
                secretStore.get(account)
            }
            stored.startsWith(VAULT_ENV_PREFIX) -> {
                val envName = stored.removePrefix(VAULT_ENV_PREFIX)
                System.getenv(envName)?.ifBlank { null }
            }
            else -> stored
        }
    }

    /** Parsed header row. {@code valueOrSentinel} is cleartext, except
     *  when {@code secret} is true and storage="keystore", in which case
     *  it's the literal {@code KEYSTORE_SENTINEL} and the real value lives
     *  under {@code secretStore.get("header:$name")}. */
    private data class HeaderSpec(val name: String, val valueOrSentinel: String, val secret: Boolean)

    /**
     * Parse the textarea into structured header specs. A leading
     * <code>!</code> on the header name marks it as secret; the bang
     * stays off the stored name so downstream calls use the real header
     * key.
     */
    private fun parseHeaderSpecs(raw: String): List<HeaderSpec> =
        raw.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val sep = line.indexOfFirst { it == ':' || it == '=' }
                if (sep <= 0) return@mapNotNull null
                var name = line.substring(0, sep).trim()
                val value = line.substring(sep + 1).trim()
                val secret = name.startsWith("!")
                if (secret) name = name.removePrefix("!").trim()
                if (name.isBlank()) null else HeaderSpec(name, value, secret)
            }.toList()

    /**
     * Resolve a parsed header spec to (name -> cleartext value), pulling
     * secrets from the keystore when necessary. Returns null entries for
     * secrets that could not be resolved — callers typically drop those
     * from the outbound request and surface a warning.
     */
    private fun resolveHeaders(specs: List<HeaderSpec>): List<Pair<String, String?>> =
        specs.map { s ->
            when {
                s.secret && s.valueOrSentinel == KEYSTORE_SENTINEL ->
                    s.name to secretStore.get("header:${s.name}")
                s.secret && s.valueOrSentinel.startsWith(VAULT_ENV_PREFIX) -> {
                    val envName = s.valueOrSentinel.removePrefix(VAULT_ENV_PREFIX)
                    s.name to (System.getenv(envName)?.ifBlank { null })
                }
                else -> s.name to s.valueOrSentinel
            }
        }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun maskToken(t: String): String =
        if (t.length <= 12) "***"
        else t.substring(0, 6) + "..." + t.substring(t.length - 4)

    /**
     * Parse the custom-headers textarea into a map. Accepts one header per
     * line, either "Key: Value" or "Key=Value". Blank and #-prefixed lines
     * are ignored.
     */
    private fun parseHeaders(raw: String): Map<String, String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val sep = line.indexOfFirst { it == ':' || it == '=' }
                if (sep <= 0) null
                else line.substring(0, sep).trim() to line.substring(sep + 1).trim()
            }
            .toMap()

    /**
     * Write <code>~/.ai-bench/corp-openai.yaml</code> matching the harness's
     * {@code CorpOpenAiConfig} schema. Credentials themselves are not
     * written; env-var NAMES are written so the harness can re-read
     * values from a real secret store at runtime. Returns the absolute
     * path written.
     */
    private fun writeCorpOpenAiYaml(session: HttpSession): String {
        val loginUrl = session.getAttribute(APIGEE_LOGIN_URL) as? String ?: ""
        val scope = session.getAttribute(APIGEE_SCOPE) as? String ?: ""
        val baseUrl = session.getAttribute(OAI_BASE_URL) as? String ?: ""
        val defaultModel = session.getAttribute(OAI_DEFAULT_MODEL) as? String ?: "gpt-4o"
        val clientIdEnvName = session.getAttribute(CLIENT_ID_ENV_NAME) as? String ?: "CORP_LLM_CLIENT_ID"
        val clientSecretEnvName = session.getAttribute(CLIENT_SECRET_ENV_NAME) as? String ?: "CORP_LLM_CLIENT_SECRET"
        val headerSpecs = parseHeaderSpecs(session.getAttribute(OAI_CUSTOM_HEADERS) as? String ?: "")

        val dir = java.io.File(System.getProperty("user.home"), ".ai-bench")
        dir.mkdirs()
        val file = java.io.File(dir, "corp-openai.yaml")
        val yaml = buildString {
            appendLine("# Written by bench-webui LLM wizard. Edit by hand if the schema changes.")
            appendLine("# Secrets are NOT in this file — the harness reads them from env at")
            appendLine("# runtime using the *EnvName fields below.")
            appendLine()
            appendLine("baseUrl: \"$baseUrl\"")
            appendLine("defaultModel: \"$defaultModel\"")
            appendLine("apigee:")
            appendLine("  tokenUrl: \"$loginUrl\"")
            appendLine("  clientIdEnv: \"$clientIdEnvName\"")
            appendLine("  clientSecretEnv: \"$clientSecretEnvName\"")
            appendLine("  scope: \"$scope\"")
            appendLine("  tokenCacheTtlSec: 1800")
            val publicHeaders = headerSpecs.filterNot { it.secret }
            val secretHeaders = headerSpecs.filter { it.secret }
            if (publicHeaders.isNotEmpty()) {
                appendLine("headers:")
                publicHeaders.forEach { s ->
                    appendLine("  \"${s.name}\": \"${s.valueOrSentinel.replace("\"", "\\\"")}\"")
                }
            }
            if (secretHeaders.isNotEmpty()) {
                appendLine("# Secret headers (resolved at runtime — not in this file):")
                secretHeaders.forEach { s ->
                    val source = when {
                        s.valueOrSentinel == KEYSTORE_SENTINEL ->
                            "OS keystore (service=${SecretStore.SERVICE}, account=header:${s.name})"
                        s.valueOrSentinel.startsWith(VAULT_ENV_PREFIX) ->
                            "env var ${s.valueOrSentinel.removePrefix(VAULT_ENV_PREFIX)} (HashiCorp Vault / injector)"
                        else -> "in-session memory"
                    }
                    appendLine("#   \"${s.name}\": <$source>")
                }
            }
            appendLine("proxy:")
            appendLine("  urlEnv: \"HTTPS_PROXY\"")
        }
        file.writeText(yaml)
        return file.absolutePath
    }
}
