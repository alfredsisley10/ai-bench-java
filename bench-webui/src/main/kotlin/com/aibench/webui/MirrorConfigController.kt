package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Admin surface for the Artifactory mirror configuration only.
 * Originally lived as a section inside /proxy; split out so the
 * operator can manage proxy and mirror independently without one
 * page bundling two distinct concerns.
 *
 * Mirror save calls into the existing ConnectionSettings.update()
 * path -- same persistence + gradle.properties write +
 * stop-banking-app-daemon side-effect that the in-place /proxy
 * mirror form used to do.
 *
 * The verify endpoints (POST /proxy/test-mirror, /proxy/test-mirror-resolve,
 * /proxy/test-mirror-gradle/start and /status) stay at their existing
 * paths for backward compatibility with any external callers; they're
 * stateless and the URL location is irrelevant. The /mirror page's JS
 * just calls them.
 */
@Controller
class MirrorConfigController(
    private val connectionSettings: ConnectionSettings,
    private val bankingApp: BankingAppManager
) {
    private val log = org.slf4j.LoggerFactory.getLogger(MirrorConfigController::class.java)

    @GetMapping("/mirror")
    fun mirrorConfig(model: Model, session: HttpSession): String {
        model.addAttribute("settings", connectionSettings.settings)
        model.addAttribute("saveResult", session.getAttribute("mirrorSaveResult"))
        session.removeAttribute("mirrorSaveResult")
        return "mirror-config"
    }

    @PostMapping("/mirror/save")
    fun save(
        @RequestParam(required = false, defaultValue = "") mirrorUrl: String,
        @RequestParam(required = false, defaultValue = "") mirrorAuthUser: String,
        @RequestParam(required = false, defaultValue = "") mirrorAuthPassword: String,
        @RequestParam(required = false, defaultValue = "false") bypassMirror: Boolean,
        session: HttpSession
    ): String {
        val existing = connectionSettings.settings
        val keepPw = mirrorAuthPassword.isBlank() && existing.mirrorAuthPassword.isNotBlank()
        connectionSettings.update(
            existing.copy(
                mirrorUrl = mirrorUrl.trim(),
                mirrorAuthUser = mirrorAuthUser.trim(),
                mirrorAuthPassword = if (keepPw) existing.mirrorAuthPassword else mirrorAuthPassword,
                bypassMirror = bypassMirror
            )
        )
        // Same daemon-stop pattern as ProxyConfigController so a
        // running banking-app gradle daemon picks up the new mirror
        // routing on its next invocation instead of holding the
        // stale repository config until idle-timeout.
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
        session.setAttribute("mirrorSaveResult", parts.joinToString(" "))
        return "redirect:/mirror"
    }

    private fun stopBankingAppDaemon() {
        val repo = bankingApp.bankingAppDir
        if (!repo.isDirectory) return
        val cmd = mutableListOf<String>().apply {
            addAll(Platform.gradleWrapper(repo)); add("--stop")
        }
        runCatching {
            val proc = ProcessBuilder(cmd).directory(repo).redirectErrorStream(true).start()
            if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                log.warn("gradlew --stop timed out (30s)")
            } else {
                log.info("gradlew --stop completed (exit={})", proc.exitValue())
            }
        }.onFailure { log.warn("gradlew --stop failed: {}", it.message) }
    }
}
