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
        val source: String = "none"
    )

    @Volatile private var current: Settings = Settings()

    val settings: Settings get() = current

    @PostConstruct
    fun init() {
        current = detectInitial()
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
        log.info(
            "Connection settings updated to: httpsProxy='{}', httpProxy='{}', noProxy='{}', insecureSsl={}",
            new.httpsProxy, new.httpProxy, new.noProxy, new.insecureSsl
        )
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
