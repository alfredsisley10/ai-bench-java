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
