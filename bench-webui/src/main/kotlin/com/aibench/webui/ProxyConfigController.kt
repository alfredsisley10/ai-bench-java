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
        // Empty password fields on the form mean "leave the existing
        // value alone" — typical practice for password inputs that
        // shouldn't echo current values back to the page.
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
        val parts = mutableListOf("Proxy settings saved and Gradle properties updated.")
        if (insecureSsl) parts += "SSL verification is DISABLED for outbound WebUI connections."
        if (proxyAuthUser.isNotBlank()) parts += "Proxy HTTP-Basic auth installed."
        if (mirrorUrl.isNotBlank()) parts += "Mirror URL applied."
        session.setAttribute("proxySaveResult", parts.joinToString(" "))
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
     * Probe the configured Artifactory mirror specifically — uses
     * the saved mirror URL and (if set) Basic-auth credentials so
     * the user can debug a 401/403 separately from a generic
     * connectivity issue.
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
