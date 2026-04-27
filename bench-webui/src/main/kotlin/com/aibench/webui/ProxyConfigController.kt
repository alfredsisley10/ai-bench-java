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
    private val connectionSettings: ConnectionSettings
) {

    @GetMapping("/proxy")
    fun proxyConfig(model: Model, session: HttpSession): String {
        model.addAttribute("settings", connectionSettings.settings)
        model.addAttribute("gradleSystemProps", connectionSettings.gradleSystemProps())
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
        val parts = mutableListOf("Proxy settings saved and Gradle properties updated.")
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
        session: HttpSession
    ): String {
        val existing = connectionSettings.settings
        val keepMirrorPw = mirrorAuthPassword.isBlank() && existing.mirrorAuthPassword.isNotBlank()
        connectionSettings.update(
            existing.copy(
                mirrorUrl = mirrorUrl.trim(),
                mirrorAuthUser = mirrorAuthUser.trim(),
                mirrorAuthPassword = if (keepMirrorPw) existing.mirrorAuthPassword else mirrorAuthPassword
            )
        )
        val parts = mutableListOf<String>()
        parts += if (mirrorUrl.isBlank()) "Artifactory mirror cleared."
                 else "Artifactory mirror saved: $mirrorUrl"
        if (mirrorAuthUser.isNotBlank()) parts += "Mirror auth installed (user '$mirrorAuthUser')."
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
                mirrorAuthPassword = if (keepMirrorPw) existing.mirrorAuthPassword else mirrorAuthPassword
            )
        )
        session.setAttribute("proxySaveResult", "Proxy + mirror settings saved.")
        return "redirect:/proxy"
    }

    @PostMapping("/proxy/reset")
    fun reset(session: HttpSession): String {
        connectionSettings.resetToDetected()
        session.setAttribute("proxySaveResult", "Connection settings reset to auto-detected defaults.")
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
     * Tier 3 mirror verification: actually invoke gradle to resolve
     * a plugin DSL request against the saved mirror. Slowest but
     * most authoritative -- mirrors what the harness will do at
     * benchmark time.
     */
    @PostMapping("/proxy/test-mirror-gradle")
    @ResponseBody
    fun testMirrorGradle(): Map<String, Any> {
        val r = connectionSettings.probeMirrorViaGradle()
        return mapOf(
            "ok" to r.ok,
            "exitCode" to r.exitCode,
            "durationMs" to r.durationMs,
            "gradleBinary" to r.gradleBinary,
            "tmpDir" to r.tmpDir,
            "message" to r.message,
            "log" to r.log
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
