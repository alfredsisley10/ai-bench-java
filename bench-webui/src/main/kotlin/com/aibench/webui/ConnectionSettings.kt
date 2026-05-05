package com.aibench.webui

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
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
        val bypassMirror: Boolean = false,
        // Artifactory repo key (e.g. "libs-release", "maven-virtual",
        // "spring-plugins-virtual"). Written to ~/.gradle/gradle.properties
        // as `artifactory_repoKey` so build scripts that read it via
        // providers.gradleProperty("artifactory_repoKey") -- a common
        // pattern in enterprise plugin/repo configs -- pick it up.
        // Distinct from mirrorUrl: mirrorUrl is the FULL URL of the
        // virtual; repoKey is the trailing path segment scripts often
        // construct URLs from. The /mirror form lets the operator set it.
        val artifactoryRepoKey: String = "",
        // Corp policy: Maven Central content reaches the build only
        // via the Artifactory mirror; direct egress to
        // repo.maven.apache.org is blocked at the corp proxy
        // (typically 403 from the proxy itself, not the upstream).
        // When this is on:
        //   - The verify-gradle-connectivity panel skips the direct
        //     maven.apache.org probe and probes a known Central
        //     artifact AT THE MIRROR URL instead, so the panel's
        //     verdict reflects what gradle actually does.
        //   - banking-app/build.gradle.kts drops its mavenCentral()
        //     last-ditch fallback (driven by -DcentralViaMirror=true
        //     from gradleSystemProps()), so gradle never tries the
        //     blocked upstream.
        // Requires hasMirror to be meaningful; if no mirror is
        // configured the toggle has no effect.
        // Default true: every corp Artifactory deployment we've seen
        // proxies Maven Central through the mirror, and direct egress
        // to repo.maven.apache.org from inside the corp network is
        // typically blocked at the proxy. Operators on a non-corp
        // network can flip off if they actually have direct egress.
        val centralViaMirrorOnly: Boolean = true,
        // Optional second virtual repository URL for the corp
        // Artifactory's external/Maven-Central proxy. Most enterprise
        // Artifactory deployments split repos into:
        //   - <corp>/artifactory/maven-virtual -- internal libs +
        //     plugins, tracked here as `mirrorUrl`
        //   - <corp>/artifactory/maven-external-virtual -- proxied
        //     external content (Maven Central, plugin portal mirrors,
        //     etc.), tracked here as this field.
        // Same corp Artifactory instance, so the existing mirror
        // auth (mirrorAuthUser/Password) applies to both. When set,
        // banking-app's build.gradle.kts uses this URL as the
        // Maven-Central source (via -DmavenExternalVirtual=...) and
        // the verify panel probes it as the canonical Central path.
        // Empty = single-mirror setup (the current legacy behaviour).
        val mavenExternalVirtualUrl: String = "",
        // When true, the mirror's hostname (and the maven-external-
        // virtual's, when set) is auto-added to the effective
        // nonProxyHosts list -- both for bench-webui's HttpClient
        // and for gradle subprocesses. Default OFF preserves the
        // legacy "everything goes through the proxy" behaviour.
        // Turn ON when corp Artifactory is on the internal network
        // and the corp proxy can't / shouldn't be the path to it
        // (typical symptom: SSLHandshakeException on gradle TLS to
        // the mirror even though /proxy verify-connectivity passes,
        // because the proxy's MITM cert is the wrong shape for the
        // mirror's internal-CA cert OR the proxy refuses CONNECT to
        // an internal IP). The toggle does NOT modify the operator's
        // saved noProxy field; the auto-bypass is computed at
        // request time so toggling it off cleanly restores the
        // through-proxy routing.
        // Default true: corp Artifactory typically lives on the
        // internal network, and routing internal traffic through the
        // corp proxy fails on TLS handshake (proxy can't MITM
        // internal-CA certs cleanly, OR refuses CONNECT to internal
        // IPs). Operators on a non-corp setup can flip off.
        val mirrorBypassProxy: Boolean = true
    ) {
        /** Convenience flag — true when proxy auth is fully configured. */
        val hasProxyAuth: Boolean get() = proxyAuthUser.isNotBlank() && proxyAuthPassword.isNotBlank()
        /** Convenience flag — true when a mirror URL is set. */
        val hasMirror: Boolean get() = mirrorUrl.isNotBlank()
        /** Convenience flag — true when a separate maven-external-virtual is configured. */
        val hasMavenExternalVirtual: Boolean get() = mavenExternalVirtualUrl.isNotBlank()
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

        /**
         * Human-readable summary of how Maven Central + plugin/dep
         * resolution is currently configured to flow. Surfaced as a
         * badge on the /mirror Active settings table so the operator
         * can see at a glance which of the four valid combinations is
         * active without mentally combining bypassMirror +
         * centralViaMirrorOnly + hasMirror.
         */
        val resolutionMode: String get() = when {
            !hasMirror                                  -> "Maven Central direct (no mirror configured)"
            bypassMirror                                -> "Mirror bypassed — Maven Central direct via proxy"
            centralViaMirrorOnly                        -> "Mirror only (Maven Central proxied via mirror)"
            else                                         -> "Mirror + Maven Central direct fallback"
        }
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
        writeCorpInitScript(new)
        writeInsecureSslInitScript(new)
        applyProxyAuthenticator(new)
        log.info(
            "Connection settings updated to: httpsProxy='{}', httpProxy='{}', noProxy='{}', insecureSsl={}, proxyAuth={}, mirror='{}', bypassMirror={}",
            new.httpsProxy, new.httpProxy, new.noProxy, new.insecureSsl,
            if (new.hasProxyAuth) "set" else "none", new.mirrorUrl, new.bypassMirror
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
     */
    fun probeConnectivity(): List<ProbeResult> {
        val s = current
        val results = mutableListOf<ProbeResult>()
        val client = httpClient(Duration.ofSeconds(8))

        // Synthetic top-of-sweep row that surfaces the parsed proxy
        // state. If the operator saved a proxy but parseHostPort
        // couldn't extract host:port from it (e.g. "proxy.corp.com"
        // with no port), every later row would render "Via proxy = no"
        // with no explanation; this row makes the discrepancy obvious.
        val httpsParsed = parseProxy(s.httpsProxy)
        val httpParsed  = parseProxy(s.httpProxy)
        val bothBlank   = s.httpsProxy.isBlank() && s.httpProxy.isBlank()
        val anyDefaulted = (httpsParsed?.portDefaulted == true) ||
                           (httpParsed?.portDefaulted == true)
        val proxyDiagnostic = when {
            bothBlank ->
                ProbeResult("(no proxy)", "Proxy configuration",
                    viaProxy = false, statusCode = 0, durationMs = 0,
                    ok = true,
                    message = "No proxy saved; every probe below will go direct.")
            httpsParsed != null || httpParsed != null -> {
                val parts = mutableListOf<String>()
                httpsParsed?.let {
                    parts += "https=${it.host}:${it.port}" +
                        (if (it.portDefaulted) " (port defaulted)" else "")
                }
                httpParsed?.let {
                    parts += "http=${it.host}:${it.port}" +
                        (if (it.portDefaulted) " (port defaulted)" else "")
                }
                if (s.hasProxyAuth) parts += "auth=${s.proxyAuthUser}"
                val defaultedNote = if (anyDefaulted)
                    " ⚠ Port was defaulted because the saved URL had no explicit ':<port>'. " +
                    "If your proxy listens on a different port, edit /proxy and add it."
                else ""
                ProbeResult(s.httpsProxy.ifBlank { s.httpProxy }, "Proxy configuration",
                    viaProxy = true, statusCode = 0, durationMs = 0,
                    ok = !anyDefaulted,
                    message = "Parsed — " + parts.joinToString(", ") +
                              ". Probes below SHOULD show 'Via proxy = yes'." +
                              defaultedNote)
            }
            else ->
                ProbeResult(s.httpsProxy.ifBlank { s.httpProxy }, "Proxy configuration",
                    viaProxy = false, statusCode = -1, durationMs = 0,
                    ok = false,
                    message = "Proxy is saved but couldn't be parsed at all — " +
                              "expected form 'proxy.corp.com:8080' or 'https://proxy.corp.com:8080'. " +
                              "Probes below will all go direct.")
        }
        results += proxyDiagnostic

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
                // OK criterion is intentionally tight:
                //   2xx                — success
                //   3xx                — redirect; URL is reachable
                //   401                — auth challenge from upstream
                //                        (URL reachable, just needs a token)
                // Everything else is a fail. The previous 200..499 check
                // green-lit a 403 from the corp proxy (the proxy refusing
                // the request, which is exactly what verify should catch),
                // and a 404 from a typo'd URL.
                val ok = code in 200..399 || code == 401
                val msg = when {
                    code in 200..299 -> "HTTP $code"
                    code in 300..399 -> "HTTP $code (redirect — endpoint reachable)"
                    code == 401      -> "HTTP 401 (auth challenge — endpoint reachable, supply credentials)"
                    code == 403      -> "HTTP 403 (forbidden — proxy or upstream refused)"
                    code == 404      -> "HTTP 404 (not found — URL or path is wrong)"
                    code in 400..499 -> "HTTP $code (client error)"
                    else             -> "HTTP $code (server error)"
                }
                ProbeResult(url, purpose, viaProxy, code, ms, ok, msg)
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - start
                ProbeResult(url, purpose, viaProxy, -1, ms, false, formatProbeException(e))
            }
        }

        probe("https://www.google.com/generate_204", "Direct internet sanity check")
        probe("https://services.gradle.org/versions/current", "Gradle distribution server (gradlew bootstrap)")
        // Maven Central probe routing depends on operator's
        // resolution mode. When centralViaMirrorOnly is set, corp
        // policy blocks direct egress to repo.maven.apache.org and
        // expects Central content to flow through the Artifactory
        // mirror -- so the panel probes a known Central artifact AT
        // THE MIRROR URL instead of the upstream. That mirrors what
        // gradle actually does once the corp init script rewrites
        // mavenCentral() → mirrorUrl, and stops the panel from
        // surfacing the inevitable proxy-403 as a failure.
        if (s.centralViaMirrorOnly && s.hasMirror) {
            // Spring Core 6.1.13 is on banking-app's classpath via
            // spring-boot-starter; if the corp Artifactory virtual
            // claims to proxy Maven Central, this artifact must
            // exist there or banking-app builds would already be
            // broken. Prefer the dedicated maven-external-virtual
            // URL (Artifactory's external-proxy convention) when set
            // -- that's the URL the corp policy actually expects
            // Central traffic to hit.
            val centralBase = (if (s.hasMavenExternalVirtual)
                s.mavenExternalVirtualUrl else s.mirrorUrl).trimEnd('/')
            val centralProbeUrl = "$centralBase/org/springframework/" +
                "spring-core/6.1.13/spring-core-6.1.13.pom"
            val purposeLabel = if (s.hasMavenExternalVirtual)
                "Maven Central via maven-external-virtual (corp policy blocks direct egress)"
            else
                "Maven Central via mirror (corp policy blocks direct egress)"
            probe(centralProbeUrl, purposeLabel, useMirrorAuth = true)
        } else {
            probe("https://repo.maven.apache.org/maven2/",
                "Maven Central (default dependency repo)")
            // Even when not in centralViaMirrorOnly mode, surface a
            // standalone health row for the maven-external-virtual
            // when it's configured -- otherwise a typo in the URL
            // would silently break Maven Central resolution as soon
            // as the operator flips the toggle on.
            if (s.hasMavenExternalVirtual) {
                probe(s.mavenExternalVirtualUrl.trimEnd('/') + "/",
                    "maven-external-virtual root reachability",
                    useMirrorAuth = true)
            }
        }
        probe("https://api.foojay.io/disco/v3.0/distributions", "Foojay (JDK toolchain auto-provisioning)")
        if (s.hasMirror) {
            probe(s.mirrorUrl, "Configured Artifactory mirror", useMirrorAuth = true)
        }
        return results
    }

    /**
     * Proxy-only connectivity sweep: just the targets that test
     * whether outbound HTTPS works at all through the saved proxy.
     * Used by the /proxy "Verify connectivity" panel after we split
     * gradle-specific targets out to /mirror.
     */
    fun probeProxyConnectivity(): List<ProbeResult> {
        val results = mutableListOf<ProbeResult>()
        val helper = probeConnectivity()  // reuse the full sweep, then filter
        // Just the functional reachability probe. The synthetic
        // "Proxy configuration" row used to surface here as a
        // top-of-table diagnostic, but its target IS the proxy
        // endpoint itself -- which read as "we're testing the
        // proxy" even though the row isn't a probe. Operators
        // didn't want a row that looks like a self-test of the
        // proxy endpoint cluttering the panel; the parsed proxy
        // state is already visible on the form above.
        for (r in helper) {
            if (r.purpose == "Direct internet sanity check") {
                results += r
            }
        }
        return results
    }

    /**
     * Gradle-ecosystem connectivity sweep: services.gradle.org +
     * Maven Central + Foojay + the configured mirror. Surfaced on
     * the /mirror page (now linked as 'Gradle' in the nav).
     */
    fun probeGradleConnectivity(): List<ProbeResult> {
        val results = mutableListOf<ProbeResult>()
        val helper = probeConnectivity()
        for (r in helper) {
            if (r.purpose == "Proxy configuration" ||
                r.purpose == "Direct internet sanity check") continue
            results += r
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
            //   * Generic URL probe (no auth, "Test custom URL" on
            //     /proxy): operator's mental model is "did this URL
            //     load?". A 200 / 3xx redirect = yes. A 4xx (404,
            //     403, 401, 410) = the URL doesn't serve content =
            //     no, even though the connection succeeded. A 5xx =
            //     server error. Earlier "200..499 = ok" treated 404
            //     as success, which surprised operators reading the
            //     ✓ banner -- the URL was clearly broken. 4xx detail
            //     stays in the verbose probe log so the diagnostic
            //     value isn't lost; the verdict just doesn't lie.
            val authoritative = useMirrorAuth && s.hasMirrorAuth
            val ok = if (authoritative) code in 200..399 else code in 200..399
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
            val unwrapped = formatProbeException(e)
            log.appendLine("[err]  $unwrapped")
            DetailedProbe(false, url, viaProxy, -1, ms, unwrapped, log.toString())
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
        // Search order, ranked by "most likely to be a Gradle the runtime
        // JDK can run" (i.e. recent enough to know about JDK 22+ version
        // strings):
        //
        //   1. $AI_BENCH_GRADLE_BIN env var — explicit operator override
        //   2. Repo-checkout gradlew(.bat) — bundled at 9.4.1
        //   3. Newest gradle-9.* in ~/.gradle/wrapper/dists/ — any wrapper
        //      Gradle has bootstrapped on this machine before
        //   4. System-PATH `gradle{.bat}` — last resort; on enterprise
        //      Windows boxes this is often pinned to 8.14, which can't
        //      handle JDK 25 (Kotlin's JavaVersion.parse throws on
        //      "25.0.1"). The pre-flight version check in
        //      startMirrorGradleProbe rejects pre-9.0 binaries with a
        //      clear error rather than letting the operator wait for
        //      the confusing crash.
        val wrapperName = if (Platform.isWindows) "gradlew.bat" else "gradlew"
        val gradleExe = if (Platform.isWindows) "gradle.bat" else "gradle"

        fun usable(f: java.io.File): Boolean =
            f.isFile && (Platform.isWindows || f.canExecute())

        // 1. Explicit operator override.
        System.getenv("AI_BENCH_GRADLE_BIN")?.takeIf { it.isNotBlank() }?.let {
            val f = java.io.File(it)
            if (usable(f)) return f.absolutePath
        }

        // 2. Repo-checkout wrappers.
        val wrapperCandidates = listOf(
            "${System.getProperty("user.dir")}/banking-app/$wrapperName",
            "${System.getProperty("user.dir")}/bench-webui/$wrapperName",
            "${System.getProperty("user.dir")}/bench-cli/$wrapperName",
            "${System.getProperty("user.dir")}/../banking-app/$wrapperName",
            "${System.getProperty("user.dir")}/../bench-webui/$wrapperName"
        )
        wrapperCandidates.firstOrNull { usable(java.io.File(it)) }
            ?.let { return java.io.File(it).absolutePath }

        // 3. Newest gradle-9.* in the wrapper-distribution cache. Layout:
        //    ~/.gradle/wrapper/dists/gradle-9.4.1-bin/<hash>/gradle-9.4.1/bin/gradle{.bat,}
        // The cache survives across project checkouts, so a one-time
        // bench-webui dev run on the same machine puts a 9.4.1 here that
        // the operator's later install-only run can re-use.
        findCachedGradle9(gradleExe)?.let { return it }

        // 4. System PATH fallback.
        val path = System.getenv("PATH") ?: ""
        return path.split(java.io.File.pathSeparator)
            .map { java.io.File(it, gradleExe) }
            .firstOrNull { usable(it) }
            ?.absolutePath
    }

    private fun findCachedGradle9(gradleExe: String): String? {
        val home = System.getProperty("user.home") ?: return null
        val distsDir = java.io.File(home, ".gradle/wrapper/dists")
        if (!distsDir.isDirectory) return null
        // Each top-level entry is e.g. "gradle-9.4.1-bin"; filter to 9.x
        // and pick the highest version (string-sort works because the
        // version segments are zero-padded by the existing 1-2 digit
        // convention, and we only care about >= 9.0).
        val ninePlus = distsDir.listFiles { f ->
            f.isDirectory && Regex("""^gradle-9\.\d+(\.\d+)?(-[a-z]+)?$""").matches(f.name)
        } ?: return null
        return ninePlus.sortedByDescending { it.name }
            .asSequence()
            .flatMap { topLevel ->
                // <hash>/gradle-9.x.y/bin/gradle(.bat) — there's exactly
                // one hash subdir per cached distribution, but iterate
                // defensively just in case.
                val hashes = topLevel.listFiles { f -> f.isDirectory } ?: emptyArray()
                hashes.asSequence().mapNotNull { hash ->
                    val inner = hash.listFiles { f -> f.isDirectory && f.name.startsWith("gradle-9.") }?.firstOrNull()
                    inner?.let { java.io.File(it, "bin/$gradleExe") }
                }
            }
            .firstOrNull { it.isFile && (Platform.isWindows || it.canExecute()) }
            ?.absolutePath
    }

    /**
     * Run `<gradleBin> --version` once to determine the major version.
     * Used by the tier-3 probe to refuse old-Gradle binaries that will
     * deterministically crash on a JDK 22+ runtime. Returns null when
     * the version line can't be parsed (treat as "unknown, try anyway").
     */
    private fun gradleMajorVersion(gradleBin: String): Int? {
        return try {
            val proc = ProcessBuilder(gradleBin, "--version").redirectErrorStream(true).start()
            val ok = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) { proc.destroyForcibly(); return null }
            val out = proc.inputStream.bufferedReader().readText()
            // Match e.g. "Gradle 8.14" or "Gradle 9.4.1"
            Regex("""Gradle\s+(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
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

        // Pre-flight version check. Gradle 8.x bundles a Kotlin compiler
        // whose JavaVersion.parse() doesn't recognize JDK 22+ version
        // strings — `IllegalArgumentException: 25.0.1` blows up the
        // build long before plugin resolution starts. Refuse the run
        // up front with an actionable message instead of leaving the
        // operator to decode a Kotlin stack trace.
        val gradleMajor = gradleMajorVersion(gradleBin)
        val runtimeJavaMajor = runCatching {
            System.getProperty("java.specification.version")?.toIntOrNull()
        }.getOrNull()
        state.appendLog("[info] gradle version: " + (gradleMajor?.let { "$it.x" } ?: "(could not parse)"))
        state.appendLog("[info] webui jdk: " + (runtimeJavaMajor?.toString() ?: "(unknown)"))
        if (gradleMajor != null && gradleMajor < 9 &&
            runtimeJavaMajor != null && runtimeJavaMajor >= 22) {
            state.appendLog("[err] Gradle $gradleMajor.x can't run under JDK $runtimeJavaMajor — its bundled")
            state.appendLog("[err] Kotlin compiler crashes on the version string '$runtimeJavaMajor.0.x'.")
            state.appendLog("[err] Fix one of:")
            state.appendLog("[err]   - install Gradle 9.0+ and put it on PATH, OR")
            state.appendLog("[err]   - set the AI_BENCH_GRADLE_BIN env var to a newer wrapper, OR")
            state.appendLog("[err]   - run bench-webui under JDK 17-21 (the test still drives plugin")
            state.appendLog("[err]     resolution against the configured mirror; only the *bench-webui's*")
            state.appendLog("[err]     own JDK matters here, not the banking-app toolchain).")
            state.result = GradleProbeResult(false, -1, 0, gradleBin, "", state.snapshotLog(),
                "Found Gradle $gradleMajor.x at $gradleBin; needs Gradle 9.0+ to run under JDK $runtimeJavaMajor.")
            state.done = true
            return state
        }

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
        //    NO_PROXY / no_proxy are checked alongside the proxy URLs --
        //    enterprise Windows boxes commonly push the bypass list as a
        //    system env var and leave the IE registry empty, so without
        //    this branch the noProxy field would never auto-fill.
        val envHttps = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: ""
        val envHttp  = System.getenv("HTTP_PROXY")  ?: System.getenv("http_proxy")  ?: ""
        val envNo    = System.getenv("NO_PROXY")    ?: System.getenv("no_proxy")    ?: ""
        if (envHttps.isNotBlank() || envHttp.isNotBlank() || envNo.isNotBlank()) {
            log.appendLine("[env] HTTPS_PROXY=$envHttps HTTP_PROXY=$envHttp NO_PROXY=$envNo")
            if (https.isBlank())   https = envHttps
            if (http.isBlank())    http = envHttp
            if (noProxy.isBlank()) noProxy = envNo
            if (https.isNotBlank() || http.isNotBlank() || noProxy.isNotBlank()) {
                sources += "environment variables"
            }
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
        val builder = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            // Follow 3xx redirects so the verdict reflects the FINAL
            // endpoint, not the first hop. services.gradle.org returns
            // HTTP 307 to its CDN; under the JDK default
            // Redirect.NEVER that surfaced as the literal redirect
            // status, which read as "the gradle distribution server
            // is failing" even though following the 307 reaches the
            // real endpoint cleanly. NORMAL refuses HTTPS→HTTP
            // downgrade so we can't be tricked into transmitting auth
            // headers in cleartext on a hostile redirect chain.
            .followRedirects(HttpClient.Redirect.NORMAL)
        currentProxySelector()?.let { builder.proxy(it) }
        if (current.insecureSsl) {
            builder.sslContext(trustAllSslContext())
            // JDK HttpClient defaults to "HTTPS" endpoint identification,
            // which still verifies the cert SAN/CN against the requested
            // hostname even when the trust manager accepts every chain.
            // Corp-MITM certs commonly carry a wildcard that doesn't match
            // every upstream — and that mismatch surfaces as
            // SSLHandshakeException, exactly the symptom operators see
            // when "ignore TLS errors" appears to do nothing for hosts like
            // services.gradle.org / api.foojay.io. Clearing the algorithm
            // makes insecure mode actually insecure end-to-end.
            val params = SSLParameters()
            params.endpointIdentificationAlgorithm = ""
            builder.sslParameters(params)
        } else {
            // Default JDK HttpClient trusts $JAVA_HOME/lib/security/cacerts
            // only — it does NOT consult the macOS Keychain or the
            // Windows-ROOT store. Enterprise users put their corp MITM
            // root in the OS keychain (so Safari, Chrome, curl all work)
            // and are then surprised when the JVM still throws
            // SSLHandshakeException. Layer the OS roots on top of cacerts
            // so the JVM sees the same trust set as the rest of the OS.
            osAugmentedSslContext()?.let { builder.sslContext(it) }
        }
        return builder.build()
    }

    /**
     * Mask credential values inside any `-D*Password=...` /
     * `-D*orgInternalMavenPassword=...` arg so the result is safe
     * to render in the UI, persist on a SeedAudit, log, or paste
     * into a bug report. Pass `gradleSystemProps()` through this
     * before any operator-visible surface; ALWAYS use the masked
     * form for `verificationCommand` on persisted audits.
     */
    fun maskCredentialArgs(args: List<String>): List<String> {
        // Match anything ending in `Password=...` or
        // `passw...=...` (case-insensitive) so future credential-
        // shaped flags (mirrorAuthPassword, jdk.http.auth.password,
        // etc.) get covered without per-key bookkeeping.
        val maskRe = Regex("(-D[^=]*(?:[Pp]assword|[Pp]asswd)[^=]*)=.*")
        return args.map { arg ->
            maskRe.matchEntire(arg)?.let { "${it.groupValues[1]}=********" } ?: arg
        }
    }

    /** Mask any `-D*Password=cleartext` substring inside a joined
     *  command-line string. Use on `cmd.joinToString(" ")` shapes
     *  before persisting / logging / displaying. */
    fun maskCredentialArgsInLine(line: String): String {
        return line.replace(
            Regex("(-D[^=\\s]*(?:[Pp]assword|[Pp]asswd)[^=\\s]*=)[^\\s]+"),
            "$1********"
        )
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
        // -Dhttp.nonProxyHosts deliberately NOT added here. The JVM's
        // separator is '|', which cmd.exe interprets as a pipe and
        // uses to split the command line -- a value like
        //   -Dhttp.nonProxyHosts=*.corp.com|*.corp.net
        // makes Windows try to exec `*.corp.net` as its own command and
        // fails with "is not recognized as an internal or external
        // command". writeGradleProxyProperties writes the same value to
        // ~/.gradle/gradle.properties as systemProp.http.nonProxyHosts,
        // which the Gradle daemon picks up at start without ever going
        // through a shell -- so plugin/dep resolution still respects it.

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
            // When the operator has flagged that direct egress to
            // repo.maven.apache.org is blocked at the corp proxy
            // (so Maven Central content reaches the build only via
            // the Artifactory mirror), banking-app/build.gradle.kts
            // reads this sysprop and drops its mavenCentral()
            // last-ditch fallback. Without this, gradle would still
            // try the upstream and fail with 403 from the proxy.
            if (current.centralViaMirrorOnly) {
                args += "-DcentralViaMirror=true"
            }
            // Pipe the external/maven-virtual URL through too. When
            // set, banking-app/build.gradle.kts adds it as a separate
            // Maven repo source (the corp Artifactory's Central
            // proxy lives at this URL, distinct from the internal
            // virtual at -Denterprise.sim.mirror).
            if (current.hasMavenExternalVirtual) {
                args += "-DmavenExternalVirtual=${current.mavenExternalVirtualUrl}"
            }
        }
        // Note on insecureSsl: this toggle now scopes ONLY to the
        // bench-webui's own HttpClient (probes, mirror tests, AppMap
        // Navie / Copilot bridge calls). For gradle subprocesses we
        // intentionally do NOT emit -Dcom.sun.net.ssl.checkRevocation=
        // false / -Dtrust_all_cert=true: the former only disables
        // OCSP/CRL revocation checks (chain trust still happens), and
        // the latter is a non-standard property the JDK's default
        // SSLContext ignores entirely. Both were vestigial best-effort
        // hints that misled operators into thinking gradle was running
        // with cert validation off when in reality the merged truststore
        // below (cacerts ∪ OS keychain, written every save) is what
        // actually let the corp MITM CA validate. The truststore IS
        // emitted unconditionally so corp deployments work without the
        // toggle being on.
        // Point the gradle subprocess at the merged truststore (JDK
        // cacerts ∪ OS keychain). Without this, a corp MITM root that
        // the operator has installed in macOS Keychain / Windows-ROOT
        // is invisible to the JVM running gradle, so banking-app +
        // AppMap recording fail with the same SSLHandshakeException
        // the WebUI probes used to fail with. Skipped when the merge
        // returns null (Linux, or when both stores fail to load).
        managedTrustStorePath()?.let { path ->
            args += "-Djavax.net.ssl.trustStore=$path"
            args += "-Djavax.net.ssl.trustStorePassword=$MANAGED_TRUSTSTORE_PASSWORD"
            args += "-Djavax.net.ssl.trustStoreType=JKS"
        }
        return args
    }

    private val MANAGED_TRUSTSTORE_PASSWORD = "changeit"
    // Filename bumped to v2 so existing operator caches (built by an
    // earlier bench-webui that hardcoded `KeyStore.getInstance("JKS")`
    // for cacerts on JDK 9+, where cacerts is actually PKCS12) get
    // replaced automatically. The old broken file was missing the
    // ~150 public CA roots cacerts ships with -- that's what caused
    // the regression where gradle's TLS handshakes started failing
    // with "Remote host terminated the handshake" after PR #43 fixed
    // the Windows path-escape bug (which had been silently making
    // gradle fall back to JDK default cacerts before).
    private val managedTrustStoreFile: java.io.File by lazy {
        val dir = java.io.File(System.getProperty("user.home"), ".aibench")
        dir.mkdirs()
        java.io.File(dir, "truststore-v2.jks")
    }

    /**
     * Materialize a merged JKS truststore (JDK cacerts ∪ OS keychain)
     * at <code>~/.aibench/truststore-v2.jks</code> and return the path.
     * Used by banking-app + AppMap gradle subprocesses (via
     * <code>gradleSystemProps()</code> and the matching
     * <code>~/.gradle/gradle.properties</code> entries written from
     * <code>writeGradleProxyProperties</code>) so subprocess TLS trust
     * lines up with the WebUI's HttpClient.
     *
     * Cached: builds once and reuses the file thereafter. To force a
     * rebuild (e.g. after a corp CA rotation), the operator can delete
     * the file -- the next call notices it missing and regenerates.
     */
    fun managedTrustStorePath(): String? {
        if (managedTrustStoreFile.exists() && managedTrustStoreFile.length() > 0) {
            return managedTrustStoreFile.absolutePath
        }
        return runCatching {
            val merged = KeyStore.getInstance("JKS").also {
                it.load(null, MANAGED_TRUSTSTORE_PASSWORD.toCharArray())
            }
            val cacertsCount = loadJdkCacertsInto(merged)
            val osCount = loadOsKeychainInto(merged)
            if (cacertsCount == 0 && osCount == 0) {
                // Empty truststore is worse than no truststore -- the
                // JVM would reject every cert. Don't write the file;
                // gradle will fall back to its own default cacerts.
                log.warn("Could not load any certs into merged truststore " +
                    "(cacerts=$cacertsCount, os=$osCount). Skipping write; " +
                    "gradle subprocesses will use JDK default cacerts.")
                return@runCatching null
            }
            managedTrustStoreFile.outputStream().use {
                merged.store(it, MANAGED_TRUSTSTORE_PASSWORD.toCharArray())
            }
            log.info("Built merged truststore at {} ({} cacerts + {} OS = {} total entries).",
                managedTrustStoreFile, cacertsCount, osCount, merged.size())
            managedTrustStoreFile.absolutePath
        }.getOrElse {
            log.warn("Could not build merged truststore for gradle subprocesses. Cause: {}", it.message)
            null
        }
    }

    /** Load the JDK's default trust roots into [merged]. Returns the
     *  number of certs added.
     *
     *  This used to read $JAVA_HOME/lib/security/cacerts directly via
     *  KeyStore.getInstance("JKS"), but JDK 9+ ships cacerts as
     *  PKCS12. The hardcoded JKS load silently failed under
     *  runCatching, leaving the merged store with ZERO public CA
     *  roots and only OS-keychain certs. Result: gradle TLS
     *  handshakes failed against any host whose chain root was in
     *  cacerts but not the OS keychain ("Remote host terminated the
     *  handshake" was the canonical symptom).
     *
     *  Now uses the standard pattern -- TrustManagerFactory.init(null)
     *  which asks the JDK for its default trust managers (regardless
     *  of cacerts file format) -- then copies their accepted issuers
     *  into the merged store. Works on JDK 8 (JKS), JDK 9+ (PKCS12),
     *  and any future format change. */
    private fun loadJdkCacertsInto(merged: KeyStore): Int {
        return runCatching {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            var n = 0
            tmf.trustManagers.filterIsInstance<X509TrustManager>().forEach { tm ->
                tm.acceptedIssuers.forEach { cert ->
                    // Use the SubjectDN as the alias suffix so duplicates
                    // (a cert appearing in multiple trust managers) get
                    // deduped naturally by KeyStore.setCertificateEntry.
                    val alias = "jdk-" + cert.subjectX500Principal.name.hashCode().toString(16)
                    merged.setCertificateEntry(alias, cert)
                    n++
                }
            }
            n
        }.getOrElse {
            log.warn("Could not load JDK default trust roots into merged truststore: {}", it.message)
            0
        }
    }

    /** Load OS-native trust roots (macOS Keychain, Windows-ROOT) into
     *  [merged]. Returns the number of certs added. Linux is a no-op:
     *  cacerts on most Linux distros already mirrors /etc/ssl/certs. */
    private fun loadOsKeychainInto(merged: KeyStore): Int {
        val osName = System.getProperty("os.name", "").lowercase()
        val osType = when {
            osName.contains("mac") || osName.contains("darwin") -> "KeychainStore"
            osName.contains("win")                              -> "Windows-ROOT"
            else                                                 -> return 0
        }
        return runCatching {
            val osKs = KeyStore.getInstance(osType).also { it.load(null, null) }
            var n = 0
            osKs.aliases().toList().forEach { alias ->
                osKs.getCertificate(alias)?.let {
                    merged.setCertificateEntry("os-$alias", it)
                    n++
                }
            }
            n
        }.getOrElse {
            log.warn("Could not load OS keychain into merged truststore: {}", it.message)
            0
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Hostnames that should bypass the proxy in addition to the
     * operator-typed `noProxy` field. Currently sources the mirror's
     * hostname (and the maven-external-virtual's, when configured)
     * when the `mirrorBypassProxy` toggle is on. Returns empty when
     * the toggle is off so the legacy "everything goes through the
     * proxy" behaviour is preserved.
     *
     * Internal corp Artifactory typically lives on a private subnet
     * the corp proxy either can't reach or refuses to MITM cleanly;
     * surfacing this as a single toggle is much friendlier than
     * asking the operator to manually paste the hostname into the
     * /proxy noProxy field every time the mirror URL changes.
     */
    fun effectiveBypassHosts(): List<String> {
        if (!current.mirrorBypassProxy) return emptyList()
        val hosts = mutableListOf<String>()
        runCatching { java.net.URI.create(current.mirrorUrl).host }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { hosts += it }
        if (current.hasMavenExternalVirtual) {
            runCatching { java.net.URI.create(current.mavenExternalVirtualUrl).host }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { hosts += it }
        }
        return hosts
    }

    private fun currentProxySelector(): ProxySelector? {
        val httpsHostPort = parseHostPort(current.httpsProxy)
        val httpHostPort = parseHostPort(current.httpProxy)
        if (httpsHostPort == null && httpHostPort == null) return null

        // Operator-saved no_proxy list, plus the auto-bypass hosts
        // contributed by mirrorBypassProxy. Computed each call so a
        // fresh /mirror save takes effect without rebuilding the
        // selector.
        val noProxyPatterns = (current.noProxy.split(",") + effectiveBypassHosts())
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

    /**
     * Build an SSLContext whose trust set is the *union* of the JVM's
     * default cacerts AND the OS-native truststore (macOS Keychain or
     * Windows-ROOT). This is the right default for enterprise: corp IT
     * pushes the MITM root into the OS keychain, and we want the JVM
     * to see the same world. Returns null on Linux (or when neither
     * store loads cleanly), in which case the caller leaves the JDK
     * default in place.
     *
     * Expensive enough that we cache the constructed context — trust
     * material doesn't change between operator clicks, and rebuilding
     * a TrustManagerFactory per probe was adding ~200ms per call.
     */
    @Volatile private var cachedOsAugmentedCtx: SSLContext? = null
    @Volatile private var cachedOsAugmentedAttempted: Boolean = false
    private fun osAugmentedSslContext(): SSLContext? {
        if (cachedOsAugmentedAttempted) return cachedOsAugmentedCtx
        cachedOsAugmentedAttempted = true
        cachedOsAugmentedCtx = runCatching {
            val osTms = loadOsTrustManagers() ?: return@runCatching null
            val cacertsTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            cacertsTmf.init(null as KeyStore?)
            val combined = unionX509TrustManager(
                osTms.filterIsInstance<X509TrustManager>() +
                cacertsTmf.trustManagers.filterIsInstance<X509TrustManager>()
            )
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(combined), SecureRandom())
            log.info("SSL trust: layered {} OS root(s) on top of JVM cacerts.", combined.acceptedIssuers.size)
            ctx
        }.getOrElse {
            log.warn("OS-augmented SSL trust setup failed; falling back to JVM cacerts. Cause: {}", it.message)
            null
        }
        return cachedOsAugmentedCtx
    }

    private fun loadOsTrustManagers(): Array<TrustManager>? {
        val osName = System.getProperty("os.name", "").lowercase()
        val storeType = when {
            osName.contains("mac") || osName.contains("darwin") -> "KeychainStore"
            osName.contains("win")                              -> "Windows-ROOT"
            else                                                 -> return null  // Linux — cacerts already pulls from OS
        }
        return runCatching {
            val ks = KeyStore.getInstance(storeType)
            ks.load(null, null)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            tmf.trustManagers
        }.getOrNull()
    }

    /**
     * Combine multiple X509TrustManagers into one that accepts a chain
     * if ANY underlying manager accepts it. Used to union the OS
     * keychain with the JVM cacerts so neither half loses.
     */
    private fun unionX509TrustManager(parts: List<X509TrustManager>): X509TrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                var lastErr: Exception? = null
                for (p in parts) {
                    try { p.checkClientTrusted(chain, authType); return } catch (e: Exception) { lastErr = e }
                }
                throw lastErr ?: java.security.cert.CertificateException("No trust managers configured")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                var lastErr: Exception? = null
                for (p in parts) {
                    try { p.checkServerTrusted(chain, authType); return } catch (e: Exception) { lastErr = e }
                }
                throw lastErr ?: java.security.cert.CertificateException("No trust managers configured")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                parts.flatMap { it.acceptedIssuers.asList() }.toTypedArray()
        }

    /**
     * Unwrap the cause chain on a probe exception so the operator sees
     * the actual root cause. SSLHandshakeException by itself reads as a
     * generic "handshake failed"; the chain underneath spells out PKIX
     * path failure / unknown CA / hostname mismatch / etc. — which is
     * what the operator needs to fix the trust config.
     */
    fun formatProbeException(e: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = e
        var depth = 0
        val seen = mutableSetOf<Throwable>()
        while (cur != null && depth < 6 && seen.add(cur)) {
            val name = cur::class.java.simpleName
            val msg = cur.message?.trim().orEmpty()
            parts += if (msg.isNotEmpty()) "$name: $msg" else name
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" → ")
    }

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

    /**
     * Detailed proxy URL parse result. Surfaced to the /proxy
     * Active-settings UI + the verify panel so the operator sees
     * EXACTLY how their saved value was interpreted (including
     * any port that bench-webui defaulted in their absence).
     */
    data class ParsedProxy(
        val host: String,
        val port: String,
        /** True when the operator's URL had no explicit port and we
         *  defaulted based on the scheme (or fell back to 8080 when
         *  no scheme was present). Surfaced as a UI hint so the
         *  operator can correct if our guess is wrong. */
        val portDefaulted: Boolean,
        /** "https" / "http" / "" -- empty when the operator pasted
         *  a bare host:port without a scheme. */
        val scheme: String
    )

    /** Lenient parse of an operator-saved proxy URL.
     *
     * Previously returned null whenever a port wasn't explicitly
     * specified, which caused gradleSystemProps() to silently DROP
     * the -Dhttps.proxyHost/-Dhttps.proxyPort args -- operators on
     * corp networks who entered "https://proxy.corp.com" (no port)
     * would see "proxy: <unset>" in their AppMap recording log
     * even though they thought their proxy was configured. Now
     * defaults the port based on the scheme:
     *   https://X        -> port 443
     *   http://X         -> port 80
     *   X (no scheme)    -> port 8080  (typical corp HTTP-CONNECT proxy)
     * and surfaces a `portDefaulted=true` flag so the UI can
     * prompt the operator to confirm.
     */
    fun parseProxy(url: String): ParsedProxy? {
        if (url.isBlank()) return null
        // Aggressive cleanup -- the operator may paste anything
        // from the bare `proxy.corp.com:8080` form to a fully-qualified
        // `https://proxy.corp.com:8080/path?x=1` URL with surrounding
        // whitespace.
        val raw = url.trim()
        val schemeMatch = Regex("""^(https?)://""", RegexOption.IGNORE_CASE).find(raw)
        val scheme = schemeMatch?.groupValues?.get(1)?.lowercase().orEmpty()
        var cleaned = if (schemeMatch != null) raw.substring(schemeMatch.range.last + 1) else raw
        // Drop user:pass@ if present.
        cleaned.indexOf('@').let { at -> if (at >= 0) cleaned = cleaned.substring(at + 1) }
        // Drop path / query.
        cleaned = cleaned.substringBefore('/').substringBefore('?')
        cleaned = cleaned.trim().trim('/')
        if (cleaned.isBlank()) return null
        val parts = cleaned.split(":")
        val host = parts[0].trim()
        if (host.isBlank()) return null
        // Take an explicit port if present; otherwise default based
        // on scheme.
        val explicitPort = if (parts.size >= 2) parts[1].takeWhile { it.isDigit() } else ""
        val port: String
        val defaulted: Boolean
        if (explicitPort.isNotBlank()) {
            port = explicitPort
            defaulted = false
        } else {
            port = when (scheme) {
                "https" -> "443"
                "http"  -> "80"
                else    -> "8080"
            }
            defaulted = true
            log.warn("Proxy URL '{}' had no explicit port — defaulted to {} " +
                "(scheme={}). If your proxy listens on a different port, edit " +
                "/proxy and add ':<port>'.", url, port, scheme.ifEmpty { "(none)" })
        }
        return ParsedProxy(host, port, defaulted, scheme)
    }

    /** Backwards-compat shim for the existing call sites that just
     *  want (host, port). Honors the same lenient defaulting as
     *  parseProxy(); existing call sites get the upgrade for free. */
    private fun parseHostPort(url: String): Pair<String, String>? =
        parseProxy(url)?.let { it.host to it.port }

    private fun writeGradleProxyProperties(s: Settings) {
        // Always run when ~/.gradle/gradle.properties exists, regardless
        // of whether the new Settings has anything to write. Skipping
        // when "nothing to manage" leaks a stale entry from a previous
        // /proxy config -- e.g. the operator clears the proxy field in
        // the form, but the dead `systemProp.http.proxyHost=...` lines
        // stay in gradle.properties forever, and every later Gradle
        // invocation tries to route through a now-unreachable host.
        // The filter+rewrite always strips the keys we manage; the
        // re-emit only happens when the new Settings actually has them.
        val gradleDir = java.io.File(System.getProperty("user.home"), ".gradle")
        val propsFile = java.io.File(gradleDir, "gradle.properties")
        val hasAnything = s.httpsProxy.isNotEmpty() || s.httpProxy.isNotEmpty() ||
                          s.noProxy.isNotEmpty() || s.hasMirrorAuth || s.hasMirror ||
                          s.hasProxyAuth || s.artifactoryRepoKey.isNotBlank()
        // No new state AND no pre-existing file = nothing to do, no
        // need to even create the dir.
        if (!hasAnything && !propsFile.exists()) return
        gradleDir.mkdirs()
        // The full set of keys this writer owns -- filtered out of any
        // existing file before we re-emit them below. Keeping the list
        // central avoids a stale entry surviving when we widen the
        // managed surface (the next /mirror save would otherwise leave
        // a duplicate next to the new one).
        val managedPrefixes = listOf(
            // Proxy host / port (both schemes).
            "systemProp.http.proxyHost",
            "systemProp.http.proxyPort",
            "systemProp.https.proxyHost",
            "systemProp.https.proxyPort",
            // Proxy-auth user / password (both schemes). Newly managed
            // -- gradle's HTTP layer reads these for HTTP Basic against
            // the proxy, mirroring the -D args we already pass via
            // gradleSystemProps(). Writing them to disk lets gradle
            // builds launched OUTSIDE bench-webui (e.g. an operator
            // running ./gradlew at a shell) authenticate without
            // re-typing creds.
            "systemProp.http.proxyUser",
            "systemProp.http.proxyPassword",
            "systemProp.https.proxyUser",
            "systemProp.https.proxyPassword",
            // Comma-vs-pipe-separated nonProxyHosts (gradle uses the
            // pipe form internally; we translate from no_proxy syntax).
            "systemProp.http.nonProxyHosts",
            // TLS-trust keys we manage so the gradle daemon picks up
            // the merged truststore (cacerts ∪ OS keychain) we hand
            // the WebUI HttpClient. Wiped + re-emitted on every save
            // so a rotated cert shows up cleanly.
            "systemProp.javax.net.ssl.trustStore",
            // Legacy keys consumed by the corp init script's
            // providers.gradleProperty(...) calls. Kept for
            // back-compat alongside the artifactory_* names below.
            "orgInternalMavenUser",
            "orgInternalMavenPassword",
            // Standard Artifactory naming. Newly managed -- enterprise
            // gradle scripts commonly read these via
            // providers.gradleProperty("artifactory_user") etc. The
            // contextUrl mirrors `mirrorUrl`; user/password mirror
            // the mirror-auth fields; repoKey is its own field on the
            // /mirror form (e.g. "libs-release", "maven-virtual").
            "artifactory_contextUrl",
            "artifactory_user",
            "artifactory_password",
            "artifactory_repoKey",
        )
        val existingLines = if (propsFile.exists()) {
            propsFile.readLines().filter { line -> managedPrefixes.none { line.startsWith(it) } }
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
        if (s.hasProxyAuth) {
            newLines.add("systemProp.http.proxyUser=${s.proxyAuthUser}")
            newLines.add("systemProp.http.proxyPassword=${s.proxyAuthPassword}")
            newLines.add("systemProp.https.proxyUser=${s.proxyAuthUser}")
            newLines.add("systemProp.https.proxyPassword=${s.proxyAuthPassword}")
        }
        // nonProxyHosts: union of operator-typed noProxy and the
        // auto-bypass hosts contributed by the mirrorBypassProxy
        // toggle (typically the corp Artifactory hostname). Joined
        // with '|' which is gradle's separator. Empty list means
        // we don't emit the line at all so an unconfigured proxy
        // doesn't get a stray nonProxyHosts entry.
        val combinedNoProxy = (
            s.noProxy.split(",").map { it.trim() } + effectiveBypassHosts()
        ).filter { it.isNotEmpty() }.distinct()
        if (combinedNoProxy.isNotEmpty()) {
            newLines.add("systemProp.http.nonProxyHosts=" + combinedNoProxy.joinToString("|"))
        }
        if (s.hasMirrorAuth) {
            // Legacy + standard names -- corp init script reads the
            // orgInternalMaven* pair, generic enterprise gradle
            // scripts read the artifactory_* pair. Emit both so
            // either consumer works without coordination.
            newLines.add("orgInternalMavenUser=${s.mirrorAuthUser}")
            newLines.add("orgInternalMavenPassword=${s.mirrorAuthPassword}")
            newLines.add("artifactory_user=${s.mirrorAuthUser}")
            newLines.add("artifactory_password=${s.mirrorAuthPassword}")
        }
        if (s.hasMirror) {
            newLines.add("artifactory_contextUrl=${s.mirrorUrl}")
        }
        if (s.artifactoryRepoKey.isNotBlank()) {
            newLines.add("artifactory_repoKey=${s.artifactoryRepoKey}")
        }
        managedTrustStorePath()?.let { rawPath ->
            // gradle.properties is a java.util.Properties file:
            // backslashes in VALUES are interpreted as escape
            // sequences (\t = TAB, \u = unicode, \r/\n = newlines).
            // Operators on Windows reported "Trust store file
            // …aibench ruststore.jks does not exist" — the literal
            // path "C:\Users\me\.aibench\truststore.jks" was being
            // parsed as "C:\Users\me\.aibench" + TAB + "ruststore.jks"
            // because \t got eaten as an escape. Convert backslashes
            // to forward slashes; java.io.File accepts forward slashes
            // as a path separator on Windows, so the file resolves
            // correctly AND the .properties value carries no escape
            // sequences. Same fix for the password (no backslashes
            // expected, but guard belt-and-suspenders) and type.
            val safePath = rawPath.replace('\\', '/')
            newLines.add("systemProp.javax.net.ssl.trustStore=$safePath")
            newLines.add("systemProp.javax.net.ssl.trustStorePassword=$MANAGED_TRUSTSTORE_PASSWORD")
            newLines.add("systemProp.javax.net.ssl.trustStoreType=JKS")
        }
        propsFile.writeText(newLines.joinToString("\n") + "\n")
    }

    /**
     * Regenerate ~/.gradle/init.d/corp-repos.gradle.kts to match the
     * current bench-webui mirror config. The init script controls every
     * Gradle invocation on the user's account (it rewrites repo URLs to
     * the corp Artifactory and substitutes plugin ids → real Maven
     * coords), so a stale mirror URL baked in from an earlier
     * build-health-check run will silently route every build to a
     * non-existent virtual.
     *
     * Behaviour:
     *  - mirror configured AND not bypassed → write the script with
     *    the current mirror URL substituted for the template's
     *    `__CORP_MAVEN_URL__` placeholder.
     *  - mirror cleared OR bypass-mirror toggle on → DELETE the script
     *    so `gradlePluginPortal()` / `mavenCentral()` resolve direct
     *    via the proxy instead of through a (possibly stale) mirror.
     *
     * The template is bundled into the fat jar from
     * scripts/corp-repos.gradle.kts.template via processResources, so
     * the source-of-truth is the same file build-health-check reads.
     */
    /**
     * Write or remove ~/.gradle/init.d/aibench-trust-all.gradle.kts
     * based on the operator's "Ignore SSL certificate errors" toggle.
     *
     * Until now the insecureSsl toggle ONLY scoped to bench-webui's
     * own HttpClient -- gradle subprocesses still validated certs
     * against the merged truststore. Operators reported gradle
     * connection failures with "SSLHandshakeException: Remote host
     * terminated the handshake" while their toggle was on, expecting
     * the toggle to bypass cert validation everywhere. This init
     * script makes the toggle live up to its name: when on, every
     * gradle invocation on this user account installs a trust-all
     * SSLContext as the JVM default at startup, so daemon + test
     * forks + plugin HTTP all bypass cert validation. When off,
     * the script is deleted so the next gradle invocation falls
     * back to standard validation against the merged truststore.
     *
     * NOTE: Affects EVERY gradle invocation on this user account
     * (init.d/ scripts apply globally), not just the ones bench-webui
     * spawns. That's intentional: the operator's stated intent is
     * "I want to ignore SSL errors", which has to apply uniformly
     * for shell ./gradlew calls to behave the same as bench-webui-
     * spawned ones.
     */
    private fun writeInsecureSslInitScript(s: Settings) {
        val initDir = java.io.File(System.getProperty("user.home"), ".gradle/init.d")
        val target = java.io.File(initDir, "aibench-trust-all.gradle.kts")
        if (!s.insecureSsl) {
            if (target.exists()) {
                runCatching { target.delete() }
                    .onSuccess { log.info("Removed ~/.gradle/init.d/aibench-trust-all.gradle.kts (insecureSsl toggled off).") }
            }
            return
        }
        runCatching {
            initDir.mkdirs()
            target.writeText("""
                // Generated by bench-webui from /proxy "Ignore SSL certificate
                // errors" toggle. Installs a trust-all SSLContext as the JVM
                // default at gradle startup so daemon + test forks + plugin
                // HTTP all bypass cert validation. Removed automatically when
                // the operator toggles the option off.
                //
                // SECURITY: this disables cert validation for EVERY gradle
                // invocation on this user account, not just bench-webui's.
                // It should only be on when you genuinely cannot get the
                // corp MITM CA into the OS keychain or merged truststore.
                import javax.net.ssl.HostnameVerifier
                import javax.net.ssl.HttpsURLConnection
                import javax.net.ssl.SSLContext
                import javax.net.ssl.TrustManager
                import javax.net.ssl.X509TrustManager
                import java.security.SecureRandom
                import java.security.cert.X509Certificate

                val trustAll = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                }
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
                SSLContext.setDefault(ctx)
                HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
                HttpsURLConnection.setDefaultHostnameVerifier(
                    HostnameVerifier { _, _ -> true })

                println("[aibench] insecure SSL: trust-all SSLContext installed for this gradle process.")
            """.trimIndent() + "\n")
            log.info("Wrote ~/.gradle/init.d/aibench-trust-all.gradle.kts (insecureSsl toggled on).")
        }.onFailure {
            log.warn("Could not write aibench-trust-all init script: {}", it.message)
        }
    }

    private fun writeCorpInitScript(s: Settings) {
        val initDir = java.io.File(System.getProperty("user.home"), ".gradle/init.d")
        val target = java.io.File(initDir, "corp-repos.gradle.kts")

        if (!s.hasMirror || s.bypassMirror) {
            if (target.exists()) {
                runCatching { target.delete() }
                    .onSuccess { log.info("Removed ~/.gradle/init.d/corp-repos.gradle.kts (mirror cleared/bypassed).") }
                    .onFailure { log.warn("Could not remove $target: ${it.message}") }
            }
            return
        }

        val templateBytes = this::class.java.getResourceAsStream("/init-scripts/corp-repos.gradle.kts.template")
            ?.use { it.readBytes() }
        if (templateBytes == null) {
            log.warn("corp-repos.gradle.kts.template missing from classpath; init script not regenerated.")
            return
        }
        val template = String(templateBytes, Charsets.UTF_8)
        val rendered = template.replace("__CORP_MAVEN_URL__", s.mirrorUrl)
        runCatching {
            initDir.mkdirs()
            target.writeText(rendered, Charsets.UTF_8)
        }.onSuccess {
            log.info("Regenerated ${target.absolutePath} with mirrorUrl='${s.mirrorUrl}'.")
        }.onFailure {
            log.warn("Could not write ${target.absolutePath}: ${it.message}")
        }
    }
}
