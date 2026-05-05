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
                    "severity" to r.severity.name,
                    "message" to r.message
                )
            }
        )
    }

    /**
     * Per-coord version-range probe — sweeps a window of nearby
     * versions for the given coord against the configured mirror
     * and surfaces which versions are policy-allowed. Catches the
     * "this specific version is on the corp blocklist (CVE) and
     * an adjacent patch resolves cleanly" pattern that operators
     * keep hitting -- the standard validator only probes the pinned
     * version, so the policy boundary stays invisible.
     */
    @PostMapping("/mirror/validate-deps/version-range")
    @org.springframework.web.bind.annotation.ResponseBody
    fun versionRangeProbe(
        @RequestParam coord: String
    ): Map<String, Any?> {
        val r = depValidator.probeVersionRange(coord)
        return mapOf(
            "ok" to r.probes.any { it.ok },
            "coord" to r.coord,
            "groupId" to r.groupId,
            "artifactId" to r.artifactId,
            "currentVersion" to r.currentVersion,
            "candidatesSource" to r.candidatesSource,
            "recommendation" to r.recommendation,
            "probes" to r.probes.map { p ->
                mapOf(
                    "version" to p.version,
                    "pomUrl" to p.pomUrl,
                    "ok" to p.ok,
                    "statusCode" to p.statusCode,
                    "durationMs" to p.durationMs,
                    "message" to p.message,
                    "isCurrent" to p.isCurrent
                )
            }
        )
    }

    /**
     * Iterate the supplied list of failed coordinates through every
     * connectivity option the operator has configured: primary
     * mirror, maven-external-virtual, direct upstream, and (for
     * gradle plugin markers) the rewrite-target maven coord that
     * the corp init script would substitute at build time.
     *
     * Used by the /mirror "🔄 Retry failed with alternative paths"
     * button. Catches the case where (for example) every gradle
     * plugin row goes red on the validator with HttpTimeoutException
     * because the configured probe path went direct to
     * plugins.gradle.org from a corp network that doesn't allow
     * direct egress -- the alternative routes via the mirror often
     * resolve fine, and the operator just needs to see which path
     * works.
     */
    @PostMapping("/mirror/validate-deps/retry-alternatives")
    @org.springframework.web.bind.annotation.ResponseBody
    fun retryAlternatives(
        @RequestParam(name = "coord") coords: List<String>
    ): Map<String, Any?> {
        val results = depValidator.probeAlternatives(coords)
        val anySucceeded = results.any { r -> r.attempts.any { it.ok } }
        return mapOf(
            "ok" to anySucceeded,
            "summary" to (if (anySucceeded)
                "${results.count { r -> r.attempts.any { it.ok } }} of ${results.size} " +
                "failed coords have at least one working alternative path."
            else
                "None of the ${results.size} failed coords resolved through any configured option."),
            "results" to results.map { r ->
                mapOf(
                    "coord" to r.coord,
                    "category" to r.category.name,
                    "categoryDisplay" to r.category.displayName,
                    "recommendation" to r.recommendation,
                    "attempts" to r.attempts.map { a ->
                        mapOf(
                            "label" to a.label,
                            "probeUrl" to a.probeUrl,
                            "viaProxy" to a.viaProxy,
                            "ok" to a.ok,
                            "statusCode" to a.statusCode,
                            "durationMs" to a.durationMs,
                            "message" to a.message
                        )
                    }
                )
            }
        )
    }

    /**
     * Discovery wizard: operator hands us one or more candidate
     * Artifactory URLs (typically pasted from internal docs / IT
     * email), and we probe every meaningful combination of
     * (URL × proxy on/off × TLS secure/insecure × auth on/off)
     * against a small reference artifact set. Returns the combos
     * ranked by pass-rate so the operator can pick the best and
     * one-click apply via /mirror/discover/apply.
     *
     * The point of the feature: a corp Artifactory deployment can
     * be exposed at multiple URLs (one per realm / virtual / cluster),
     * and the operator often doesn't know in advance which combo of
     * proxy routing + TLS verification their network actually allows.
     * Brute-forcing the matrix in ~5s saves them from playing
     * /proxy + /mirror tag for half a day.
     */
    @PostMapping("/mirror/discover")
    @org.springframework.web.bind.annotation.ResponseBody
    fun discoverConfig(
        @RequestParam(name = "candidateUrl") candidateUrls: List<String>
    ): Map<String, Any?> {
        val combos = depValidator.discoverConfig(candidateUrls)
        val winners = combos.filter { it.passedCount == it.totalCount }
        val partial = combos.filter { it.passedCount in 1 until it.totalCount }
        return mapOf(
            "ok" to combos.isNotEmpty(),
            "summary" to (when {
                winners.isNotEmpty() ->
                    "${winners.size} of ${combos.size} combos passed all reference probes. " +
                    "Top: ${winners.first().label}"
                partial.isNotEmpty() ->
                    "${partial.size} of ${combos.size} combos partially worked. " +
                    "Best: ${partial.first().label} (${partial.first().passedCount}/${partial.first().totalCount} probes)"
                else ->
                    "0 of ${combos.size} combos resolved any reference probe -- " +
                    "the URLs may be wrong, or the corp proxy / firewall is rejecting all paths."
            }),
            "combos" to combos.map { c ->
                mapOf(
                    "candidateUrl" to c.candidateUrl,
                    "useProxy" to c.useProxy,
                    "insecureSsl" to c.insecureSsl,
                    "useAuth" to c.useAuth,
                    "passedCount" to c.passedCount,
                    "totalCount" to c.totalCount,
                    "score" to c.score,
                    "label" to c.label,
                    "probedArtifacts" to c.probedArtifacts.map { a ->
                        mapOf(
                            "label" to a.label,
                            "ok" to a.ok,
                            "statusCode" to a.statusCode,
                            "durationMs" to a.durationMs,
                            "message" to a.message
                        )
                    }
                )
            }
        )
    }

    /**
     * Scan the operator's local profile for Artifactory URLs the
     * machine is already aware of: ~/.gradle/gradle.properties,
     * ~/.gradle/init.d/ scripts, ~/.m2/settings.xml. Returns
     * deduped URL list + per-source breakdown, so the discovery
     * wizard can pre-populate its candidates textarea with values
     * the operator's IT setup likely already endorses.
     */
    @PostMapping("/mirror/discover/scan-local")
    @org.springframework.web.bind.annotation.ResponseBody
    fun discoverScanLocal(): Map<String, Any?> {
        val scan = depValidator.scanLocalProfile()
        return mapOf(
            "ok" to true,
            "urls" to scan.urls,
            "sources" to scan.sources,
            "notes" to scan.notes,
            "summary" to (if (scan.urls.isEmpty())
                "No Artifactory-shaped URLs found in ~/.gradle/{gradle.properties, init.d/} or ~/.m2/settings.xml."
            else
                "Found ${scan.urls.size} candidate URL(s) across " +
                "${scan.sources.size} local profile file(s).")
        )
    }

    /**
     * Apply a chosen discovery combo to ConnectionSettings: sets
     * mirrorUrl + insecureSsl from the combo. Doesn't modify
     * proxy fields (those are managed on /proxy and the operator
     * can toggle "Bypass mirror" / "Maven Central via mirror only"
     * separately afterwards). Reloads the page on success.
     */
    @PostMapping("/mirror/discover/apply")
    @org.springframework.web.bind.annotation.ResponseBody
    fun applyDiscoveryCombo(
        @RequestParam candidateUrl: String,
        @RequestParam(defaultValue = "false") insecureSsl: Boolean,
        session: HttpSession
    ): Map<String, Any?> {
        val s = connectionSettings.settings
        val updated = s.copy(
            mirrorUrl = candidateUrl.trim(),
            insecureSsl = insecureSsl
        )
        connectionSettings.update(updated)
        stopBankingAppDaemon()
        val parts = mutableListOf<String>()
        parts += "Mirror URL set to $candidateUrl."
        if (insecureSsl != s.insecureSsl) {
            parts += "TLS verification: ${if (insecureSsl) "DISABLED" else "ENABLED"}."
        }
        parts += "Banking-app gradle daemon stopped so next build picks up the change."
        return mapOf(
            "ok" to true,
            "message" to parts.joinToString(" ")
        )
    }

    @PostMapping("/mirror/save")
    fun save(
        @RequestParam(required = false, defaultValue = "") mirrorUrl: String,
        @RequestParam(required = false, defaultValue = "") mirrorAuthUser: String,
        @RequestParam(required = false, defaultValue = "") mirrorAuthPassword: String,
        @RequestParam(required = false, defaultValue = "false") bypassMirror: Boolean,
        @RequestParam(required = false, defaultValue = "") artifactoryRepoKey: String,
        @RequestParam(required = false, defaultValue = "false") centralViaMirrorOnly: Boolean,
        @RequestParam(required = false, defaultValue = "") mavenExternalVirtualUrl: String,
        @RequestParam(required = false, defaultValue = "false") mirrorBypassProxy: Boolean,
        session: HttpSession
    ): String {
        val existing = connectionSettings.settings
        val keepPw = mirrorAuthPassword.isBlank() && existing.mirrorAuthPassword.isNotBlank()
        connectionSettings.update(
            existing.copy(
                mirrorUrl = mirrorUrl.trim(),
                mirrorAuthUser = mirrorAuthUser.trim(),
                mirrorAuthPassword = if (keepPw) existing.mirrorAuthPassword else mirrorAuthPassword,
                bypassMirror = bypassMirror,
                artifactoryRepoKey = artifactoryRepoKey.trim(),
                centralViaMirrorOnly = centralViaMirrorOnly,
                mavenExternalVirtualUrl = mavenExternalVirtualUrl.trim(),
                mirrorBypassProxy = mirrorBypassProxy
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

    /**
     * Diagnostic: probe the three signals that distinguish the four
     * Maven-Central resolution patterns, then return a structured
     * recommendation the /mirror UI can render as a one-click apply.
     *
     * The classic enterprise failure mode is "mirror works fine +
     * direct repo.maven.apache.org returns 403 from the corp proxy",
     * which means corp policy mandates Central content via the
     * mirror only. Operators don't always know that's the design --
     * they just see the verify panel turn ✗ on the Central row and
     * assume something is broken. This endpoint detects that pattern
     * and proposes the centralViaMirrorOnly toggle.
     */
    @PostMapping("/mirror/recommend-config")
    @org.springframework.web.bind.annotation.ResponseBody
    fun recommendConfig(): Map<String, Any?> {
        val s = connectionSettings.settings
        val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(8))

        // Signal 1: is the mirror itself reachable + serving content?
        val mirrorOk = s.hasMirror && runCatching {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(s.mirrorUrl.trimEnd('/') + "/"))
                .timeout(java.time.Duration.ofSeconds(6))
                .GET().build()
            val withAuth = if (s.hasMirrorAuth) {
                java.net.http.HttpRequest.newBuilder(req, { _, _ -> true })
                    .header("Authorization", "Basic " +
                        java.util.Base64.getEncoder().encodeToString(
                            "${s.mirrorAuthUser}:${s.mirrorAuthPassword}".toByteArray()))
                    .build()
            } else req
            client.send(withAuth, java.net.http.HttpResponse.BodyHandlers.discarding())
                .statusCode() in 200..399
        }.getOrDefault(false)

        // Signal 2: is the corp proxy blocking direct Maven Central?
        // 403 from the proxy is the canonical signal for the pattern
        // we're detecting; 200 means direct egress works, in which
        // case there's nothing to recommend.
        val centralDirectStatus = runCatching {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://repo.maven.apache.org/maven2/"))
                .timeout(java.time.Duration.ofSeconds(6))
                .GET().build()
            client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
        }.getOrDefault(-1)
        val centralBlockedDirect = centralDirectStatus == 403 ||
                                    centralDirectStatus == 407 ||
                                    centralDirectStatus == 502

        // Signal 3: does the mirror (or maven-external-virtual when
        // set) actually serve Central content? Probe a Spring Core
        // POM that banking-app needs. Prefers the external virtual
        // since that's the canonical Central path in two-virtual
        // setups; falls back to the main mirror in single-virtual
        // setups.
        val centralProbeBase = (if (s.hasMavenExternalVirtual)
            s.mavenExternalVirtualUrl else s.mirrorUrl).trimEnd('/')
        val centralProbeSource = if (s.hasMavenExternalVirtual) "maven-external-virtual"
                                 else "mirror"
        val centralViaMirrorOk = s.hasMirror && runCatching {
            val url = "$centralProbeBase/org/springframework/spring-core/" +
                "6.1.13/spring-core-6.1.13.pom"
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(6))
                .GET().build()
            val withAuth = if (s.hasMirrorAuth) {
                java.net.http.HttpRequest.newBuilder(req, { _, _ -> true })
                    .header("Authorization", "Basic " +
                        java.util.Base64.getEncoder().encodeToString(
                            "${s.mirrorAuthUser}:${s.mirrorAuthPassword}".toByteArray()))
                    .build()
            } else req
            client.send(withAuth, java.net.http.HttpResponse.BodyHandlers.discarding())
                .statusCode() in 200..299
        }.getOrDefault(false)

        // Pattern matching → recommendation.
        val recommendation: String?
        val applyToggle: String?
        when {
            !s.hasMirror -> {
                recommendation = "No mirror configured. Configure a mirror first " +
                    "if your corp environment requires one."
                applyToggle = null
            }
            !mirrorOk -> {
                recommendation = "Mirror URL is unreachable. Fix the mirror URL or " +
                    "credentials before changing resolution mode."
                applyToggle = null
            }
            centralBlockedDirect && centralViaMirrorOk && !s.centralViaMirrorOnly -> {
                recommendation = "Detected: corp proxy returned HTTP $centralDirectStatus " +
                    "for direct repo.maven.apache.org, but the mirror IS serving Central " +
                    "content. Recommended: enable \"Maven Central via mirror only\" so " +
                    "the verify panel + banking-app build skip the blocked upstream."
                applyToggle = "centralViaMirrorOnly"
            }
            centralBlockedDirect && !centralViaMirrorOk -> {
                recommendation = "Corp proxy blocks direct Central (HTTP $centralDirectStatus) " +
                    "but the mirror does NOT serve Central content either. The mirror needs " +
                    "to be reconfigured to proxy Maven Central before banking-app can build."
                applyToggle = null
            }
            !centralBlockedDirect && s.centralViaMirrorOnly -> {
                recommendation = "Direct repo.maven.apache.org is reachable (HTTP " +
                    "$centralDirectStatus). \"Maven Central via mirror only\" is on but not " +
                    "required -- builds would still work either way. Safe to leave as-is " +
                    "or toggle off if you'd prefer the mirror+fallback configuration."
                applyToggle = null
            }
            else -> {
                recommendation = "Current resolution mode (\"${s.resolutionMode}\") matches " +
                    "the detected network: mirror reachable=$mirrorOk, " +
                    "direct Central=HTTP $centralDirectStatus, " +
                    "mirror serves Central=$centralViaMirrorOk. No change recommended."
                applyToggle = null
            }
        }

        return mapOf(
            "ok" to true,
            "currentMode" to s.resolutionMode,
            "mirrorOk" to mirrorOk,
            "centralDirectStatus" to centralDirectStatus,
            "centralBlockedDirect" to centralBlockedDirect,
            "centralViaMirrorOk" to centralViaMirrorOk,
            "centralProbeSource" to centralProbeSource,
            "centralViaMirrorOnly" to s.centralViaMirrorOnly,
            "hasMavenExternalVirtual" to s.hasMavenExternalVirtual,
            "recommendation" to recommendation,
            "applyToggle" to applyToggle
        )
    }

    /**
     * One-click apply for a recommended toggle. Currently only
     * supports `centralViaMirrorOnly`; structured so future
     * recommendations (auto-fill mirror URL, swap to bypass-mirror,
     * etc.) can extend the same endpoint without a new route.
     */
    @PostMapping("/mirror/apply-recommendation")
    @org.springframework.web.bind.annotation.ResponseBody
    fun applyRecommendation(
        @RequestParam toggle: String,
        session: HttpSession
    ): Map<String, Any?> {
        val s = connectionSettings.settings
        val updated = when (toggle) {
            "centralViaMirrorOnly" -> s.copy(centralViaMirrorOnly = true)
            else -> return mapOf("ok" to false,
                "reason" to "Unknown toggle '$toggle'. " +
                    "Currently supported: centralViaMirrorOnly.")
        }
        connectionSettings.update(updated)
        stopBankingAppDaemon()
        return mapOf(
            "ok" to true,
            "newMode" to updated.resolutionMode,
            "message" to "Toggle '$toggle' applied. New resolution mode: " +
                "${updated.resolutionMode}. Banking-app gradle daemon stopped " +
                "so the next build picks up the change."
        )
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
