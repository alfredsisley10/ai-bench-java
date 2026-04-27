package com.aibench.webui

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Centralized outbound-connection settings for the WebUI. Every HTTP
 * client the management site builds (GitHub, JIRA, AppMap agent, banking
 * app health probe, etc.) must go through {@link #httpClient()} so that
 * proxy and TLS-verification preferences configured on the
 * <code>/proxy</code> page are honored uniformly.
 *
 * <p>Settings are in-process singleton state so the choices persist
 * across sessions; they do NOT leak to disk. Auto-detection runs once at
 * startup from the OS environment (or, on macOS, the
 * <code>networksetup</code> output); the {@code /proxy} page then lets
 * the operator override.
 *
 * <p>For Gradle subprocesses spawned by the WebUI, {@link #gradleSystemProps()}
 * returns the <code>-Dhttp.*</code> / <code>-Dhttps.*</code> JVM args that
 * should be appended to the command line so the child process inherits
 * the same settings without requiring a <code>~/.gradle/gradle.properties</code>
 * rewrite.
 */
@Component
class ConnectionSettings {

    private val log = LoggerFactory.getLogger(ConnectionSettings::class.java)

    data class Settings(
        val httpsProxy: String = "",
        val httpProxy: String = "",
        val noProxy: String = "",
        val insecureSsl: Boolean = false,
        val source: String = "none",
        // Optional HTTP-Basic credentials for the corporate proxy. Held
        // in memory only — never written to disk — and applied via
        // `java.net.Authenticator.setDefault()` so JDK HttpClient and
        // HttpURLConnection both pick them up. Empty user disables auth.
        val proxyAuthUser: String = "",
        val proxyAuthPassword: String = "",
        // Optional Artifactory (or other Maven-compatible) mirror URL
        // and credentials. Surfaced as a probe target on /proxy and
        // injected into Gradle subprocesses via -Denterprise.sim.mirror
        // so banking-app's settings.gradle.kts can route plugins +
        // dependencies through it.
        val mirrorUrl: String = "",
        val mirrorAuthUser: String = "",
        val mirrorAuthPassword: String = "",
        // When true, the mirror is preserved in settings (still probeable
        // via the verify panel) but NOT injected into Gradle subprocess
        // args. banking-app then talks directly to plugins.gradle.org /
        // Maven Central through whatever httpsProxy is configured.
        // Useful when the corp Artifactory virtual doesn't proxy
        // plugins.gradle.org content (e.g. foojay-resolver, which lives
        // only at plugins.gradle.org and is not on Maven Central) but
        // the corp proxy DOES allow direct egress to those upstreams.
        val bypassMirror: Boolean = false
    ) {
        /** Convenience flag — true when proxy auth is fully configured. */
        val hasProxyAuth: Boolean get() = proxyAuthUser.isNotBlank() && proxyAuthPassword.isNotBlank()
        /** Convenience flag — true when a mirror URL is set. */
        val hasMirror: Boolean get() = mirrorUrl.isNotBlank()
        /** Convenience flag — true when mirror auth is configured. */
        val hasMirrorAuth: Boolean get() = mirrorAuthUser.isNotBlank() && mirrorAuthPassword.isNotBlank()
        /**
         * True when Gradle subprocesses should receive the mirror sysprops.
         * False either because no mirror is configured or because the
         * operator has explicitly toggled "bypass mirror" so Gradle goes
         * direct via the proxy. The verify panel still probes the mirror
         * URL regardless of this flag.
         */
        val mirrorActiveForGradle: Boolean get() = hasMirror && !bypassMirror
    }

    /**
     * Single result row from {@link #probeConnectivity}. Targets are
     * probed sequentially because the user may be on a single-IP
     * corporate proxy with rate limits; parallel fan-out would just
     * trigger 429s.
     */
    data class ProbeResult(
        val target: String,
        val purpose: String,
        val viaProxy: Boolean,
        val statusCode: Int,
        val durationMs: Long,
        val ok: Boolean,
        val message: String
    )

    @Volatile private var current: Settings = Settings()

    val settings: Settings get() = current

    @PostConstruct
    fun init() {
        current = detectInitial()
        applyProxyAuthenticator(current)
        log.info(
            "Outbound connection defaults: httpsProxy='{}', httpProxy='{}', noProxy='{}', insecureSsl={}, source={}",
            current.httpsProxy, current.httpProxy, current.noProxy, current.insecureSsl, current.source
        )
    }

    /**
     * Replace the live settings and (when proxy details are present) sync
     * them into <code>~/.gradle/gradle.properties</code> so Gradle build
     * invocations launched outside the WebUI inherit the same proxy.
     */
    fun update(new: Settings) {
        current = new
        writeGradleProxyProperties(new)
        applyProxyAuthenticator(new)
        log.info(
            "Connection settings updated to: httpsProxy='{}', httpProxy='{}', noProxy='{}', insecureSsl={}, proxyAuth={}, mirror='{}'",
            new.httpsProxy, new.httpProxy, new.noProxy, new.insecureSsl,
            if (new.hasProxyAuth) "set" else "none", new.mirrorUrl
        )
    }

    /**
     * Install (or clear) a JVM-wide default {@link java.net.Authenticator}
     * for the configured proxy. Uses `RequestorType.PROXY` so we never
     * leak the proxy creds to a destination server. Also flips the
     * default {@code jdk.http.auth.tunneling.disabledSchemes} so HTTP
     * Basic works through CONNECT proxies (Java disables this by
     * default for security; corporate Basic-auth proxies are common
     * enough that we re-enable it when the user explicitly opted in).
     */
    private fun applyProxyAuthenticator(s: Settings) {
        if (!s.hasProxyAuth) {
            // Clear any previous auth so removing credentials actually takes effect.
            java.net.Authenticator.setDefault(null)
            return
        }
        // Enable Basic over HTTPS CONNECT tunnels.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "")
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                if (requestorType != RequestorType.PROXY) return null
                val now = current
                if (!now.hasProxyAuth) return null
                return java.net.PasswordAuthentication(now.proxyAuthUser, now.proxyAuthPassword.toCharArray())
            }
        })
    }

    /**
     * Probe a sensible set of targets to verify the operator's
     * proxy/mirror config actually reaches what Gradle needs to build.
     * Each probe is small (HEAD where possible, otherwise GET with
     * short read) so the whole sweep finishes in a few seconds even
     * over a slow proxy.
     *
     * Targets:
     *  - https://www.google.com/generate_204 — basic egress sanity check
     *  - https://services.gradle.org/versions/current — Gradle distribution server
     *  - https://repo.maven.apache.org/maven2/ — Maven Central
     *  - https://api.foojay.io/disco/v3.0/distributions — JDK toolchain provisioning
     *  - {@code mirrorUrl} (if configured) — operator's Artifactory mirror
     *  - {@code httpsProxy} (if configured) — does the proxy itself answer?
     */
    fun probeConnectivity(): List<ProbeResult> {
        val s = current
        val results = mutableListOf<ProbeResult>()
        val client = httpClient(Duration.ofSeconds(8))

        fun probe(url: String, purpose: String, useMirrorAuth: Boolean = false) {
            val start = System.currentTimeMillis()
            val viaProxy = currentProxySelector() != null && !isLoopback(url)
            results += try {
                val builder = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                if (useMirrorAuth && s.hasMirrorAuth) {
                    val token = java.util.Base64.getEncoder().encodeToString(
                        "${s.mirrorAuthUser}:${s.mirrorAuthPassword}".toByteArray())
                    builder.header("Authorization", "Basic $token")
                }
                val resp = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
                val ms = System.currentTimeMillis() - start
                val code = resp.statusCode()
                // Many CDNs return 200/204/301/302/401 for HEAD/GET on a
                // base URL. Treat anything below 500 as "the endpoint is
                // reachable" — the auth/path may be wrong but the network
                // path works, which is what we're testing.
                val ok = code in 200..499
                ProbeResult(url, purpose, viaProxy, code, ms, ok,
                    if (ok) "HTTP $code" else "HTTP $code (server error)")
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - start
                ProbeResult(url, purpose, viaProxy, -1, ms, false,
                    "${e.javaClass.simpleName}: ${e.message ?: "(no detail)"}")
            }
        }

        probe("https://www.google.com/generate_204", "Direct internet sanity check")
        probe("https://services.gradle.org/versions/current", "Gradle distribution server (gradlew bootstrap)")
        probe("https://repo.maven.apache.org/maven2/", "Maven Central (default dependency repo)")
        probe("https://api.foojay.io/disco/v3.0/distributions", "Foojay (JDK toolchain auto-provisioning)")
        if (s.hasMirror) {
            probe(s.mirrorUrl, "Configured Artifactory mirror", useMirrorAuth = true)
        }
        // Probing the proxy itself is informational — many corporate
        // proxies refuse direct GET without a target URL, so we don't
        // mark a non-2xx as failure. We just record what comes back.
        parseHostPort(s.httpsProxy)?.let { (h, p) ->
            val proxyUrl = "http://$h:$p/"
            val start = System.currentTimeMillis()
            results += try {
                // Explicitly bypass the proxy when probing the proxy itself
                // — otherwise the request just loops back to it.
                val direct = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                val resp = direct.send(
                    java.net.http.HttpRequest.newBuilder().uri(URI.create(proxyUrl))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()
                )
                val ms = System.currentTimeMillis() - start
                ProbeResult(proxyUrl, "Corporate proxy (host reachable)", false,
                    resp.statusCode(), ms, true,
                    "HTTP ${resp.statusCode()} — proxy host responds")
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - start
                ProbeResult(proxyUrl, "Corporate proxy (host reachable)", false, -1, ms, false,
                    "${e.javaClass.simpleName}: ${e.message ?: "unreachable"}")
            }
        }
        return results
    }

    private fun isLoopback(url: String): Boolean = runCatching {
        val u = URI.create(url)
        val h = u.host?.lowercase() ?: return@runCatching false
        h == "localhost" || h == "127.0.0.1" || h == "::1"
    }.getOrDefault(false)

    /**
     * Detailed probe of a single URL — captures the request line,
     * which proxy was used, response status + headers, and any
     * exception trace. Used by the /proxy "Test custom URL" and
     * "Test mirror" buttons; the verbose log goes into a collapsible
     * <details> on the page so debugging a failing path doesn't
     * require sshing in to read application logs.
     */
    data class DetailedProbe(
        val ok: Boolean,
        val target: String,
        val viaProxy: Boolean,
        val statusCode: Int,
        val durationMs: Long,
        val message: String,
        val log: String
    )

    /**
     * Mirror probes -- known Maven coordinates we expect a working
     * Artifactory mirror to serve. Picked for two reasons:
     *  1. They're plugin POMs that Gradle plugin resolution actually
     *     looks for (so a mirror that serves them will resolve the
     *     `plugins {}` DSL when paired with the corp-repos init script's
     *     useModule() mappings).
     *  2. They include both a Gradle-Plugin-Portal classic
     *     (spring-boot-gradle-plugin) and a Maven-Central transitive
     *     (gson) so we can tell whether the mirror is plugins-only,
     *     Maven-Central-only, or properly aggregated.
     */
    data class ArtifactProbe(
        val coord: String,        // "group:artifact:version"
        val pomPath: String,      // path under the mirror root, e.g. org/springframework/.../*.pom
        val description: String   // short human label
    )
    private val mirrorArtifactProbes = listOf(
        ArtifactProbe("org.springframework.boot:spring-boot-gradle-plugin:3.3.4",
            "org/springframework/boot/spring-boot-gradle-plugin/3.3.4/spring-boot-gradle-plugin-3.3.4.pom",
            "Spring Boot Gradle plugin (the plugin jar resolved by useModule)"),
        ArtifactProbe("com.google.code.gson:gson:2.10.1",
            "com/google/code/gson/gson/2.10.1/gson-2.10.1.pom",
            "gson 2.10.1 (transitive Maven Central dep)"),
        ArtifactProbe("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20",
            "org/jetbrains/kotlin/kotlin-gradle-plugin/2.0.20/kotlin-gradle-plugin-2.0.20.pom",
            "Kotlin Gradle plugin (one of the corp-repos init script's mapped plugins)")
    )

    fun probeUrlDetailed(rawUrl: String, useMirrorAuth: Boolean = false): DetailedProbe {
        val log = StringBuilder()
        val s = current
        val url = rawUrl.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it
            else "https://$it"
        }
        log.appendLine("$ probe ${if (useMirrorAuth) "(with mirror auth)" else ""} $url")
        val viaProxy = currentProxySelector() != null && !isLoopback(url)
        log.appendLine("[info] proxy: " +
            (if (s.httpsProxy.isNotBlank()) s.httpsProxy else "(none)") +
            (if (s.hasProxyAuth) " (HTTP-Basic as ${s.proxyAuthUser})" else ""))
        log.appendLine("[info] insecureSsl: ${s.insecureSsl}")
        log.appendLine("[info] viaProxy for this URL: $viaProxy")

        val start = System.currentTimeMillis()
        return runCatching {
            val client = httpClient(Duration.ofSeconds(10))
            val builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
            if (useMirrorAuth && s.hasMirrorAuth) {
                val token = java.util.Base64.getEncoder().encodeToString(
                    "${s.mirrorAuthUser}:${s.mirrorAuthPassword}".toByteArray())
                builder.header("Authorization", "Basic $token")
                log.appendLine("[info] Authorization: Basic <${s.mirrorAuthUser}:****>")
            }
            val resp = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            val ms = System.currentTimeMillis() - start
            val code = resp.statusCode()
            log.appendLine("[ok]   HTTP $code in ${ms}ms")
            // Response headers (relevant subset).
            val interesting = listOf("content-type", "content-length", "server",
                "x-cache", "via", "x-served-by", "www-authenticate", "location",
                "x-ratelimit-remaining", "x-jfrog-version")
            interesting.forEach { name ->
                resp.headers().firstValue(name).ifPresent {
                    log.appendLine("[hdr]  $name: $it")
                }
            }
            val body = resp.body() ?: ""
            if (body.isNotBlank()) {
                val trimmed = body.replace("\\s+".toRegex(), " ").take(180)
                log.appendLine("[body] ${trimmed}${if (body.length > 180) "…" else ""}")
            }
            // OK criterion is context-aware:
            //   * Mirror probe with auth attached: we BELIEVE the
            //     credentials should work, so 4xx/5xx are failures --
            //     the operator needs to know their token is rejected.
            //     But 3xx (redirect) is fine: many Artifactory installs
            //     302 the root URL to a UI path, a trailing-slash
            //     variant, or an authenticated landing page. Treating
            //     302 as failure was overcorrection from the earlier
            //     "401-as-success" bug -- the right line is "did the
            //     request reach a fully authenticated endpoint?", and
            //     a 302 satisfies that. (A 302 redirect-to-login WOULD
            //     be a covert auth failure, but tier-2 artifact
            //     resolution catches that since the actual POM GETs
            //     would still 401.)
            //   * Generic URL probe (no auth, just connectivity): the
            //     historical "200..499 = reachable" stance still
            //     applies -- a 401 from Maven Central means we got there
            //     but the endpoint requires auth, which is still useful
            //     diagnostic info.
            val authoritative = useMirrorAuth && s.hasMirrorAuth
            val ok = if (authoritative) code in 200..399 else code in 200..499
            // Mine common Artifactory body-error patterns for a more
            // actionable message. Without this, the operator sees
            // "HTTP 401 — server error" and has to dig the
            // detail out of the [body] line in the log.
            val bodyDiag = when {
                body.contains("token failed verification: parse") ->
                    "Artifactory rejected the bearer token (failed parse). Token is malformed or for a different realm."
                body.contains("Bad credentials") ->
                    "Artifactory rejected the credentials (Bad credentials)."
                body.contains("missingValueFor authentication") ->
                    "Artifactory says no credentials were sent. Check that mirrorAuthUser + password are saved."
                body.contains("does not have permission") ->
                    "Authenticated but missing permission on the requested path."
                else -> null
            }
            val message = when {
                ok -> "HTTP $code (${ms}ms)"
                authoritative && code == 401 ->
                    "HTTP 401 -- mirror rejected the saved credentials." +
                    (bodyDiag?.let { " $it" } ?: "") +
                    " Re-check the mirror username/password (or token) on the form above."
                authoritative && code == 403 ->
                    "HTTP 403 -- credentials are valid but lack permission for this URL." +
                    (bodyDiag?.let { " $it" } ?: "")
                code in 400..499 -> "HTTP $code — client error" + (bodyDiag?.let { ". $it" } ?: "")
                else -> "HTTP $code — server error" + (bodyDiag?.let { ". $it" } ?: "")
            }
            DetailedProbe(ok, url, viaProxy, code, ms, message, log.toString())
        }.getOrElse { e ->
            val ms = System.currentTimeMillis() - start
            log.appendLine("[err]  ${e.javaClass.simpleName}: ${e.message ?: "(no detail)"}")
            // Surface the chained cause (often hides the real DNS / TLS / proxy auth failure).
            var cause: Throwable? = e.cause
            while (cause != null) {
                log.appendLine("[err]  caused by ${cause.javaClass.simpleName}: ${cause.message ?: ""}")
                cause = cause.cause
            }
            DetailedProbe(false, url, viaProxy, -1, ms,
                "${e.javaClass.simpleName}: ${e.message ?: "request failed"}",
                log.toString())
        }
    }

    /**
     * Read proxy settings from the OS + VSCode without applying them.
     * Returns a structured snapshot the /proxy page can pre-fill the
     * form with; the operator confirms before saving. Each source is
     * probed independently so partial reads (e.g. VSCode settings
     * exists but registry is empty) still surface useful values.
     */
    data class DetectedProxy(
        val httpsProxy: String = "",
        val httpProxy: String = "",
        val noProxy: String = "",
        val proxyAuthUser: String = "",
        val foundIn: List<String> = emptyList(),
        val log: String = ""
    )

    /**
     * Second-tier mirror verification: HEAD/GET each known plugin POM
     * directly against the mirror URL. Catches the case where the
     * mirror's root URL is reachable (the existing /proxy/test-mirror
     * already proves that) but it's NOT actually serving the plugin
     * artifacts the harness needs -- e.g. a Maven-Central-only mirror
     * that doesn't proxy plugins.gradle.org and would reject every
     * plugin lookup with 404.
     *
     * One row per probe so the operator can see which artifacts came
     * back and which didn't, and whether the mirror is plugins-only,
     * Maven-Central-only, or aggregating both.
     */
    data class ArtifactProbeResult(
        val coord: String,
        val description: String,
        val statusCode: Int,
        val ok: Boolean,
        val message: String,
        val pomUrl: String
    )

    fun probeMirrorArtifacts(): List<ArtifactProbeResult> {
        val s = current
        if (!s.hasMirror) return emptyList()
        val client = httpClient(Duration.ofSeconds(15))
        val base = s.mirrorUrl.trim().trimEnd('/')
        return mirrorArtifactProbes.map { p ->
            val url = "$base/${p.pomPath}"
            runCatching {
                val builder = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                if (s.hasMirrorAuth) {
                    val token = java.util.Base64.getEncoder().encodeToString(
                        "${s.mirrorAuthUser}:${s.mirrorAuthPassword}".toByteArray())
                    builder.header("Authorization", "Basic $token")
                }
                val resp = client.send(builder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString())
                val code = resp.statusCode()
                val body = resp.body() ?: ""
                val ok = code == 200 && body.contains("<artifactId>")
                val msg = when {
                    ok -> "POM served (${body.length} bytes)"
                    code == 401 -> "401 — mirror rejected the saved credentials"
                    code == 403 -> "403 — authenticated but lacks permission for this path"
                    code == 404 -> "404 — mirror does NOT serve this artifact (proxy gap)"
                    code in 200..299 -> "HTTP $code but body is not a POM (${body.take(80)})"
                    else -> "HTTP $code"
                }
                ArtifactProbeResult(p.coord, p.description, code, ok, msg, url)
            }.getOrElse { e ->
                ArtifactProbeResult(p.coord, p.description, -1, false,
                    "${e.javaClass.simpleName}: ${e.message ?: ""}", url)
            }
        }
    }

    /**
     * Third-tier mirror verification: actually invoke a Gradle process
     * that exercises plugin resolution against the saved mirror. This
     * is the most authoritative test -- the same code path the harness
     * takes at benchmark time -- but is also the slowest (~30-90s
     * including any Gradle distribution download) and depends on a
     * usable `gradle` binary (or an existing project's gradlew) being
     * reachable from the WebUI process.
     *
     * Strategy:
     *  1. Generate a tiny scratch project under /tmp with a
     *     settings.gradle.kts that points pluginManagement at the
     *     saved mirror URL (with credentials when configured).
     *  2. Generate a build.gradle.kts that declares `plugins {
     *     id("org.springframework.boot") version "3.3.4" apply false }`
     *     -- exact same plugin id the banking-app build uses.
     *  3. Invoke gradlew (preferring a local checkout's wrapper, or
     *     fall back to system `gradle`) with `-p <tmp> tasks
     *     --refresh-dependencies --console=plain --no-daemon`.
     *  4. Capture stdout+stderr; success = exit 0 AND the plugin
     *     resolved (i.e. no "could not resolve" / "could not find
     *     plugin artifact" lines).
     */
    data class GradleProbeResult(
        val ok: Boolean,
        val exitCode: Int,
        val durationMs: Long,
        val gradleBinary: String,
        val tmpDir: String,
        val log: String,
        val message: String
    )

    /**
     * Live state of one tier-3 mirror probe. Each probe owns a
     * thread-safe StringBuilder that the reader thread appends to as
     * the gradle subprocess writes lines, and a {@code result} that
     * stays null until the subprocess exits. The poll endpoint reads
     * snapshots so the UI can stream output to the operator instead of
     * staring at a blank "Spawning gradle…" banner for 30-90s.
     */
    class GradleProbeState(
        val id: String,
        val started: Long = System.currentTimeMillis()
    ) {
        @Volatile var gradleBinary: String = ""
        @Volatile var tmpDir: String = ""
        @Volatile var command: List<String> = emptyList()
        @Volatile var done: Boolean = false
        @Volatile var result: GradleProbeResult? = null
        private val log = StringBuilder()

        fun appendLog(line: String) {
            synchronized(log) { log.appendLine(line) }
        }
        fun snapshotLog(): String = synchronized(log) { log.toString() }
    }

    private val gradleProbes = java.util.concurrent.ConcurrentHashMap<String, GradleProbeState>()

    fun getMirrorGradleProbe(id: String): GradleProbeState? = gradleProbes[id]

    private fun findGradleBinary(): String? {
        // Prefer the repo's bundled gradlew (9.4.1) over a system-wide
        // `gradle` install — operators on enterprise Windows often have
        // an older Gradle on PATH (e.g. 8.14) whose bundled Kotlin
        // compiler can't parse newer JDK version strings ("25.0.1"
        // throws IllegalArgumentException). On Windows we must look
        // for `gradlew.bat`, not the Unix shell-script `gradlew` —
        // canExecute() returns false for the latter on Windows so the
        // old code silently fell through to system gradle.bat.
        val wrapperName = if (Platform.isWindows) "gradlew.bat" else "gradlew"
        val candidates = listOf(
            "${System.getProperty("user.dir")}/banking-app/$wrapperName",
            "${System.getProperty("user.dir")}/bench-webui/$wrapperName",
            "${System.getProperty("user.dir")}/bench-cli/$wrapperName",
            "${System.getProperty("user.dir")}/../banking-app/$wrapperName",
            "${System.getProperty("user.dir")}/../bench-webui/$wrapperName"
        )
        candidates.firstOrNull {
            val f = java.io.File(it)
            // canExecute() is the right check on Unix but unreliable
            // on Windows (depends on file ACLs, not extension), so
            // accept any existing .bat file there.
            f.isFile && (Platform.isWindows || f.canExecute())
        }?.let { return java.io.File(it).absolutePath }
        val path = System.getenv("PATH") ?: ""
        val gradleExe = if (Platform.isWindows) "gradle.bat" else "gradle"
        return path.split(java.io.File.pathSeparator)
            .map { java.io.File(it, gradleExe) }
            .firstOrNull { it.isFile && (Platform.isWindows || it.canExecute()) }
            ?.absolutePath
    }

    /**
     * Kick off a tier-3 mirror probe in the background. Returns
     * immediately with a {@link GradleProbeState} that the caller can
     * poll via {@link #getMirrorGradleProbe}. The setup phase
     * (binary discovery, scratch-project generation, log seeding) runs
     * synchronously so the first response already contains the
     * planned command + paths the UI shows the operator. The actual
     * gradle invocation runs on a daemon thread with a reader that
     * streams stdout into the state's log, so polls during the run
     * see real progress instead of a frozen "Spawning…" banner.
     */
    fun startMirrorGradleProbe(): GradleProbeState {
        // Garbage-collect old probes before each new run so the map
        // doesn't grow unbounded. Anything finished + older than 30 min
        // is unreachable from any reasonable UI session.
        val cutoff = System.currentTimeMillis() - 30 * 60 * 1000
        gradleProbes.entries.removeIf { it.value.done && it.value.started < cutoff }

        val id = java.util.UUID.randomUUID().toString().take(8)
        val state = GradleProbeState(id)
        gradleProbes[id] = state

        val s = current
        if (!s.hasMirror) {
            state.appendLog("[err] No mirror URL configured.")
            state.result = GradleProbeResult(false, -1, 0, "", "", state.snapshotLog(),
                "Configure the mirror first.")
            state.done = true
            return state
        }

        val gradleBin = findGradleBinary()
        if (gradleBin == null) {
            state.appendLog("[err] No gradlew found in repo sub-projects and no `gradle` on PATH.")
            state.appendLog("[err] This test needs a Gradle binary to drive a real plugin-resolution attempt.")
            state.result = GradleProbeResult(false, -1, 0, "", "", state.snapshotLog(),
                "Install Gradle or run from a repo checkout (banking-app/, bench-webui/, etc).")
            state.done = true
            return state
        }
        state.gradleBinary = gradleBin

        val tmpDir = java.nio.file.Files.createTempDirectory("ai-bench-mirror-gradle-").toFile()
        state.tmpDir = tmpDir.absolutePath

        // Groovy DSL (settings.gradle, not .kts) on purpose: Gradle has
        // to evaluate this scratch project under the operator's `gradle`
        // binary, which on enterprise Windows boxes is often an older
        // version (8.14 in field reports) whose bundled Kotlin compiler
        // cannot parse newer JDK version strings. JDK 25 + Gradle 8.14
        // throws `IllegalArgumentException: 25.0.1` from the Kotlin
        // script compiler before plugin resolution even starts. Groovy
        // scripts skip the Kotlin compiler path entirely, so we still
        // exercise plugin DSL resolution against the mirror without
        // tripping that pre-condition. Single-quoted Groovy strings
        // are literal (no interpolation); we escape any embedded '\''
        // so a corp token containing apostrophes still parses.
        fun groovyEscape(v: String) = v.replace("\\", "\\\\").replace("'", "\\'")
        val authBlock = if (s.hasMirrorAuth) """
                allowInsecureProtocol = true
                credentials {
                    username = '${groovyEscape(s.mirrorAuthUser)}'
                    password = '${groovyEscape(s.mirrorAuthPassword)}'
                }
""" else "\n                allowInsecureProtocol = true"
        java.io.File(tmpDir, "settings.gradle").writeText("""
            pluginManagement {
                repositories {
                    maven {
                        url = '${groovyEscape(s.mirrorUrl)}'$authBlock
                    }
                }
                resolutionStrategy {
                    eachPlugin {
                        if (requested.id.id == 'org.springframework.boot') {
                            useModule("org.springframework.boot:spring-boot-gradle-plugin:" + requested.version)
                        }
                    }
                }
            }
            rootProject.name = 'ai-bench-mirror-gradle-probe'
        """.trimIndent() + "\n")
        java.io.File(tmpDir, "build.gradle").writeText("""
            plugins {
                id 'org.springframework.boot' version '3.3.4' apply false
            }
        """.trimIndent() + "\n")

        // --no-daemon so the process exits cleanly and the tmp project
        // doesn't get held in a long-lived daemon's configuration cache.
        // 180s cap -- a Gradle distribution download through a slow
        // proxy can take a while, but no plugin probe should
        // legitimately exceed 3 minutes.
        val cmd = listOf(gradleBin,
            "-p", tmpDir.absolutePath,
            "tasks", "--refresh-dependencies",
            "--console=plain", "--no-daemon", "--stacktrace")
        state.command = cmd

        state.appendLog("[info] gradle binary: $gradleBin")
        state.appendLog("[info] scratch project: ${tmpDir.absolutePath}")
        state.appendLog("[info] mirror URL: ${s.mirrorUrl}")
        state.appendLog("[info] mirror auth: " +
            (if (s.hasMirrorAuth) "Basic as ${s.mirrorAuthUser}" else "(none)"))
        state.appendLog("[cmd]  ${cmd.joinToString(" ")}")
        state.appendLog("[info] launching gradle (output streams below as the process emits it)…")

        val worker = Thread({ runMirrorGradleProbe(state, cmd) }, "mirror-gradle-probe-$id")
        worker.isDaemon = true
        worker.start()
        return state
    }

    private fun runMirrorGradleProbe(state: GradleProbeState, cmd: List<String>) {
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val proc = pb.start()
            // Reader thread drains the merged stdout/stderr line-by-line
            // and appends to the shared state.log. Daemon so it never
            // blocks JVM shutdown.
            val reader = Thread({
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        var line = r.readLine()
                        while (line != null) {
                            state.appendLog(line)
                            line = r.readLine()
                        }
                    }
                } catch (_: Exception) { /* process killed; reader exits */ }
            }, "mirror-gradle-reader-${state.id}")
            reader.isDaemon = true
            reader.start()

            val ok = proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) {
                proc.destroyForcibly()
                reader.join(2_000)
            } else {
                reader.join(5_000)
            }

            val ms = System.currentTimeMillis() - state.started
            val finalLog = state.snapshotLog()
            state.result = if (!ok) {
                GradleProbeResult(false, -1, ms, state.gradleBinary, state.tmpDir, finalLog,
                    "Gradle did not finish within 180s. Killed.")
            } else {
                val exit = proc.exitValue()
                val resolutionFail = finalLog.contains("could not resolve plugin artifact") ||
                                     finalLog.contains("Could not find") ||
                                     finalLog.contains("not found in any of the following sources")
                val pass = (exit == 0) && !resolutionFail
                GradleProbeResult(pass, exit, ms, state.gradleBinary, state.tmpDir, finalLog,
                    if (pass)
                        "Spring Boot plugin (3.3.4) resolved successfully through the mirror in ${ms}ms."
                    else if (resolutionFail)
                        "Plugin resolution FAILED. Mirror reachable but doesn't serve spring-boot-gradle-plugin."
                    else
                        "Gradle exited $exit. See log."
                )
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - state.started
            state.appendLog("[err]  ${e.javaClass.simpleName}: ${e.message ?: ""}")
            state.result = GradleProbeResult(false, -1, ms, state.gradleBinary, state.tmpDir,
                state.snapshotLog(),
                "Failed to invoke Gradle: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        } finally {
            state.done = true
        }
    }

    fun detectFromOs(): DetectedProxy {
        val log = StringBuilder()
        val sources = mutableListOf<String>()
        var https = ""; var http = ""; var noProxy = ""; var user = ""

        // 1. macOS networksetup (already wired up; reuse).
        if (Platform.isMac) {
            log.appendLine("[mac] running `networksetup -getwebproxy Wi-Fi`…")
            detectMacProxy()?.let { mac ->
                log.appendLine("[mac]   httpsProxy=${mac.httpsProxy}")
                log.appendLine("[mac]   httpProxy=${mac.httpProxy}")
                log.appendLine("[mac]   noProxy=${mac.noProxy}")
                if (mac.httpsProxy.isNotBlank()) { https = mac.httpsProxy; sources += "macOS networksetup" }
                if (mac.httpProxy.isNotBlank() && http.isBlank()) http = mac.httpProxy
                if (mac.noProxy.isNotBlank()) noProxy = mac.noProxy
            } ?: log.appendLine("[mac]   (no proxy or networksetup unavailable)")
        }

        // 2. Windows registry (HKCU IE settings — what most enterprise
        //    proxy tooling writes to so apps see it).
        if (Platform.isWindows) {
            val winFound = readWindowsProxy(log)
            if (winFound != null) {
                if (winFound.first.isNotBlank()) {
                    https = winFound.first
                    if (http.isBlank()) http = winFound.first
                    sources += "Windows registry (HKCU)"
                }
                if (winFound.second.isNotBlank()) noProxy = winFound.second
            }
        }

        // 3. VSCode user settings — http.proxy / http.proxyAuthorization /
        //    http.noProxy. JSONC parsing via regex (settings.json may
        //    contain comments; full JSONC parse would pull in another dep).
        val vsc = readVsCodeProxy(log)
        if (vsc != null) {
            val (vp, vAuth, vNo) = vsc
            if (vp.isNotBlank() && https.isBlank()) {
                https = vp; if (http.isBlank()) http = vp
                sources += "VSCode settings.json"
            }
            if (vNo.isNotBlank() && noProxy.isBlank()) noProxy = vNo
            if (vAuth.isNotBlank()) {
                // proxyAuthorization is "Basic <base64>" — decode the user only.
                runCatching {
                    val token = vAuth.removePrefix("Basic ").trim()
                    val decoded = String(java.util.Base64.getDecoder().decode(token))
                    user = decoded.substringBefore(':')
                    log.appendLine("[vsc]   proxyAuthorization → user=$user (password not pre-filled)")
                }
            }
        }

        // 4. Env vars as a last-resort fallback (already used at startup
        //    but include in the report so the user knows they're set).
        val envHttps = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: ""
        val envHttp = System.getenv("HTTP_PROXY") ?: System.getenv("http_proxy") ?: ""
        if (envHttps.isNotBlank() || envHttp.isNotBlank()) {
            log.appendLine("[env] HTTPS_PROXY=$envHttps HTTP_PROXY=$envHttp")
            if (https.isBlank()) https = envHttps
            if (http.isBlank()) http = envHttp
            if (https.isNotBlank() || http.isNotBlank()) sources += "environment variables"
        }

        if (sources.isEmpty()) log.appendLine("[info] No proxy detected from any source.")
        return DetectedProxy(https, http, noProxy, user, sources, log.toString())
    }

    /**
     * Read Windows IE proxy settings via PowerShell (Get-ItemProperty
     * on HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings).
     * Returns (proxyServer, proxyOverride) — both possibly empty.
     */
    private fun readWindowsProxy(log: StringBuilder): Pair<String, String>? {
        log.appendLine("[win] reading HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings…")
        return runCatching {
            val script = """
                ${'$'}p = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
                if (${'$'}p.ProxyEnable -eq 1) {
                    Write-Output "PROXYENABLE=1"
                    Write-Output ("PROXYSERVER=" + ${'$'}p.ProxyServer)
                    Write-Output ("PROXYOVERRIDE=" + ${'$'}p.ProxyOverride)
                    Write-Output ("AUTOCONFIGURL=" + ${'$'}p.AutoConfigURL)
                } else {
                    Write-Output "PROXYENABLE=0"
                }
            """.trimIndent()
            val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            log.appendLine("[win]   raw: " + out.replace("\n", " | ").trim())
            if (!out.contains("PROXYENABLE=1")) {
                log.appendLine("[win]   ProxyEnable=0 (proxy disabled in IE settings)")
                return null
            }
            // ProxyServer can be plain "host:port" or "http=host:port;https=host:port".
            val rawServer = Regex("PROXYSERVER=(.*)").find(out)?.groupValues?.get(1)?.trim() ?: ""
            val rawOverride = Regex("PROXYOVERRIDE=(.*)").find(out)?.groupValues?.get(1)?.trim() ?: ""
            val server = if (rawServer.contains("https=")) {
                Regex("https=([^;]+)").find(rawServer)?.groupValues?.get(1) ?: rawServer
            } else if (rawServer.contains("http=")) {
                Regex("http=([^;]+)").find(rawServer)?.groupValues?.get(1) ?: rawServer
            } else rawServer
            val proxy = if (server.isBlank()) "" else "http://$server"
            log.appendLine("[win]   ProxyServer=$server → $proxy")
            log.appendLine("[win]   ProxyOverride=$rawOverride")
            // The IE bypass list uses ';' and '<local>' for short-name match;
            // convert to the comma-separated form our settings expect.
            val override = rawOverride.split(';').filter { it.isNotBlank() && it != "<local>" }
                .joinToString(",")
            proxy to override
        }.getOrElse { e ->
            log.appendLine("[win]   error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Read VSCode user settings.json and extract any proxy keys. The
     * file is JSONC (allows comments), so we strip line-comments before
     * parsing the bits we care about with a small regex pass — full
     * JSONC parsing isn't worth a dependency for three keys.
     */
    private fun readVsCodeProxy(log: StringBuilder): Triple<String, String, String>? {
        val home = System.getProperty("user.home") ?: return null
        val candidates = if (Platform.isMac) listOf(
            "$home/Library/Application Support/Code/User/settings.json",
            "$home/Library/Application Support/Code - Insiders/User/settings.json"
        ) else if (Platform.isWindows) listOf(
            "${System.getenv("APPDATA")}\\Code\\User\\settings.json",
            "${System.getenv("APPDATA")}\\Code - Insiders\\User\\settings.json"
        ) else listOf(
            "$home/.config/Code/User/settings.json",
            "$home/.config/Code - Insiders/User/settings.json"
        )
        for (path in candidates) {
            val f = java.io.File(path)
            if (!f.isFile) continue
            log.appendLine("[vsc] reading $path")
            return runCatching {
                val raw = f.readText()
                // Strip // line comments to make the regex search reliable.
                val stripped = raw.lineSequence().joinToString("\n") { line ->
                    val idx = line.indexOf("//")
                    if (idx >= 0 && !insideQuotedString(line, idx)) line.substring(0, idx) else line
                }
                fun extract(key: String): String =
                    Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(stripped)?.groupValues?.get(1) ?: ""
                val proxy = extract("http\\.proxy")
                val auth = extract("http\\.proxyAuthorization")
                val noProxy = extract("http\\.noProxy")
                if (proxy.isNotBlank()) log.appendLine("[vsc]   http.proxy=$proxy")
                if (auth.isNotBlank()) log.appendLine("[vsc]   http.proxyAuthorization=<set>")
                if (noProxy.isNotBlank()) log.appendLine("[vsc]   http.noProxy=$noProxy")
                if (proxy.isBlank() && auth.isBlank() && noProxy.isBlank()) {
                    log.appendLine("[vsc]   (no http.proxy keys present)")
                    null
                } else Triple(proxy, auth, noProxy)
            }.getOrElse {
                log.appendLine("[vsc]   error: ${it.message}")
                null
            }
        }
        log.appendLine("[vsc] no settings.json found in standard locations")
        return null
    }

    /** Crude check: is char index `idx` of `line` inside a "..." literal? */
    private fun insideQuotedString(line: String, idx: Int): Boolean {
        var inQ = false; var i = 0
        while (i < idx) {
            val c = line[i]
            if (c == '"' && (i == 0 || line[i - 1] != '\\')) inQ = !inQ
            i++
        }
        return inQ
    }

    /**
     * Reset to the auto-detected OS defaults. Gradle proxy properties are
     * rewritten to match.
     */
    fun resetToDetected() {
        update(detectInitial())
    }

    /**
     * Build an {@code HttpClient} that honors the current proxy + TLS
     * settings. All outbound calls from WebUI controllers should go
     * through this factory rather than {@code HttpClient.newBuilder()}
     * directly so proxies and the insecure-SSL override are applied
     * uniformly.
     */
    fun httpClient(connectTimeout: Duration = Duration.ofSeconds(10)): HttpClient {
        val builder = HttpClient.newBuilder().connectTimeout(connectTimeout)
        currentProxySelector()?.let { builder.proxy(it) }
        if (current.insecureSsl) {
            builder.sslContext(trustAllSslContext())
        }
        return builder.build()
    }

    /**
     * JVM system-property args (e.g. {@code -Dhttps.proxyHost=...}) that
     * must be appended to Gradle subprocess command lines so the child
     * honors the same proxy and TLS preferences.
     */
    fun gradleSystemProps(): List<String> {
        val args = mutableListOf<String>()
        parseHostPort(current.httpProxy)?.let { (h, p) ->
            args += "-Dhttp.proxyHost=$h"
            args += "-Dhttp.proxyPort=$p"
        }
        parseHostPort(current.httpsProxy)?.let { (h, p) ->
            args += "-Dhttps.proxyHost=$h"
            args += "-Dhttps.proxyPort=$p"
        }
        if (current.noProxy.isNotBlank()) {
            // JVM uses | as separator with wildcard support; convert from
            // the comma-separated no_proxy convention.
            val hosts = current.noProxy.split(",").map { it.trim() }
                .filter { it.isNotEmpty() }.joinToString("|")
            if (hosts.isNotEmpty()) {
                args += "-Dhttp.nonProxyHosts=$hosts"
            }
        }
        // Proxy HTTP-Basic credentials. Gradle, Maven, and most JVM
        // HTTP clients honor these system props for the JVM-default
        // proxy authentication path. Mirroring them on http.* and
        // https.* covers both schemes.
        if (current.hasProxyAuth) {
            args += "-Dhttp.proxyUser=${current.proxyAuthUser}"
            args += "-Dhttp.proxyPassword=${current.proxyAuthPassword}"
            args += "-Dhttps.proxyUser=${current.proxyAuthUser}"
            args += "-Dhttps.proxyPassword=${current.proxyAuthPassword}"
            args += "-Djdk.http.auth.tunneling.disabledSchemes="
            args += "-Djdk.http.auth.proxying.disabledSchemes="
        }
        // Artifactory mirror — banking-app's settings.gradle.kts has an
        // `enterprise.sim.mirror`-conditional block that swaps in a
        // custom Maven repository. Pipe the value through so a single
        // /proxy save means the next gradlew run uses the mirror without
        // hand-editing settings.gradle.kts. Mirror-auth is forwarded as
        // separate properties the user can wire into their own repo
        // block; we don't auto-rewrite settings.gradle.kts to consume
        // them since that would require knowing the repo name.
        //
        // SKIP injection when `bypassMirror` is set — that toggle keeps
        // the mirror URL visible in the UI (and probeable via the verify
        // panel) but tells Gradle to talk direct to plugins.gradle.org /
        // Maven Central through the configured proxy. Required when the
        // corp Artifactory doesn't carry plugin content that only lives
        // at plugins.gradle.org (e.g. foojay-resolver).
        if (current.mirrorActiveForGradle) {
            args += "-Denterprise.sim.mirror=${current.mirrorUrl}"
            if (current.hasMirrorAuth) {
                args += "-DmirrorUsername=${current.mirrorAuthUser}"
                args += "-DmirrorPassword=${current.mirrorAuthPassword}"
            }
        }
        if (current.insecureSsl) {
            // These switches tell most JVM HTTP stacks to skip cert
            // validation. They're applied to the subprocess JVM itself;
            // Gradle's internal Maven/Ivy resolvers honor them because
            // they share the JVM's default SSLContext. Note that this
            // does NOT reach every possible TLS client — some plugins
            // bring their own HttpClient — so we still print a banner
            // on the proxy UI reminding the operator of the limitation.
            args += "-Dcom.sun.net.ssl.checkRevocation=false"
            args += "-Dtrust_all_cert=true"
        }
        return args
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun currentProxySelector(): ProxySelector? {
        val httpsHostPort = parseHostPort(current.httpsProxy)
        val httpHostPort = parseHostPort(current.httpProxy)
        if (httpsHostPort == null && httpHostPort == null) return null

        val noProxyPatterns = current.noProxy.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        return object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val host = (uri.host ?: "").lowercase()
                // Honor no_proxy: exact match, suffix match, or wildcard-style ".foo.com"
                for (pattern in noProxyPatterns) {
                    val p = pattern.removePrefix(".")
                    if (host == p || host.endsWith(".$p")) return listOf(Proxy.NO_PROXY)
                }
                // localhost / loopback bypass — banking-app and AppMap
                // agent calls go to 127.0.0.1 and should never be proxied.
                if (host == "localhost" || host == "127.0.0.1" || host == "::1") {
                    return listOf(Proxy.NO_PROXY)
                }
                val hp = (if (uri.scheme.equals("https", ignoreCase = true)) httpsHostPort else httpHostPort)
                    ?: return listOf(Proxy.NO_PROXY)
                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(hp.first, hp.second.toInt())))
            }

            override fun connectFailed(uri: URI, sa: java.net.SocketAddress, ioe: java.io.IOException) {
                log.warn("Proxy connection failed for {} via {}: {}", uri, sa, ioe.message)
            }
        }
    }

    private fun trustAllSslContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustAll), SecureRandom())
        return ctx
    }

    /** Hostname verifier that accepts any CN/SAN when insecure mode is on. */
    fun insecureHostnameVerifier(): HostnameVerifier = HostnameVerifier { _: String, _: SSLSession -> true }

    private fun detectInitial(): Settings {
        val httpsProxy = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: ""
        val httpProxy = System.getenv("HTTP_PROXY") ?: System.getenv("http_proxy") ?: ""
        val noProxy = System.getenv("NO_PROXY") ?: System.getenv("no_proxy") ?: ""
        if (httpsProxy.isNotEmpty() || httpProxy.isNotEmpty()) {
            return Settings(httpsProxy, httpProxy, noProxy, source = "environment")
        }
        detectMacProxy()?.let { return it }
        return Settings()
    }

    private fun detectMacProxy(): Settings? = runCatching {
        val proc = ProcessBuilder("networksetup", "-getwebproxy", "Wi-Fi")
            .redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) return null

        val enabled = Regex("Enabled:\\s*(Yes|No)").find(output)?.groupValues?.get(1)
        if (enabled != "Yes") return null

        val server = Regex("Server:\\s*(\\S+)").find(output)?.groupValues?.get(1) ?: return null
        val port = Regex("Port:\\s*(\\d+)").find(output)?.groupValues?.get(1) ?: "80"
        val httpProxy = "http://$server:$port"

        val secProc = ProcessBuilder("networksetup", "-getsecurewebproxy", "Wi-Fi")
            .redirectErrorStream(true).start()
        val secOutput = secProc.inputStream.bufferedReader().readText()
        secProc.waitFor()
        val secEnabled = Regex("Enabled:\\s*(Yes|No)").find(secOutput)?.groupValues?.get(1)
        val httpsProxy = if (secEnabled == "Yes") {
            val secServer = Regex("Server:\\s*(\\S+)").find(secOutput)?.groupValues?.get(1) ?: server
            val secPort = Regex("Port:\\s*(\\d+)").find(secOutput)?.groupValues?.get(1) ?: "443"
            "http://$secServer:$secPort"
        } else httpProxy

        val bypassProc = ProcessBuilder("networksetup", "-getproxybypassdomains", "Wi-Fi")
            .redirectErrorStream(true).start()
        val bypassOutput = bypassProc.inputStream.bufferedReader().readText().trim()
        bypassProc.waitFor()
        val noProxy = bypassOutput.lines().filter { it.isNotBlank() && !it.startsWith("There") }.joinToString(",")

        Settings(httpsProxy, httpProxy, noProxy, source = "macOS system")
    }.getOrNull()

    private fun parseHostPort(url: String): Pair<String, String>? {
        if (url.isBlank()) return null
        val cleaned = url.removePrefix("https://").removePrefix("http://").trim('/')
        val parts = cleaned.split(":")
        if (parts.size < 2) return null
        val host = parts[0]
        val port = parts[1].takeWhile { it.isDigit() }
        if (host.isBlank() || port.isBlank()) return null
        return host to port
    }

    private fun writeGradleProxyProperties(s: Settings) {
        if (s.httpsProxy.isEmpty() && s.httpProxy.isEmpty()) return
        val gradleDir = java.io.File(System.getProperty("user.home"), ".gradle")
        gradleDir.mkdirs()
        val propsFile = java.io.File(gradleDir, "gradle.properties")
        val existingLines = if (propsFile.exists()) {
            propsFile.readLines().filter { line ->
                !line.startsWith("systemProp.http.proxyHost") &&
                !line.startsWith("systemProp.http.proxyPort") &&
                !line.startsWith("systemProp.https.proxyHost") &&
                !line.startsWith("systemProp.https.proxyPort") &&
                !line.startsWith("systemProp.http.nonProxyHosts")
            }
        } else emptyList()

        val newLines = mutableListOf<String>()
        newLines.addAll(existingLines)
        parseHostPort(s.httpProxy)?.let { (h, p) ->
            newLines.add("systemProp.http.proxyHost=$h")
            newLines.add("systemProp.http.proxyPort=$p")
        }
        parseHostPort(s.httpsProxy)?.let { (h, p) ->
            newLines.add("systemProp.https.proxyHost=$h")
            newLines.add("systemProp.https.proxyPort=$p")
        }
        if (s.noProxy.isNotEmpty()) {
            val gradleNoProxy = s.noProxy.split(",").joinToString("|") { it.trim() }
            newLines.add("systemProp.http.nonProxyHosts=$gradleNoProxy")
        }
        propsFile.writeText(newLines.joinToString("\n") + "\n")
    }
}
