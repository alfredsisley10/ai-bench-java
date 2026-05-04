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
        return try {
            val (probeUrl, viaMirror) = resolveProbeUrl(entry)
            val client = httpClient()
            val req = HttpRequest.newBuilder()
                .uri(URI.create(probeUrl))
                .timeout(Duration.ofSeconds(8))
                // HEAD: cheap, no body transfer, but some Maven repos
                // (Artifactory in particular) return 405 on HEAD even
                // when GET would 200 -- in that case we retry with GET
                // and rely on the response code.
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            // Add Basic auth header if mirror auth is configured.
            // System-property based auth (-Dhttp.proxyPassword) is for
            // the proxy itself; mirror credentials need an explicit
            // Authorization header on each request.
            val s = connectionSettings.settings
            val withAuth = if (viaMirror && s.hasMirrorAuth) {
                HttpRequest.newBuilder(req, { _, _ -> true })
                    .header("Authorization", "Basic " +
                        java.util.Base64.getEncoder().encodeToString(
                            "${s.mirrorAuthUser}:${s.mirrorAuthPassword}".toByteArray()
                        ))
                    .build()
            } else req
            var resp = client.send(withAuth, HttpResponse.BodyHandlers.discarding())
            // Retry HEAD-rejecters with GET. Some Artifactory configs
            // return 405 Method Not Allowed for HEAD on .pom files.
            if (resp.statusCode() == 405) {
                val getReq = HttpRequest.newBuilder(withAuth, { _, _ -> true })
                    .GET().build()
                resp = client.send(getReq, HttpResponse.BodyHandlers.discarding())
            }
            val ms = System.currentTimeMillis() - started
            val code = resp.statusCode()
            val ok = code in 200..299
            val message = when {
                ok -> "OK"
                code == 401 -> "401 Unauthorized -- mirror auth missing or wrong token"
                code == 403 -> "403 Forbidden -- account lacks read on this repo"
                code == 404 -> if (viaMirror)
                    "404 Not Found via mirror -- mirror may not carry this artifact group"
                else "404 Not Found -- coord may not exist or version is wrong"
                code in 500..599 -> "$code from upstream"
                else -> "HTTP $code"
            }
            Result(entry, probeUrl, viaProxy = s.httpsProxy.isNotBlank(),
                viaMirror = viaMirror, statusCode = code, durationMs = ms, ok = ok,
                message = message)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - started
            log.debug("validator probe failed for {}: {}", entry.coord, e.message)
            Result(entry, probeUrl = entry.coord,
                viaProxy = connectionSettings.settings.httpsProxy.isNotBlank(),
                viaMirror = false, statusCode = -1, durationMs = ms, ok = false,
                message = "${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
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
