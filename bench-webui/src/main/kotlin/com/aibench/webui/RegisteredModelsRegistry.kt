package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Component

/**
 * Single source of truth for "what LLM models can the harness actually
 * reach right now". Combines two sources:
 *
 * <ul>
 *   <li><b>Persisted entries</b> — everything the operator added via the
 *       Copilot wizard's Register button (or by other manual flows).
 *       Stored in {@link RegisteredModelsStore}, which writes to
 *       {@code ~/.ai-bench/registered-models.json} so they survive a
 *       bench-webui restart.</li>
 *   <li><b>Auto-derived defaults</b> — synthetic entries that exist only
 *       when their underlying provider is healthy:
 *       <code>copilot-default</code> when the VSCode bridge socket is
 *       live, and <code>corp-openai-default</code> when the Corp-OpenAI
 *       wizard has completed the YAML write.</li>
 * </ul>
 *
 * <p>Both LlmConfigController (the LLMs page) and DemoController (the
 * Quick Benchmark dropdown) read from here so they always agree on
 * what's available.
 */
@Component
class RegisteredModelsRegistry(
    private val priceCatalog: ModelPriceCatalog,
    private val registeredModelsStore: RegisteredModelsStore
) {

    /**
     * Enrich a freshly-built ModelInfo with prices from the public
     * model-reference catalog when the registry id matches. Auto-
     * discovered Copilot entries (copilot-gpt-4-1, copilot-claude-...,
     * etc.) come back from the bridge with no pricing attached, which
     * left the Registered models table showing $0.000000 for every row
     * even though the catalog below it had real per-1K rates. Joining
     * the two by id surfaces the rates inline.
     *
     * <p>The catalog's Copilot entries are deliberately set to the
     * underlying vendor's direct per-token rate (OpenAI / Anthropic /
     * Google) rather than $0 -- Copilot is seat-priced so the actual
     * billed amount is $0, but operators want a chargeback estimate
     * for cost-comparison purposes ("what would this run cost if
     * billed direct?"). Per-row notes in the catalog explain this.
     */
    private fun LlmConfigController.ModelInfo.withCatalogPrice(): LlmConfigController.ModelInfo {
        if (costPer1kPrompt > 0.0 || costPer1kCompletion > 0.0) return this
        val price = priceCatalog.catalog.firstOrNull { it.id == this.id }
            ?: return this
        return copy(
            costPer1kPrompt = price.costPer1kPrompt,
            costPer1kCompletion = price.costPer1kCompletion ?: 0.0
        )
    }

    /** Path under {@code ~/.ai-bench/} the harness writes its config to. */
    private val corpConfigPath: String =
        System.getProperty("user.home") + "/.ai-bench/corp-openai.yaml"

    /**
     * Locate the AppMap CLI binary if installed. Checks the conventional
     * installer drop-zones on each platform first, then falls back to
     * scanning {@code PATH}. Windows invocations need the {@code .exe}
     * (or {@code .cmd} for the npm-global shim) extension; macOS and
     * Linux look for the POSIX-shipped binary name.
     *
     * <p>Search order:
     * <ol>
     *   <li>{@code $HOME/.appmap/bin/appmap[.exe]} — official installer
     *       symlink / shim.</li>
     *   <li>{@code %APPDATA%/AppMap/bin/appmap.exe} — Windows per-user
     *       install path used by some installers.</li>
     *   <li>{@code %LOCALAPPDATA%/AppMap/bin/appmap.exe} — alternate
     *       Windows per-user install path.</li>
     *   <li>Every directory on {@code PATH}, checking each
     *       platform-appropriate executable suffix.</li>
     * </ol>
     * Returns the first hit as an absolute path, or {@code null} when
     * no candidate is callable.
     */
    fun appmapCliPath(): String? {
        val home = System.getProperty("user.home")
        // Platform-appropriate executable suffixes to try. On Windows
        // the same command can be either a native .exe or an npm-global
        // .cmd shim, so we probe both; on *nix the name has no suffix.
        val suffixes: List<String> = if (Platform.isWindows)
            listOf(".exe", ".cmd", ".bat", "")
        else
            listOf("")

        val candidateDirs = mutableListOf<String>()
        candidateDirs += "$home/.appmap/bin"
        if (Platform.isWindows) {
            System.getenv("APPDATA")?.let { candidateDirs += "$it\\AppMap\\bin" }
            System.getenv("LOCALAPPDATA")?.let { candidateDirs += "$it\\AppMap\\bin" }
            // Common npm-global install dir on Windows.
            System.getenv("APPDATA")?.let { candidateDirs += "$it\\npm" }
        }
        for (dir in candidateDirs) {
            for (suf in suffixes) {
                val f = java.io.File(dir, "appmap$suf")
                if (f.isFile) return f.absolutePath
            }
        }
        // Fall back to PATH scan — covers brew, npm-global on *nix,
        // hand-installed binaries.
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(java.io.File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (suf in suffixes) {
                val candidate = java.io.File(dir, "appmap$suf")
                if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            }
        }
        return null
    }

    fun availableModels(session: HttpSession): List<LlmConfigController.ModelInfo> {
        val builtins = mutableListOf<LlmConfigController.ModelInfo>()

        // Auto-derived: Copilot bridge — present whenever the VSCode
        // bridge is actually accepting TCP connections. The user doesn't
        // have to Register anything to get this entry; it shows up
        // implicitly when the bridge is reachable.
        val copilotPort = Platform.readCopilotPort()
        val copilotLive = copilotPort != null && runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", copilotPort), 500); true
            }
        }.getOrDefault(false)
        if (copilotLive) {
            builtins += LlmConfigController.ModelInfo(
                id = "copilot-default",
                displayName = "Copilot (default model)",
                provider = "copilot",
                modelIdentifier = "copilot",
                status = "available",
                editable = false
            )
            // Auto-discover the rest of the Copilot model catalogue
            // (gpt-4-1, claude-sonnet-4, gpt-5, etc.) from the live
            // bridge so the operator doesn't have to click "List
            // Copilot Models" + "Register" for every model after
            // every webui restart. Cached for 30s so this isn't a
            // per-page-render TCP roundtrip.
            fetchAndCacheCopilotModels(copilotPort).forEach { builtins += it }
        }

        // Auto-derived: AppMap Navie via Copilot — only meaningful when
        // BOTH the bridge socket is live (Navie's LLM hop) AND the
        // AppMap CLI binary is installed on the host (Navie's
        // context-search engine). Navie itself talks to the bridge's
        // OpenAI-compatible HTTP endpoint, so listing this entry tells
        // the operator the full chain is intact: AppMap CLI → OpenAI
        // shim on the bridge → Copilot.
        val cli = appmapCliPath()
        if (copilotLive && cli != null) {
            builtins += LlmConfigController.ModelInfo(
                id = "appmap-navie-default",
                displayName = "AppMap Navie via Copilot",
                provider = "appmap-navie",
                // Identifier the harness passes to `appmap navie`: the
                // CLI ultimately routes to whichever model Copilot's
                // bridge resolves; we expose `navie-via-copilot` so the
                // log feed makes the routing path explicit.
                modelIdentifier = "navie-via-copilot",
                status = "available",
                editable = false
            )
        }

        // Auto-derived: Corporate OpenAI default — present when the
        // wizard has completed (yaml file exists). Pulls the actual
        // default-model id from session so the dropdown shows the same
        // thing the LLMs page does.
        if (java.io.File(corpConfigPath).exists()) {
            val defaultModel = (session.getAttribute("corpOpenAi.defaultModel") as? String) ?: "gpt-4o"
            builtins += LlmConfigController.ModelInfo(
                id = "corp-openai-default",
                displayName = "Corporate OpenAI ($defaultModel)",
                provider = "corp-openai",
                modelIdentifier = defaultModel,
                status = "available",
                editable = false
            )
        }

        // Manually-registered models come from the disk-backed store
        // (~/.ai-bench/registered-models.json) so they survive a
        // bench-webui restart. Previously kept in HttpSession under
        // "llmModels", which was wiped on every JVM bounce.
        val storedModels = registeredModelsStore.all()

        // Auto-derived defaults take precedence by id; store entries
        // with the same id are shadowed (rare, but possible if the
        // operator manually registers `copilot-default`).
        val seenIds = builtins.map { it.id }.toMutableSet()
        val merged = builtins.toMutableList()
        for (m in storedModels) {
            if (seenIds.add(m.id)) merged += m
        }
        // Last pass: fill in per-1K pricing from the public catalog for
        // any entry that's still at $0 / $0 (Copilot auto-discovery
        // never carries pricing). withCatalogPrice() is a no-op when
        // the entry already has prices set (manual registration via
        // the Add Model form).
        return merged.map { it.withCatalogPrice() }
    }

    /** Convenience: distinct provider names from the merged list. */
    fun availableProviders(session: HttpSession): List<String> =
        availableModels(session).map { it.provider }.distinct().sorted()

    // ---- Copilot model auto-discovery cache --------------------------
    // The bridge's list-models op is a fresh TCP roundtrip with a 10s
    // soTimeout; calling it on every page render would be wasteful and
    // would also block UI threads behind the bridge during a slow
    // VSCode session. Cache the result for 30 seconds. The set
    // legitimately changes only when the operator's GitHub Copilot
    // entitlements change OR they reload the VSCode bridge, so 30s
    // is the right side of "fast vs fresh."

    @Volatile
    private var copilotModelsCache: List<LlmConfigController.ModelInfo> = emptyList()

    @Volatile
    private var copilotModelsCacheUntil: Long = 0L

    private fun fetchAndCacheCopilotModels(port: Int): List<LlmConfigController.ModelInfo> {
        val now = System.currentTimeMillis()
        if (now < copilotModelsCacheUntil) return copilotModelsCache
        val fresh = runCatching { listCopilotModelsViaBridge(port) }.getOrDefault(emptyList())
        copilotModelsCache = fresh
        copilotModelsCacheUntil = now + 30_000
        return fresh
    }

    private fun listCopilotModelsViaBridge(port: Int): List<LlmConfigController.ModelInfo> {
        return java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 2_000)
            s.soTimeout = 10_000
            s.outputStream.write("{\"op\":\"list-models\"}\n".toByteArray(Charsets.UTF_8))
            s.outputStream.flush()
            val buf = ByteArray(64 * 1024)
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val n = try { s.inputStream.read(buf) } catch (_: Exception) { -1 }
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                if (sb.contains('\n')) break
            }
            val line = sb.toString().substringBefore('\n').takeIf { it.isNotBlank() }
                ?: return@use emptyList()
            // Parse the JSON-line shape:
            //   {"ok":true,"models":[{"id":"...","name":"...","family":"..."}, ...]}
            // Use a minimal regex extraction rather than dragging Jackson in;
            // the field shape is stable and we only need id/name.
            val models = mutableListOf<LlmConfigController.ModelInfo>()
            // Match each {"id":"...","name":"...",...} object.
            val rx = Regex("""\{\s*"id"\s*:\s*"([^"]+)"[^}]*?(?:"name"\s*:\s*"([^"]*)")?[^}]*?\}""")
            rx.findAll(line).forEach { m ->
                val rawId = m.groupValues[1]
                val displayName = m.groupValues[2].ifBlank { rawId }
                if (rawId == "copilot") return@forEach   // already added as copilot-default
                // The form's id-namespace prefix lets the dropdown
                // distinguish "copilot foo" from a coincidentally-named
                // id from another provider.
                val sanitized = "copilot-" + rawId.replace(Regex("[^A-Za-z0-9_\\-]"), "-")
                models += LlmConfigController.ModelInfo(
                    id = sanitized,
                    displayName = "Copilot · $displayName",
                    provider = "copilot",
                    modelIdentifier = rawId,
                    status = "available",
                    editable = false
                )
            }
            models
        }
    }
}
