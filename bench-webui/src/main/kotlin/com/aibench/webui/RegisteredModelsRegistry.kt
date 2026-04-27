package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Component

/**
 * Single source of truth for "what LLM models can the harness actually
 * reach right now". Combines two sources:
 *
 * <ul>
 *   <li><b>Session-stored entries</b> — everything the operator added via
 *       the Copilot wizard's Register button (or by other manual flows).
 *       Keyed in session under {@code llmModels}.</li>
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
class RegisteredModelsRegistry {

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

        @Suppress("UNCHECKED_CAST")
        val sessionModels = (session.getAttribute("llmModels")
            as? List<LlmConfigController.ModelInfo>) ?: emptyList()

        // Auto-derived defaults take precedence by id; session-added
        // entries with the same id are shadowed (rare, but possible if
        // the operator manually registers `copilot-default`).
        val seenIds = builtins.map { it.id }.toMutableSet()
        val merged = builtins.toMutableList()
        for (m in sessionModels) {
            if (seenIds.add(m.id)) merged += m
        }
        return merged
    }

    /** Convenience: distinct provider names from the merged list. */
    fun availableProviders(session: HttpSession): List<String> =
        availableModels(session).map { it.provider }.distinct().sorted()
}
