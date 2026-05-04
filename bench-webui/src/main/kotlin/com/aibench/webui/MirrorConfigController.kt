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
    private val bankingApp: BankingAppManager,
    private val depCatalog: GradleDepCatalog,
    private val depValidator: GradleDepValidator,
    private val gradleProps: GradlePropertiesService,
    private val enterpriseSamples: EnterpriseGradleSamples,
    private val llmDiagnostician: LlmDiagnostician
) {
    private val log = org.slf4j.LoggerFactory.getLogger(MirrorConfigController::class.java)

    @GetMapping("/mirror")
    fun mirrorConfig(model: Model, session: HttpSession): String {
        model.addAttribute("settings", connectionSettings.settings)
        model.addAttribute("saveResult", session.getAttribute("mirrorSaveResult"))
        session.removeAttribute("mirrorSaveResult")
        // Catalog of every dep bench-webui + banking-app need at
        // build time, grouped by category. Surface lets the operator
        // toggle which categories to include in a validate run.
        model.addAttribute("depCategories", depCatalog.byCategory())
        // The live ~/.gradle/gradle.properties text for the "compare"
        // panel's reference column. Empty string when the file
        // doesn't exist yet.
        model.addAttribute("currentGradleProps", gradleProps.currentText())
        return "mirror-config"
    }

    /**
     * Diff an operator-pasted candidate gradle.properties against
     * the live ~/.gradle/gradle.properties. Returns per-key status
     * (added / changed / removed / unchanged) + display-safe values
     * (password-shaped keys masked).
     */
    @PostMapping("/mirror/properties/compare")
    @org.springframework.web.bind.annotation.ResponseBody
    fun compareProperties(@RequestParam candidateText: String): Map<String, Any?> {
        val diff = gradleProps.diff(candidateText)
        return mapOf(
            "diff" to diff.map {
                mapOf(
                    "key" to it.key,
                    "status" to it.status.name,
                    "isSecret" to it.isSecret,
                    "candidateDisplay" to it.candidateDisplay,
                    "currentDisplay" to it.currentDisplay
                )
            },
            "summary" to mapOf(
                "added" to diff.count { it.status == GradlePropertiesService.DiffStatus.ADDED },
                "changed" to diff.count { it.status == GradlePropertiesService.DiffStatus.CHANGED },
                "removed" to diff.count { it.status == GradlePropertiesService.DiffStatus.REMOVED },
                "unchanged" to diff.count { it.status == GradlePropertiesService.DiffStatus.UNCHANGED }
            )
        )
    }

    /**
     * Diagnose a gradle connectivity / resolution failure with the
     * operator's chosen LLM. Mirrors the existing AppMap-trace
     * diagnose pattern (PR #16): produces two clearly-labelled
     * sections of recommendations:
     *   - Local resolutions (what to change on this box now)
     *   - Source-code project fixes (PRs to raise against
     *     ai-bench-java so the failure mode is eliminated)
     *
     * Inputs the prompt sees:
     *   - Current bench-webui connection settings (proxy/mirror)
     *   - The masked live gradle.properties
     *   - The recent dep-validator failure summary (scraped from
     *     the in-page table by JS and POSTed in)
     *   - The recent banking-app boot log tail (when the operator
     *     supplied one)
     *
     * Cleartext credentials NEVER make it into the prompt; the
     * masking helpers from PR-K + the proxy-args masker do that.
     */
    @PostMapping("/mirror/diagnose")
    @org.springframework.web.bind.annotation.ResponseBody
    fun diagnoseGradleFailure(
        @RequestParam(defaultValue = "copilot-default") modelId: String,
        @RequestParam(required = false, defaultValue = "") depFailures: String,
        @RequestParam(required = false, defaultValue = "") logTail: String,
        @RequestParam(required = false, defaultValue = "") freeFormHint: String,
        session: HttpSession
    ): Map<String, Any?> {
        val s = connectionSettings.settings
        val maskedProps = gradleProps.diff(gradleProps.currentText())
            .joinToString("\n") { "${it.key}=${it.currentDisplay ?: ""}" }
            .ifBlank { "(file does not exist or is empty)" }
        val maskedGradleArgs = connectionSettings.maskCredentialArgs(
            connectionSettings.gradleSystemProps()
        )

        val systemPrompt = """
            You are a senior Gradle / corp-build engineer triaging a
            gradle connectivity or dependency-resolution failure on
            an enterprise developer box. Bench-webui has captured the
            operator's current settings + recent failure signals; your
            job is to spot the actual root cause and recommend
            actionable fixes.

            Respond in TWO clearly-delimited sections:

            ## Local resolutions
            Concrete steps the operator can take on THIS machine right
            now. Examples: change a /mirror or /proxy field in
            bench-webui, edit ~/.gradle/gradle.properties (cite the
            specific systemProp.* line), kill a stale gradle daemon,
            install a missing JDK toolchain, ask the corp ITA team
            for a token to a different Artifactory virtual. Cite
            specific commands or button names.

            ## Source-code project fixes
            Changes that should be raised as PRs against the
            ai-bench-java repo so this failure mode stops happening
            for future developers. Examples: bump or pin a plugin
            version in banking-app/build.gradle.kts, add a clearer
            error in ConnectionSettings.parseHostPort, add a
            defensive check in AppMapTraceManager, document a JDK
            requirement in README. Name the file path and the change
            in 1-2 sentences each.

            Be concrete. Cite the specific failing coordinate or
            error string when present. 2-5 bullets per section. If
            no fix is needed in one section, say so in one line
            instead of inventing recommendations.
        """.trimIndent()

        val userPrompt = """
            Operator's current bench-webui connection settings:
            - HTTPS proxy: ${s.httpsProxy.ifBlank { "(none)" }}
            - HTTP proxy: ${s.httpProxy.ifBlank { "(none)" }}
            - Mirror URL: ${s.mirrorUrl.ifBlank { "(none)" }}
            - Mirror auth: ${if (s.hasMirrorAuth) "configured (user '${s.mirrorAuthUser}')" else "(none)"}
            - Bypass mirror: ${s.bypassMirror}
            - Insecure SSL: ${s.insecureSsl}

            Gradle subprocess JVM args bench-webui appends (credentials masked):
            ```
            ${maskedGradleArgs.joinToString(" ")}
            ```

            Live ~/.gradle/gradle.properties (credential values masked):
            ```
            $maskedProps
            ```

            Recent dep-validator failures the operator captured
            (empty when no validate-deps run yet):
            ```
            ${depFailures.ifBlank { "(no dep-validator failures supplied)" }}
            ```

            Recent log tail (banking-app bootRun.log or similar; empty
            when not provided):
            ```
            ${logTail.take(8000).ifBlank { "(no log tail supplied)" }}
            ```

            Operator's free-form note (empty when none):
            "${freeFormHint.ifBlank { "(none)" }}"
        """.trimIndent()

        return llmDiagnostician
            .diagnose(modelId, systemPrompt, userPrompt, session, timeoutSec = 60)
            .toMap()
    }

    /**
     * Recommend a gradle.properties shape for the operator's
     * environment. The LLM gets:
     *   - Current settings (proxy / mirror / bypass)
     *   - Live ~/.gradle/gradle.properties (masked)
     *   - The full enterprise-sample catalog as reference
     *   - Any dep-validator results the operator already ran
     *     (passed as latestDepFailures so the LLM can target
     *     fixes for the actual broken artifacts)
     * It returns a recommended snippet PLUS a 2-3 bullet rationale.
     * The operator pastes the snippet into the Compare-and-apply
     * form above, reviews per-key, and applies what they want.
     */
    @PostMapping("/mirror/properties/recommend")
    @org.springframework.web.bind.annotation.ResponseBody
    fun recommendProperties(
        @RequestParam(defaultValue = "copilot-default") modelId: String,
        @RequestParam(required = false, defaultValue = "") latestDepFailures: String,
        @RequestParam(required = false, defaultValue = "") freeFormHint: String,
        session: HttpSession
    ): Map<String, Any?> {
        val s = connectionSettings.settings
        // Render samples as a markdown-style block the LLM can pattern-match against.
        val sampleBlock = enterpriseSamples.samples.joinToString("\n\n") { sample ->
            """
            ### Sample: ${sample.title} (id=${sample.id})
            ${sample.description}
            When to use: ${sample.whenToUse}

            ```properties
            ${sample.text}
            ```
            """.trimIndent()
        }
        // Mask the live file before showing it to the LLM -- the
        // actual cleartext password should never go over the bridge.
        val liveMasked = gradleProps.diff(gradleProps.currentText())
            .joinToString("\n") { "${it.key}=${it.currentDisplay ?: ""}" }
            .ifBlank { "(file does not exist or is empty)" }

        val systemPrompt = """
            You are a senior Gradle / corporate-build engineer helping
            an operator pick a working gradle.properties for their
            enterprise dev box. Bench-webui has a small catalog of
            known-working enterprise shapes; your job is to pick the
            one that best matches the operator's current state and
            ADAPT it to their actual proxy / mirror values.

            Output strictly in three sections, separated by blank
            lines + a markdown header:

            ## Recommended sample
            One sentence naming the sample id from the catalog you're
            adapting (e.g. "Adapting `artifactory-virtual-with-auth`")
            and a 1-line WHY.

            ## Recommended gradle.properties
            A complete `.properties` block in a fenced code block. Use
            placeholders for any value the operator hasn't supplied
            (mirror auth user / token). Leave existing values where
            possible.

            ## Apply notes
            2-4 bullets calling out which keys differ from the live
            file and what each one fixes. Cite specific symptoms (e.g.
            "the testcontainers 401s on the validate-deps panel will
            resolve once orgInternalMavenPassword is set").
        """.trimIndent()

        val userPrompt = """
            Operator's current bench-webui connection settings:
            - HTTPS proxy: ${s.httpsProxy.ifBlank { "(none)" }}
            - HTTP proxy: ${s.httpProxy.ifBlank { "(none)" }}
            - Mirror URL: ${s.mirrorUrl.ifBlank { "(none)" }}
            - Mirror auth user: ${s.mirrorAuthUser.ifBlank { "(none)" }}
            - Mirror auth password: ${if (s.mirrorAuthPassword.isNotBlank()) "(set, masked)" else "(unset)"}
            - Bypass mirror: ${s.bypassMirror}
            - Insecure SSL: ${s.insecureSsl}

            Operator's CURRENT ~/.gradle/gradle.properties (credential values masked):
            ```
            $liveMasked
            ```

            Recent dep-validator failures the operator pasted in
            (empty if not run):
            ```
            ${latestDepFailures.ifBlank { "(no dep-validator results supplied)" }}
            ```

            Operator's free-form note (empty if none):
            "${freeFormHint.ifBlank { "(none)" }}"

            Reference catalog of enterprise gradle.properties shapes:
            $sampleBlock
        """.trimIndent()

        val r = llmDiagnostician.diagnose(modelId, systemPrompt, userPrompt, session, timeoutSec = 60)
        return r.toMap() + mapOf(
            "samples" to enterpriseSamples.samples.map {
                mapOf("id" to it.id, "title" to it.title, "description" to it.description)
            }
        )
    }

    /**
     * Apply selected keys from the candidate text to the live
     * ~/.gradle/gradle.properties. Comments + unrelated keys
     * preserved verbatim. Side-effect: stop banking-app daemon
     * so the next gradle invocation reads the new file.
     */
    @PostMapping("/mirror/properties/apply")
    fun applyProperties(
        @RequestParam candidateText: String,
        @RequestParam(name = "keys", required = false) keys: List<String>?,
        session: HttpSession
    ): String {
        val keySet = (keys ?: emptyList()).toSet()
        if (keySet.isEmpty()) {
            session.setAttribute("mirrorSaveResult", "No keys selected — nothing applied.")
            return "redirect:/mirror"
        }
        val r = gradleProps.apply(candidateText, keySet)
        stopBankingAppDaemon()
        session.setAttribute("mirrorSaveResult",
            "Applied ${r.added + r.changed + r.removed} key(s) to ${r.filePath}: " +
            "${r.added} added, ${r.changed} changed, ${r.removed} removed. " +
            "Banking-app gradle daemon stopped so the next build reads the updated file.")
        return "redirect:/mirror"
    }

    /**
     * Gradle ecosystem connectivity sweep: services.gradle.org +
     * Maven Central + Foojay + the configured mirror. Moved here
     * from /proxy because the two pages are independent concerns
     * (proxy reachability vs gradle ecosystem reachability) and
     * bundling them under a single verdict on /proxy made it hard
     * to tell which half failed.
     */
    @PostMapping("/mirror/verify-connectivity")
    @org.springframework.web.bind.annotation.ResponseBody
    fun verifyGradleConnectivity(): Map<String, Any> {
        val results = connectionSettings.probeGradleConnectivity()
        val ok = results.all { it.ok }
        return mapOf(
            "ok" to ok,
            "summary" to (if (ok)
                "All ${results.size} gradle-ecosystem probes passed."
            else
                "${results.count { !it.ok }} of ${results.size} gradle probes failed."),
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

    /**
     * Probe every catalog entry whose category is in the supplied
     * list. Empty list = probe all. Returns one row per coordinate
     * with HTTP status / latency / mirror-vs-public route.
     */
    @PostMapping("/mirror/validate-deps")
    @org.springframework.web.bind.annotation.ResponseBody
    fun validateDeps(
        @RequestParam(name = "categories", required = false) categories: List<String>?
    ): Map<String, Any?> {
        val catSet = (categories ?: emptyList())
            .mapNotNull { runCatching { GradleDepCatalog.Category.valueOf(it) }.getOrNull() }
            .toSet()
        val results = depValidator.validate(catSet)
        val passed = results.count { it.ok }
        return mapOf(
            "summary" to "$passed of ${results.size} dependencies resolved.",
            "ok" to (passed == results.size),
            "results" to results.map { r ->
                mapOf(
                    "category" to r.entry.category.name,
                    "categoryDisplay" to r.entry.category.displayName,
                    "coord" to r.entry.coord,
                    "description" to r.entry.description,
                    "probeUrl" to r.probeUrl,
                    "viaProxy" to r.viaProxy,
                    "viaMirror" to r.viaMirror,
                    "statusCode" to r.statusCode,
                    "durationMs" to r.durationMs,
                    "ok" to r.ok,
                    "message" to r.message
                )
            }
        )
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
