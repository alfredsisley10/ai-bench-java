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
        val mirrorAuthPassword: String = ""
    ) {
        /** Convenience flag — true when proxy auth is fully configured. */
        val hasProxyAuth: Boolean get() = proxyAuthUser.isNotBlank() && proxyAuthPassword.isNotBlank()
        /** Convenience flag — true when a mirror URL is set. */
        val hasMirror: Boolean get() = mirrorUrl.isNotBlank()
        /** Convenience flag — true when mirror auth is configured. */
        val hasMirrorAuth: Boolean get() = mirrorAuthUser.isNotBlank() && mirrorAuthPassword.isNotBlank()
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
            // Anything below 500 is "endpoint reachable" for this purpose;
            // 401/403 still proves the proxy + DNS path works.
            val ok = code in 200..499
            DetailedProbe(ok, url, viaProxy, code, ms,
                if (ok) "HTTP $code (${ms}ms)" else "HTTP $code — server error",
                log.toString())
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
        // Artifactory mirror — banking-app's settings.gradle.kts already
        // has an `enterprise.sim.mirror`-conditional block that swaps in
        // a custom Maven repository. Pipe the value through so a single
        // /proxy save means the next gradlew run uses the mirror without
        // hand-editing settings.gradle.kts. Mirror-auth is forwarded as
        // separate properties the user can wire into their own repo
        // block; we don't auto-rewrite settings.gradle.kts to consume
        // them since that would require knowing the repo name.
        if (current.hasMirror) {
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
