package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Thin UI wrapper over {@link ConnectionSettings}. All outbound HTTP
 * clients in the WebUI should build through {@code ConnectionSettings}
 * so the choices made here propagate everywhere.
 */
@Controller
class ProxyConfigController(
    private val connectionSettings: ConnectionSettings,
    private val bankingApp: BankingAppManager
) {

    private val log = org.slf4j.LoggerFactory.getLogger(ProxyConfigController::class.java)

    /**
     * Stop any running gradle daemon for banking-app so it picks up
     * the just-written ~/.gradle/gradle.properties on the next
     * invocation. Without this, a daemon spawned BEFORE the operator
     * saved proxy settings keeps its stale JVM args until idle-timeout
     * (default ~3 hours), so settings.gradle.kts in that daemon still
     * reports "no proxy configured" and downstream HTTPS to Maven
     * Central / mirror auth fails.
     *
     * Best-effort: a 30s timeout, exit code is logged but never
     * surfaced as a save failure -- daemon stop is an optimization,
     * not a correctness requirement.
     */
    private fun stopBankingAppDaemon() {
        val repo = bankingApp.bankingAppDir
        if (!repo.isDirectory) return
        val cmd = mutableListOf<String>().apply {
            addAll(Platform.gradleWrapper(repo))
            add("--stop")
        }
        runCatching {
            val proc = ProcessBuilder(cmd).directory(repo).redirectErrorStream(true).start()
            if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                log.warn("gradlew --stop timed out (30s) -- daemon may still be running stale")
            } else {
                log.info("gradlew --stop completed (exit={}) -- next gradle build will spawn a " +
                    "fresh daemon that reads ~/.gradle/gradle.properties", proc.exitValue())
            }
        }.onFailure { log.warn("gradlew --stop failed: {}", it.message) }
    }

    @GetMapping("/proxy")
    fun proxyConfig(model: Model, session: HttpSession): String {
        model.addAttribute("settings", connectionSettings.settings)
        // Mask the credential portions before rendering. The raw list
        // includes -Dhttp.proxyPassword=<cleartext> /
        // -Dhttps.proxyPassword=<cleartext>; the masked variant has
        // those swapped for ********. The page uses this for display only;
        // the actual gradle subprocess args still get the real values.
        model.addAttribute("gradleSystemProps",
            connectionSettings.maskCredentialArgs(connectionSettings.gradleSystemProps()))
        model.addAttribute("saveResult", session.getAttribute("proxySaveResult"))
        session.removeAttribute("proxySaveResult")
        return "proxy-config"
    }

    /**
     * Save the PROXY half only — leaves the mirror config untouched.
     * The form on /proxy is split so the operator can save proxy
     * changes without having to re-enter the mirror token (and vice
     * versa) — separating concerns also makes it clear which test
     * button verifies which half.
     */
    @PostMapping("/proxy/save-proxy")
    fun saveProxy(
        @RequestParam httpsProxy: String,
        @RequestParam httpProxy: String,
        @RequestParam noProxy: String,
        @RequestParam(required = false, defaultValue = "false") insecureSsl: Boolean,
        @RequestParam(required = false, defaultValue = "") proxyAuthUser: String,
        @RequestParam(required = false, defaultValue = "") proxyAuthPassword: String,
        session: HttpSession
    ): String {
        // Validate noProxy entries before saving. JVM nonProxyHosts entries
        // accept hostnames, IPs, dots, dashes, and '*' wildcards; anything
        // else (whitespace inside entries, shell metacharacters) is either
        // invalid syntax or an injection vector when the value gets piped
        // into a Gradle command line. Empty noProxy is fine.
        val invalid = noProxy.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.matches(Regex("^[A-Za-z0-9.\\-*]+$")) }
        if (invalid.isNotEmpty()) {
            session.setAttribute(
                "proxySaveResult",
                "Rejected: noProxy contains invalid entries " +
                    invalid.joinToString(", ") { "'$it'" } +
                    ". Allowed: hostnames, IPs, dots, dashes, and '*' wildcards. " +
                    "Examples: '*.corp.com', '127.*', 'localhost'. Comma-separate multiple entries."
            )
            return "redirect:/proxy"
        }

        val existing = connectionSettings.settings
        val keepProxyPw = proxyAuthPassword.isBlank() && existing.proxyAuthPassword.isNotBlank()
        connectionSettings.update(
            existing.copy(
                httpsProxy = httpsProxy.trim(),
                httpProxy = httpProxy.trim(),
                noProxy = noProxy.trim(),
                insecureSsl = insecureSsl,
                source = "manual",
                proxyAuthUser = proxyAuthUser.trim(),
                proxyAuthPassword = if (keepProxyPw) existing.proxyAuthPassword else proxyAuthPassword
                // mirrorUrl / mirrorAuthUser / mirrorAuthPassword: NOT
                // touched -- this endpoint only saves the proxy half.
            )
        )
        // Kill any running banking-app gradle daemon so the next
        // gradle invocation spawns a fresh one that reads the
        // just-written gradle.properties. Without this, an existing
        // daemon keeps its stale JVM args -- settings.gradle.kts
        // still warns "no proxy configured", and mirror lookups fail.
        stopBankingAppDaemon()
        val parts = mutableListOf("Proxy settings saved and Gradle properties updated. " +
            "Stopped any running banking-app gradle daemon so next build picks up new proxy.")
        if (insecureSsl) parts += "SSL verification is DISABLED for outbound WebUI connections."
        if (proxyAuthUser.isNotBlank()) parts += "Proxy HTTP-Basic auth installed."
        session.setAttribute("proxySaveResult", parts.joinToString(" "))
        return "redirect:/proxy"
    }

    /**
     * Save the ARTIFACTORY MIRROR half only -- leaves proxy + TLS
     * settings untouched. Mirror credentials use the same blank-means-
     * unchanged convention as the proxy form.
     */
    @PostMapping("/proxy/save-mirror")
    fun saveMirror(
        @RequestParam(required = false, defaultValue = "") mirrorUrl: String,
        @RequestParam(required = false, defaultValue = "") mirrorAuthUser: String,
        @RequestParam(required = false, defaultValue = "") mirrorAuthPassword: String,
        @RequestParam(required = false, defaultValue = "false") bypassMirror: Boolean,
        session: HttpSession
    ): String {
        val existing = connectionSettings.settings
        val keepMirrorPw = mirrorAuthPassword.isBlank() && existing.mirrorAuthPassword.isNotBlank()
        connectionSettings.update(
            existing.copy(
                mirrorUrl = mirrorUrl.trim(),
                mirrorAuthUser = mirrorAuthUser.trim(),
                mirrorAuthPassword = if (keepMirrorPw) existing.mirrorAuthPassword else mirrorAuthPassword,
                bypassMirror = bypassMirror
            )
        )
        stopBankingAppDaemon()
        val parts = mutableListOf<String>()
        parts += if (mirrorUrl.isBlank()) "Artifactory mirror cleared."
                 else "Artifactory mirror saved: $mirrorUrl"
        if (mirrorAuthUser.isNotBlank()) parts += "Mirror auth installed (user '$mirrorAuthUser')."
        if (bypassMirror) {
            parts += "Mirror BYPASSED for Gradle subprocesses — banking-app builds " +
                "will hit plugins.gradle.org / Maven Central direct via the proxy."
        }
        parts += "Banking-app gradle daemon stopped so next build picks up the change."
        session.setAttribute("proxySaveResult", parts.joinToString(" "))
        return "redirect:/proxy"
    }

    /**
     * Legacy combined save -- kept for any external caller that posts
     * both halves at once. The split forms above are the preferred
     * path; this routes through the same Settings update.
     */
    @PostMapping("/proxy/save")
    fun save(
        @RequestParam httpsProxy: String,
        @RequestParam httpProxy: String,
        @RequestParam noProxy: String,
        @RequestParam(required = false, defaultValue = "false") insecureSsl: Boolean,
        @RequestParam(required = false, defaultValue = "") proxyAuthUser: String,
        @RequestParam(required = false, defaultValue = "") proxyAuthPassword: String,
        @RequestParam(required = false, defaultValue = "") mirrorUrl: String,
        @RequestParam(required = false, defaultValue = "") mirrorAuthUser: String,
        @RequestParam(required = false, defaultValue = "") mirrorAuthPassword: String,
        @RequestParam(required = false) bypassMirror: Boolean?,
        session: HttpSession
    ): String {
        val existing = connectionSettings.settings
        val keepProxyPw = proxyAuthPassword.isBlank() && existing.proxyAuthPassword.isNotBlank()
        val keepMirrorPw = mirrorAuthPassword.isBlank() && existing.mirrorAuthPassword.isNotBlank()
        connectionSettings.update(
            ConnectionSettings.Settings(
                httpsProxy = httpsProxy.trim(),
                httpProxy = httpProxy.trim(),
                noProxy = noProxy.trim(),
                insecureSsl = insecureSsl,
                source = "manual",
                proxyAuthUser = proxyAuthUser.trim(),
                proxyAuthPassword = if (keepProxyPw) existing.proxyAuthPassword else proxyAuthPassword,
                mirrorUrl = mirrorUrl.trim(),
                mirrorAuthUser = mirrorAuthUser.trim(),
                mirrorAuthPassword = if (keepMirrorPw) existing.mirrorAuthPassword else mirrorAuthPassword,
                bypassMirror = bypassMirror ?: existing.bypassMirror
            )
        )
        stopBankingAppDaemon()
        session.setAttribute("proxySaveResult",
            "Proxy + mirror settings saved. Banking-app gradle daemon stopped.")
        return "redirect:/proxy"
    }

    @PostMapping("/proxy/reset")
    fun reset(session: HttpSession): String {
        connectionSettings.resetToDetected()
        stopBankingAppDaemon()
        session.setAttribute("proxySaveResult",
            "Connection settings reset to auto-detected defaults. " +
            "Banking-app gradle daemon stopped.")
        return "redirect:/proxy"
    }

    /**
     * Run the connectivity probes the WebUI uses to verify the saved
     * proxy/mirror config actually reaches what Gradle needs. Returns
     * one row per target (Gradle dist server, Maven Central, foojay,
     * the configured mirror, the proxy host). UI renders the result
     * inside a collapsible <details> on /proxy.
     */
    @PostMapping("/proxy/verify")
    @ResponseBody
    fun verify(): Map<String, Any> {
        val results = connectionSettings.probeConnectivity()
        val ok = results.all { it.ok }
        return mapOf(
            "ok" to ok,
            "summary" to (if (ok)
                "All ${results.size} probes passed."
            else
                "${results.count { !it.ok }} of ${results.size} probes failed."),
            "results" to results.map {
                mapOf(
                    "target" to it.target, "purpose" to it.purpose,
                    "viaProxy" to it.viaProxy, "statusCode" to it.statusCode,
                    "durationMs" to it.durationMs, "ok" to it.ok,
                    "message" to it.message
                )
            }
        )
    }

    @GetMapping("/api/proxy")
    @ResponseBody
    fun apiProxySettings(): ConnectionSettings.Settings = connectionSettings.settings

    /**
     * Single-URL probe through the configured proxy / TLS settings.
     * The /proxy "Test custom URL" form posts here with the user's
     * URL (default https://www.github.com). Response includes a
     * verbose log block the page renders inside a collapsible
     * &lt;details&gt; for debugging.
     */
    @PostMapping("/proxy/test-url")
    @ResponseBody
    fun testUrl(@RequestParam(required = false, defaultValue = "https://www.github.com") url: String): Map<String, Any> {
        val r = connectionSettings.probeUrlDetailed(url)
        return mapOf(
            "ok" to r.ok, "target" to r.target, "viaProxy" to r.viaProxy,
            "statusCode" to r.statusCode, "durationMs" to r.durationMs,
            "message" to r.message, "log" to r.log
        )
    }

    /**
     * Tier 2 mirror verification: probe a handful of known plugin
     * POMs against the saved mirror to prove it actually serves
     * the artifacts the harness needs, not just that the root URL
     * answers a 200. The existing /proxy/test-mirror is tier 1
     * (root reachability + auth); this endpoint is a stronger
     * "does plugin resolution have a chance of working?"
     */
    @PostMapping("/proxy/test-mirror-resolve")
    @ResponseBody
    fun testMirrorResolve(): Map<String, Any> {
        val results = connectionSettings.probeMirrorArtifacts()
        if (results.isEmpty()) {
            return mapOf(
                "ok" to false,
                "summary" to "No mirror URL configured. Save one in the form first.",
                "results" to emptyList<Any>()
            )
        }
        val passed = results.count { it.ok }
        val total = results.size
        return mapOf(
            "ok" to (passed == total),
            "summary" to "$passed of $total known plugin POMs resolved through the mirror.",
            "results" to results.map {
                mapOf(
                    "coord" to it.coord,
                    "description" to it.description,
                    "statusCode" to it.statusCode,
                    "ok" to it.ok,
                    "message" to it.message,
                    "pomUrl" to it.pomUrl
                )
            }
        )
    }

    /**
     * Tier 3 mirror verification, kickoff phase. Returns immediately
     * with the probe id, the gradle binary chosen, the scratch project
     * path, and the seed log entries -- so the operator sees exactly
     * what's about to run before the gradle process produces any output.
     * The actual gradle invocation runs on a background thread; the
     * client polls /proxy/test-mirror-gradle/status to stream output.
     */
    @PostMapping("/proxy/test-mirror-gradle/start")
    @ResponseBody
    fun testMirrorGradleStart(): Map<String, Any> {
        val state = connectionSettings.startMirrorGradleProbe()
        return mapOf(
            "id" to state.id,
            "done" to state.done,
            "gradleBinary" to state.gradleBinary,
            "tmpDir" to state.tmpDir,
            "command" to state.command,
            "log" to state.snapshotLog(),
            "ok" to (state.result?.ok ?: false),
            "exitCode" to (state.result?.exitCode ?: -1),
            "durationMs" to (System.currentTimeMillis() - state.started),
            "message" to (state.result?.message ?: "Setup complete; gradle process launching.")
        )
    }

    /**
     * Tier 3 mirror verification, polling phase. Returns the current
     * snapshot of the named probe -- log so far, done flag, and (once
     * complete) the final result. The UI calls this every ~1s while
     * a probe runs so the operator sees Gradle output stream in
     * instead of a frozen "Spawning…" banner for 30-90s.
     */
    @GetMapping("/proxy/test-mirror-gradle/status")
    @ResponseBody
    fun testMirrorGradleStatus(@RequestParam id: String): Map<String, Any> {
        val state = connectionSettings.getMirrorGradleProbe(id)
            ?: return mapOf(
                "found" to false,
                "done" to true,
                "ok" to false,
                "message" to "No probe with id $id (it may have expired or the WebUI restarted)."
            )
        val r = state.result
        return mapOf(
            "found" to true,
            "id" to state.id,
            "done" to state.done,
            "gradleBinary" to state.gradleBinary,
            "tmpDir" to state.tmpDir,
            "log" to state.snapshotLog(),
            "ok" to (r?.ok ?: false),
            "exitCode" to (r?.exitCode ?: -1),
            "durationMs" to (r?.durationMs ?: (System.currentTimeMillis() - state.started)),
            "message" to (r?.message ?: "Running…")
        )
    }

    /**
     * Tier 1 mirror verification: HTTP-level reachability of the saved
     * mirror URL -- uses Basic-auth credentials when configured so the
     * user can debug a 401/403 separately from a generic connectivity
     * issue. Pairs with the tier-2 (artifact resolve) and tier-3
     * (Gradle process) tests below.
     */
    @PostMapping("/proxy/test-mirror")
    @ResponseBody
    fun testMirror(): Map<String, Any> {
        val s = connectionSettings.settings
        if (!s.hasMirror) {
            return mapOf(
                "ok" to false, "target" to "", "viaProxy" to false,
                "statusCode" to -1, "durationMs" to 0,
                "message" to "No mirror URL configured. Save one in the form above first.",
                "log" to "[err] settings.mirrorUrl is empty\n"
            )
        }
        val r = connectionSettings.probeUrlDetailed(s.mirrorUrl, useMirrorAuth = true)
        return mapOf(
            "ok" to r.ok, "target" to r.target, "viaProxy" to r.viaProxy,
            "statusCode" to r.statusCode, "durationMs" to r.durationMs,
            "message" to r.message, "log" to r.log
        )
    }

    /**
     * Detect proxy settings from the OS (macOS networksetup / Windows
     * registry) and from VSCode's user settings.json. Returns a
     * pre-fill payload the page applies to the form fields client-side
     * so the operator can confirm before saving.
     */
    @PostMapping("/proxy/detect-os")
    @ResponseBody
    fun detectFromOs(): Map<String, Any> {
        val d = connectionSettings.detectFromOs()
        return mapOf(
            "ok" to d.foundIn.isNotEmpty(),
            "httpsProxy" to d.httpsProxy,
            "httpProxy" to d.httpProxy,
            "noProxy" to d.noProxy,
            "proxyAuthUser" to d.proxyAuthUser,
            "foundIn" to d.foundIn,
            "message" to (
                if (d.foundIn.isEmpty()) "No proxy configuration found in OS or VSCode."
                else "Detected from: ${d.foundIn.joinToString(", ")}. Click Save & apply to keep."),
            "log" to d.log
        )
    }
}
