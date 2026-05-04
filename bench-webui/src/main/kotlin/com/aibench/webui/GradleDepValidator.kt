package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Probes each [GradleDepCatalog.Entry] for resolvability through the
 * operator's currently-saved proxy + mirror. Used by the new "Verify
 * dependencies" panel on /mirror so the operator can confirm BEFORE
 * deploying that every coordinate the build actually needs will
 * resolve.
 *
 * Strategy:
 *   - Maven coords -> issue HEAD against the mirror's
 *     <mirrorUrl>/<pomPath>. When no mirror is configured, falls back
 *     to repo.maven.apache.org for spring/jackson/etc. and
 *     plugins.gradle.org/m2 for plugin markers. If both routes are
 *     blocked the row goes red.
 *   - URL coords (foojay, services.gradle.org) -> HEAD against the
 *     URL directly. Honors the proxy from ConnectionSettings.
 *
 * Validation is cheap (HEAD only, ~200ms each behind a mirror) and
 * runs in a small thread pool so a 30-coord sweep finishes in ~3s
 * instead of 30s sequentially.
 */
@Component
class GradleDepValidator(
    private val catalog: GradleDepCatalog,
    private val connectionSettings: ConnectionSettings
) {
    private val log = LoggerFactory.getLogger(GradleDepValidator::class.java)
    private val pool = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "gradle-dep-validator").apply { isDaemon = true }
    }

    data class Result(
        val entry: GradleDepCatalog.Entry,
        val probeUrl: String,
        val viaProxy: Boolean,
        val viaMirror: Boolean,
        val statusCode: Int,
        val durationMs: Long,
        val ok: Boolean,
        val message: String
    )

    /**
     * Probe every entry whose category is in the supplied filter
     * set. Empty filter => probe all categories. Returns results
     * in catalog order (stable for the UI's grid render).
     */
    fun validate(categories: Set<GradleDepCatalog.Category> = emptySet()): List<Result> {
        val toProbe =
            if (categories.isEmpty()) catalog.entries
            else catalog.entries.filter { it.category in categories }
        val futures = toProbe.map { entry ->
            pool.submit<Result> { probeOne(entry) }
        }
        return futures.map { it.get() }
    }

    /**
     * Single attempt of a single connectivity option for one
     * coordinate. Surfaces enough detail (label, URL, ok, status,
     * message) for the /mirror UI to render a per-row breakdown
     * and for the LLM to pattern-match across multiple coords.
     */
    data class AttemptResult(
        val label: String,
        val probeUrl: String,
        val viaProxy: Boolean,
        val ok: Boolean,
        val statusCode: Int,
        val durationMs: Long,
        val message: String
    )

    /**
     * Per-entry result of trying every applicable connectivity
     * option. The recommendation summarizes which path(s) worked,
     * so the operator can pick the right routing for that dep
     * (e.g. "succeeded via maven-external-virtual" → suggests
     * configuring that as the plugin source).
     */
    data class AlternativesResult(
        val coord: String,
        val category: GradleDepCatalog.Category,
        val attempts: List<AttemptResult>,
        val recommendation: String
    )

    /**
     * Iterate the list of (already-failed) coords through every
     * connectivity option the operator has configured: direct via
     * proxy / direct without proxy, via mirror, via maven-external-
     * virtual, and (for gradle plugins) plugins.gradle.org direct
     * + the marker→maven-coord substitution that the corp init
     * script's resolutionStrategy.eachPlugin would do at build time.
     *
     * For each coord, returns one AttemptResult per option tried
     * + a one-sentence recommendation summarising what worked.
     * Used by the /mirror "🔄 Retry failed with alternative paths"
     * button -- catches the case where the primary probe path
     * (e.g. plugins.gradle.org direct) hits HttpTimeoutException
     * but the same artifact resolves cleanly via the mirror.
     */
    fun probeAlternatives(failedCoords: List<String>): List<AlternativesResult> {
        val s = connectionSettings.settings
        val client = httpClient()
        return failedCoords.mapNotNull { coord ->
            val entry = catalog.entries.firstOrNull { it.coord == coord } ?: return@mapNotNull null
            val attempts = mutableListOf<AttemptResult>()
            val targets = buildAlternativeTargets(entry, s)
            for ((label, url, useAuth) in targets) {
                attempts += attemptOne(client, label, url, useAuth, s)
            }
            val winners = attempts.filter { it.ok }
            val recommendation = when {
                winners.isEmpty() -> "✗ No configured connectivity option resolves this coord. " +
                    "Likely either the artifact genuinely doesn't exist at any reachable repo " +
                    "OR a deeper network issue (corp proxy reset, DNS, expired cert)."
                winners.size == 1 -> "✓ Resolves via \"${winners[0].label}\". " +
                    "If your saved /mirror config doesn't already route through this path, " +
                    "consider switching."
                else -> "✓ Resolves via ${winners.size} of ${attempts.size} options: " +
                    winners.joinToString(", ") { "\"${it.label}\"" } +
                    ". Pick the one matching your corp policy."
            }
            AlternativesResult(coord, entry.category, attempts, recommendation)
        }
    }

    /** Build the ordered list of (label, url, useMirrorAuth) tuples
     *  to probe for one coordinate. Order matters for the UI -- the
     *  most policy-relevant path goes first. */
    private fun buildAlternativeTargets(
        entry: GradleDepCatalog.Entry, s: ConnectionSettings.Settings
    ): List<Triple<String, String, Boolean>> {
        if (entry.isUrl) {
            // URL probes (foojay, services.gradle.org). The only
            // alternative is "with proxy off via no_proxy" -- the
            // proxy itself is the variable. Cheap so still worth
            // trying.
            return listOf(Triple("Direct (current proxy/TLS settings)", entry.coord, false))
        }
        val targets = mutableListOf<Triple<String, String, Boolean>>()
        // 1. Via primary mirror, with auth.
        if (s.hasMirror) {
            targets += Triple("Primary mirror (${s.mirrorUrl})",
                "${s.mirrorUrl.trimEnd('/')}/${entry.pomPath}", s.hasMirrorAuth)
        }
        // 2. Via maven-external-virtual when configured.
        if (s.hasMavenExternalVirtual) {
            targets += Triple("maven-external-virtual (${s.mavenExternalVirtualUrl})",
                "${s.mavenExternalVirtualUrl.trimEnd('/')}/${entry.pomPath}", s.hasMirrorAuth)
        }
        // 3. Direct public default. For plugins this is the Gradle
        //    plugin portal; for everything else it's Maven Central.
        val publicDefault = if (entry.category == GradleDepCatalog.Category.GRADLE_PLUGIN)
            "https://plugins.gradle.org/m2"
        else
            "https://repo.maven.apache.org/maven2"
        targets += Triple("Direct (${publicDefault})",
            "$publicDefault/${entry.pomPath}", false)
        // 4. For gradle plugins specifically: also try the marker→
        //    maven-coord substitution that the corp init script's
        //    resolutionStrategy.eachPlugin does. The plugin marker
        //    `org.springframework.boot:org.springframework.boot.gradle.plugin`
        //    rewrites to `org.springframework.boot:spring-boot-gradle-plugin`
        //    at build time, so probing the rewrite-target on the mirror
        //    catches the case where the marker fails but the actual
        //    plugin jar resolves fine.
        if (entry.category == GradleDepCatalog.Category.GRADLE_PLUGIN && s.hasMirror) {
            pluginMarkerRewrite(entry.coord)?.let { rewriteCoord ->
                val rewrittenPath = mavenPomPathFromCoord(rewriteCoord) ?: return@let
                targets += Triple(
                    "Mirror, plugin-marker rewritten to actual jar coord ($rewriteCoord)",
                    "${s.mirrorUrl.trimEnd('/')}/$rewrittenPath", s.hasMirrorAuth
                )
            }
        }
        return targets
    }

    /** Mirror of the corp-repos init script's pluginIdToCoord map.
     *  Rewrites a "plugin marker" coord (groupId:groupId.gradle.plugin)
     *  to the actual maven coord that hosts the plugin jar. Returns
     *  null when the entry isn't a recognised plugin marker. */
    private fun pluginMarkerRewrite(markerCoord: String): String? {
        val parts = markerCoord.split(":")
        if (parts.size < 3) return null
        val (g, a, v) = Triple(parts[0], parts[1], parts[2])
        if (!a.endsWith(".gradle.plugin")) return null
        val pluginId = a.removeSuffix(".gradle.plugin")
        // Same mappings as scripts/corp-repos.gradle.kts.template's pluginIdToCoord.
        val mapping = when (pluginId) {
            "org.springframework.boot" ->
                "org.springframework.boot:spring-boot-gradle-plugin"
            "io.spring.dependency-management" ->
                "io.spring.gradle:dependency-management-plugin"
            "org.jetbrains.kotlin.jvm" ->
                "org.jetbrains.kotlin:kotlin-gradle-plugin"
            "org.jetbrains.kotlin.plugin.spring" ->
                "org.jetbrains.kotlin:kotlin-allopen"
            "org.jetbrains.kotlin.plugin.serialization" ->
                "org.jetbrains.kotlin:kotlin-serialization"
            "org.jetbrains.kotlin.plugin.jpa" ->
                "org.jetbrains.kotlin:kotlin-noarg"
            "com.appland.appmap" ->
                "com.appland:appmap-gradle-plugin"
            "org.gradle.toolchains.foojay-resolver-convention" ->
                "org.gradle.toolchains:foojay-resolver"
            else -> return null
        }
        return "$mapping:$v"
    }

    private fun mavenPomPathFromCoord(coord: String): String? {
        val parts = coord.split(":")
        if (parts.size < 3) return null
        val (g, a, v) = Triple(parts[0], parts[1], parts[2])
        return g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v + ".pom"
    }

    /**
     * Single combination of (Artifactory URL, useProxy, insecureSsl,
     * useAuth) tried during discovery, plus per-reference-artifact
     * probe results. Surfaces enough detail for the /mirror UI to
     * render a ranked grid + for the operator to inspect WHY a combo
     * failed (which specific reference artifact missed).
     */
    data class DiscoveryCombo(
        val candidateUrl: String,
        val useProxy: Boolean,
        val insecureSsl: Boolean,
        val useAuth: Boolean,
        val probedArtifacts: List<AttemptResult>,
        val passedCount: Int,
        val totalCount: Int,
        val score: Int,        // higher = better (#passed * 100 - #attempts*ms penalty)
        val label: String      // human-readable summary for the UI
    )

    /**
     * "Discovery wizard" probe: take a list of candidate Artifactory
     * URLs the operator's IT team gave them and try every meaningful
     * combination of (URL, proxy on/off, TLS-secure/insecure, auth
     * on/off) against a small reference set of well-known artifacts
     * across categories (gradle plugin, spring boot starter, test-
     * containers, foojay JDK toolchain). Ranks combos by passed/
     * total + a small latency penalty so a fast all-pass wins over
     * a slow all-pass. The /mirror UI offers a one-click "save best"
     * action that applies the winning combo to ConnectionSettings.
     *
     * Limits: probes ~4 reference artifacts per combo, so up to
     * (URLs × 8 combos × 4 probes) HEAD requests. With 2 candidate
     * URLs that's 64 probes -- runs ~5s in the parallel pool.
     */
    fun discoverConfig(candidateUrls: List<String>): List<DiscoveryCombo> {
        val s = connectionSettings.settings
        // Reference artifacts to probe per combo. Picked to span the
        // categories that typically resolve through different
        // upstreams in a corp Artifactory virtual:
        //   - Spring Boot gradle plugin marker (plugins.gradle.org content)
        //   - Spring Boot core starter POM (Maven Central content)
        //   - Testcontainers (the canonical "is the external proxy
        //     working?" canary -- testcontainers + datafaker are the
        //     most common Windows/corp-mirror failure category)
        val references = listOf(
            "org.springframework.boot/org.springframework.boot.gradle.plugin/3.5.14/" +
                "org.springframework.boot.gradle.plugin-3.5.14.pom",
            "org/springframework/boot/spring-boot-starter/3.5.14/spring-boot-starter-3.5.14.pom",
            "org/testcontainers/testcontainers/1.20.1/testcontainers-1.20.1.pom",
            "org/testcontainers/junit-jupiter/1.20.1/junit-jupiter-1.20.1.pom"
        )
        val combos = mutableListOf<DiscoveryCombo>()
        for (rawUrl in candidateUrls) {
            val cleanUrl = rawUrl.trim().trimEnd('/')
            if (cleanUrl.isBlank()) continue
            // Generate combo matrix. Skip impossible combos (e.g.
            // useAuth=true when no auth saved on Settings).
            val authOptions = if (s.hasMirrorAuth) listOf(true, false) else listOf(false)
            val proxyOptions = if (s.httpsProxy.isNotBlank() || s.httpProxy.isNotBlank())
                listOf(true, false) else listOf(false)
            val tlsOptions = if (s.insecureSsl) listOf(true) else listOf(false, true)
            for (useAuth in authOptions) {
                for (useProxy in proxyOptions) {
                    for (insecureSsl in tlsOptions) {
                        val client = comboHttpClient(useProxy, insecureSsl)
                        val attempts: List<AttemptResult> = references.map { ref ->
                            val refUrl = "$cleanUrl/$ref"
                            val syntheticSettings = if (useAuth) s
                                else s.copy(mirrorAuthUser = "", mirrorAuthPassword = "")
                            attemptOne(client, ref, refUrl, useAuth, syntheticSettings)
                        }
                        val passed = attempts.count { it.ok }
                        val total = attempts.size
                        val avgMs = if (attempts.isNotEmpty())
                            attempts.sumOf { it.durationMs } / attempts.size else 0L
                        combos += DiscoveryCombo(
                            candidateUrl = cleanUrl,
                            useProxy = useProxy,
                            insecureSsl = insecureSsl,
                            useAuth = useAuth,
                            probedArtifacts = attempts,
                            passedCount = passed,
                            totalCount = total,
                            score = passed * 1000 - avgMs.toInt(),
                            label = buildString {
                                append("URL=").append(cleanUrl)
                                append(", proxy=").append(if (useProxy) "on" else "off")
                                append(", TLS=").append(if (insecureSsl) "insecure" else "secure")
                                append(", auth=").append(if (useAuth) "on" else "off")
                            }
                        )
                    }
                }
            }
        }
        return combos.sortedByDescending { it.score }
    }

    /** Build a one-off HttpClient with the specified proxy + TLS
     *  toggles overriding the saved Settings. Used by discoverConfig
     *  to vary those axes without mutating ConnectionSettings.
     *  followRedirects=NORMAL stays consistent with the central
     *  client (PR #27). */
    private fun comboHttpClient(useProxy: Boolean, insecureSsl: Boolean): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
        if (useProxy) {
            // Reuse the central proxy selector; if the caller wants
            // proxy off, we just don't attach it.
            connectionSettings.httpClient(Duration.ofSeconds(6)).also { /* warm side-effects */ }
            // Pull the parsed proxy host:port from Settings via a
            // throwaway client built by ConnectionSettings -- it's
            // the only place that owns proxy parsing.
            val central = connectionSettings.httpClient(Duration.ofSeconds(6))
            // central already has the proxy attached. Use it
            // directly to avoid re-implementing parseHostPort here.
            return central
        }
        if (insecureSsl) {
            builder.sslContext(makeTrustAllCtx())
            val params = javax.net.ssl.SSLParameters()
            params.endpointIdentificationAlgorithm = ""
            builder.sslParameters(params)
        }
        return builder.build()
    }

    /**
     * Surface candidate Artifactory URLs the operator's local
     * machine already mentions. Sources scanned:
     *   - ~/.gradle/gradle.properties (artifactory_contextUrl,
     *     other systemProp.maven.* style keys)
     *   - ~/.gradle/init.d/ scripts (.gradle.kts and .gradle): regex for
     *     `maven { url = uri("...") }` and Groovy-form variants
     *   - ~/.m2/settings.xml (<mirror><url>, <repository><url>,
     *     <server> for auth references)
     * Returns deduped, sorted unique URLs the operator can pre-fill
     * into the discovery wizard's candidate textarea.
     *
     * Best-effort: missing files / parse failures silently skip
     * that source; the per-source errors are returned alongside the
     * URLs so the UI can show "couldn't parse settings.xml: …"
     * when the operator wonders why an expected URL isn't here.
     */
    data class LocalProfileScan(
        val urls: List<String>,                  // deduped candidate URLs
        val sources: Map<String, List<String>>,  // path -> URLs found there
        val notes: List<String>                  // best-effort errors
    )

    fun scanLocalProfile(): LocalProfileScan {
        val home = java.io.File(System.getProperty("user.home"))
        val sources = mutableMapOf<String, MutableList<String>>()
        val notes = mutableListOf<String>()

        // ~/.gradle/gradle.properties
        val gradleProps = home.resolve(".gradle/gradle.properties")
        if (gradleProps.isFile) runCatching {
            val urls = mutableListOf<String>()
            gradleProps.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || !trimmed.contains("=")) return@forEach
                val (k, v) = trimmed.split("=", limit = 2).map { it.trim() }
                // Match any property whose value looks like an
                // Artifactory virtual URL. Catches both the keys we
                // manage (artifactory_contextUrl) AND ad-hoc keys
                // the operator may have hand-set.
                if (looksLikeArtifactoryUrl(v)) {
                    urls += v
                    sources.getOrPut("~/.gradle/gradle.properties") { mutableListOf() }
                        .add("$k=$v")
                }
            }
        }.onFailure { notes += "couldn't read ~/.gradle/gradle.properties: ${it.message}" }

        // ~/.gradle/init.d/*.gradle.kts + *.gradle
        val initDir = home.resolve(".gradle/init.d")
        if (initDir.isDirectory) {
            initDir.listFiles { f -> f.isFile &&
                (f.name.endsWith(".gradle.kts") || f.name.endsWith(".gradle")) }
                ?.forEach { f -> runCatching {
                    val text = f.readText()
                    // Match maven { url = uri("...") } (kts) and
                    // maven { url '...' } / maven { url "..." } (groovy)
                    val patterns = listOf(
                        Regex("""url\s*=\s*uri\(\s*"([^"]+)"\s*\)"""),
                        Regex("""url\s+["']([^"']+)["']"""),
                        Regex("""val\s+\w+\s*=\s*"(https?://[^"]+)""""),
                    )
                    val found = patterns.flatMap { it.findAll(text).map { m -> m.groupValues[1] } }
                        .filter { looksLikeArtifactoryUrl(it) }
                        .distinct()
                    if (found.isNotEmpty()) {
                        sources["~/.gradle/init.d/${f.name}"] = found.toMutableList()
                    }
                }.onFailure { notes += "couldn't parse ~/.gradle/init.d/${f.name}: ${it.message}" } }
        }

        // ~/.m2/settings.xml
        val m2Settings = home.resolve(".m2/settings.xml")
        if (m2Settings.isFile) runCatching {
            val text = m2Settings.readText()
            // Regex over the XML rather than DOM-parse: settings.xml
            // is small + simple, regex is dependency-free, and we
            // only need <url>…</url> values for discovery purposes.
            val urls = Regex("""<url>\s*([^<\s][^<]*?)\s*</url>""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.groupValues[1].trim() }
                .filter { looksLikeArtifactoryUrl(it) }
                .distinct()
                .toList()
            if (urls.isNotEmpty()) {
                sources["~/.m2/settings.xml"] = urls.toMutableList()
            }
        }.onFailure { notes += "couldn't parse ~/.m2/settings.xml: ${it.message}" }

        // Deduplicate across all sources, preserving order of first appearance.
        val deduped = sources.values.flatten()
            .map { it.substringAfter("=").trim() }  // strip "key=" prefix from gradle.properties entries
            .distinct()
            .sorted()
        return LocalProfileScan(deduped, sources, notes)
    }

    /** Heuristic: does this string look like an Artifactory / Nexus
     *  / general Maven repo URL we'd want to probe? Filters out
     *  http://localhost, file://, plugins.gradle.org (already a
     *  known default), maven.apache.org (ditto). */
    private fun looksLikeArtifactoryUrl(s: String): Boolean {
        if (s.length < 12) return false
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return false
        val lower = s.lowercase()
        if (lower.contains("localhost") || lower.contains("127.0.0.1")) return false
        if (lower.contains("plugins.gradle.org")) return false
        if (lower.contains("repo.maven.apache.org") ||
            lower.contains("repo1.maven.org")) return false
        if (lower.contains("repo.spring.io") ||
            lower.contains("oss.sonatype.org")) return false
        return true
    }

    private fun makeTrustAllCtx(): javax.net.ssl.SSLContext {
        val trustAll = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
        return ctx
    }

    private fun attemptOne(
        client: HttpClient, label: String, url: String,
        useMirrorAuth: Boolean, s: ConnectionSettings.Settings
    ): AttemptResult {
        val started = System.currentTimeMillis()
        return try {
            val (ok, code) = singleProbe(client, url, useMirrorAuth, s)
            val ms = System.currentTimeMillis() - started
            val message = when {
                ok -> "OK (HTTP $code)"
                code == 401 -> "401 Unauthorized"
                code == 403 -> "403 Forbidden (proxy or upstream refused)"
                code == 404 -> "404 Not Found (no such coord at this repo)"
                code in 500..599 -> "$code from upstream"
                code == -1 -> "(no response)"
                else -> "HTTP $code"
            }
            AttemptResult(label, url, viaProxy = s.httpsProxy.isNotBlank(),
                ok = ok, statusCode = code, durationMs = ms, message = message)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - started
            AttemptResult(label, url, viaProxy = s.httpsProxy.isNotBlank(),
                ok = false, statusCode = -1, durationMs = ms,
                message = connectionSettings.formatProbeException(e))
        }
    }

    private fun probeOne(entry: GradleDepCatalog.Entry): Result {
        val started = System.currentTimeMillis()
        val s = connectionSettings.settings
        return try {
            val (pomUrl, viaMirror) = resolveProbeUrl(entry)
            val client = httpClient()

            // First: probe the POM (existing behaviour). If that
            // already fails, the artifact is unresolvable -- short-
            // circuit without a JAR probe.
            val pomResult = singleProbe(client, pomUrl, viaMirror, s)
            if (!pomResult.first) {
                val ms = System.currentTimeMillis() - started
                return Result(entry, pomUrl,
                    viaProxy = s.httpsProxy.isNotBlank(), viaMirror = viaMirror,
                    statusCode = pomResult.second, durationMs = ms, ok = false,
                    message = pomMessage(pomResult.second, viaMirror))
            }

            // Second: probe the companion JAR. Skipped when
            // entry.jarPath is null (BOMs / URL probes don't have one
            // -- their POM IS the artifact). The user-facing failure
            // mode this catches is the testcontainers junit-jupiter
            // case where the corp Artifactory virtual serves POMs
            // from one upstream but routes JARs through a different
            // (broken / unauthenticated) one -- POM-only validation
            // green-lit, real build fails on junit-jupiter-1.20.1.jar.
            val jarPath = entry.jarPath
            if (jarPath == null) {
                val ms = System.currentTimeMillis() - started
                return Result(entry, pomUrl,
                    viaProxy = s.httpsProxy.isNotBlank(), viaMirror = viaMirror,
                    statusCode = pomResult.second, durationMs = ms, ok = true,
                    message = "POM OK (BOM/POM-only — no companion .jar)")
            }
            val jarUrl = pomUrl.removeSuffix(".pom") + ".jar"
            val jarResult = singleProbe(client, jarUrl, viaMirror, s)
            val ms = System.currentTimeMillis() - started

            if (jarResult.first) {
                Result(entry, pomUrl, viaProxy = s.httpsProxy.isNotBlank(),
                    viaMirror = viaMirror, statusCode = pomResult.second,
                    durationMs = ms, ok = true,
                    message = "POM + JAR resolve")
            } else {
                Result(entry, jarUrl, viaProxy = s.httpsProxy.isNotBlank(),
                    viaMirror = viaMirror, statusCode = jarResult.second,
                    durationMs = ms, ok = false,
                    message = "POM resolves but JAR does NOT (gradle build " +
                        "would fail on this dep). " + jarMessage(jarResult.second, viaMirror))
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - started
            log.debug("validator probe failed for {}: {}", entry.coord, e.message)
            Result(entry, probeUrl = entry.coord,
                viaProxy = s.httpsProxy.isNotBlank(),
                viaMirror = false, statusCode = -1, durationMs = ms, ok = false,
                message = connectionSettings.formatProbeException(e))
        }
    }

    /** Single HEAD-then-fall-back-to-GET probe. Returns
     *  (ok, statusCode). Extracted so probeOne can do both POM and
     *  JAR through the same code path without duplicating the auth /
     *  405-fallback logic. */
    private fun singleProbe(
        client: HttpClient, url: String, viaMirror: Boolean,
        s: ConnectionSettings.Settings
    ): Pair<Boolean, Int> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val withAuth = if (viaMirror && s.hasMirrorAuth) {
            HttpRequest.newBuilder(req, { _, _ -> true })
                .header("Authorization", "Basic " +
                    java.util.Base64.getEncoder().encodeToString(
                        "${s.mirrorAuthUser}:${s.mirrorAuthPassword}".toByteArray()
                    ))
                .build()
        } else req
        var resp = client.send(withAuth, HttpResponse.BodyHandlers.discarding())
        // Some Artifactory configs return 405 Method Not Allowed for
        // HEAD on .pom / .jar files; retry with GET.
        if (resp.statusCode() == 405) {
            val getReq = HttpRequest.newBuilder(withAuth, { _, _ -> true })
                .GET().build()
            resp = client.send(getReq, HttpResponse.BodyHandlers.discarding())
        }
        val code = resp.statusCode()
        return (code in 200..299) to code
    }

    private fun pomMessage(code: Int, viaMirror: Boolean): String = when {
        code in 200..299 -> "OK"
        code == 401 -> "401 Unauthorized -- mirror auth missing or wrong token"
        code == 403 -> "403 Forbidden -- account lacks read on this repo"
        code == 404 -> if (viaMirror)
            "404 Not Found via mirror -- mirror may not carry this artifact group"
        else "404 Not Found -- coord may not exist or version is wrong"
        code in 500..599 -> "$code from upstream"
        else -> "HTTP $code"
    }

    private fun jarMessage(code: Int, viaMirror: Boolean): String = when {
        code == 401 -> "JAR fetch returned 401 (auth shape differs from POM path)."
        code == 403 -> "JAR fetch returned 403 (mirror routes JARs via a stricter ACL than POMs)."
        code == 404 -> if (viaMirror)
            "JAR fetch returned 404 via mirror — virtual serves POMs but not the JAR for this coord."
        else "JAR fetch returned 404 — JAR may not exist at that coordinate."
        code in 500..599 -> "JAR fetch returned $code from upstream."
        else -> "JAR fetch returned HTTP $code."
    }

    /**
     * Decide WHERE to probe the entry. URL entries probe themselves;
     * Maven entries probe the active mirror first (when configured +
     * not bypassed) else fall back to public defaults.
     */
    private fun resolveProbeUrl(entry: GradleDepCatalog.Entry): Pair<String, Boolean> {
        if (entry.isUrl) return entry.coord to false
        val s = connectionSettings.settings
        val mirrorBase = if (s.mirrorUrl.isNotBlank() && !s.bypassMirror)
            s.mirrorUrl.trimEnd('/')
        else null
        if (mirrorBase != null) {
            return "$mirrorBase/${entry.pomPath}" to true
        }
        // Fall back to public defaults: gradle plugins go to
        // plugins.gradle.org/m2; everything else to Maven Central.
        val publicBase = if (entry.category == GradleDepCatalog.Category.GRADLE_PLUGIN)
            "https://plugins.gradle.org/m2"
        else
            "https://repo.maven.apache.org/maven2"
        return "$publicBase/${entry.pomPath}" to false
    }

    /** HTTP client honoring the configured proxy + TLS settings. Routed
     *  through ConnectionSettings.httpClient() so insecure-SSL +
     *  OS-truststore augmentation apply uniformly with /proxy + /mirror
     *  probes; the previous inline builder skipped both, which surfaced
     *  as SSLHandshakeException for hosts behind corp MITM proxies even
     *  with the operator's "ignore TLS errors" toggle on. */
    private fun httpClient(): HttpClient = connectionSettings.httpClient(Duration.ofSeconds(4))
}
