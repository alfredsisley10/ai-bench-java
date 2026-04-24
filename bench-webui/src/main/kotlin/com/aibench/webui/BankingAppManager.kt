package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import jakarta.annotation.PreDestroy

@Service
class BankingAppManager(
    private val connectionSettings: ConnectionSettings
) {

    private val log = LoggerFactory.getLogger(BankingAppManager::class.java)
    private val processRef = AtomicReference<Process?>(null)
    // Build on demand so toggles made on /proxy take effect immediately
    // (settings are read on each call; localhost is always bypassed).
    private fun http(): HttpClient = connectionSettings.httpClient(Duration.ofSeconds(3))
    private val rng = SecureRandom()

    /**
     * Shared secret regenerated on every banking-app boot. Held only in
     * memory in BOTH this WebUI process and the banking-app subprocess
     * (passed through one env var); never written to disk. The
     * banking-app's OneTimeAutologinFilter validates a single
     * autologin URL exchange against this token, then mints a session.
     */
    @Volatile private var autologinTokenRef: String? = null
    val autologinToken: String? get() = autologinTokenRef

    val bankingAppDir: File
        get() {
            var dir = File(System.getProperty("user.dir"))
            repeat(3) {
                val candidate = dir.resolve("banking-app")
                if (candidate.resolve("gradlew").exists()) return candidate
                dir = dir.parentFile ?: return@repeat
            }
            return File(System.getProperty("user.dir"), "banking-app")
        }

    val port = 8080
    val url get() = "http://localhost:$port"
    val healthUrl get() = "$url/actuator/health"

    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    fun status(): Status {
        val proc = processRef.get()
        if (proc == null) {
            return if (isHealthy()) Status.RUNNING else Status.STOPPED
        }
        if (!proc.isAlive) {
            processRef.set(null)
            return Status.ERROR
        }
        return if (isHealthy()) Status.RUNNING else Status.STARTING
    }

    fun isHealthy(): Boolean = runCatching {
        val req = HttpRequest.newBuilder().uri(URI.create(healthUrl))
            .timeout(Duration.ofSeconds(2)).GET().build()
        val resp = http().send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() == 200
    }.getOrDefault(false)

    fun start(): String = startInternal(withAppMapAgent = false)

    /**
     * Start the banking app with the AppMap Java agent attached so the
     * user can drive the running app from a browser and capture
     * interactive recordings via the agent's `/_appmap/record` endpoint.
     */
    fun startWithAppMapAgent(agentJarPath: String?, configFile: String?): String =
        startInternal(withAppMapAgent = true, agentJarPath = agentJarPath, configFile = configFile)

    @Volatile
    private var startedWithAgent_: Boolean = false
    val startedWithAgent: Boolean get() = startedWithAgent_

    private fun startInternal(
        withAppMapAgent: Boolean,
        agentJarPath: String? = null,
        configFile: String? = null
    ): String {
        if (status() == Status.RUNNING) return "Banking app already running at $url"

        val dir = bankingAppDir
        val wrapper = if (Platform.isWindows) "gradlew.bat" else "gradlew"
        if (!dir.resolve(wrapper).exists()) return "Error: $wrapper not found in ${dir.absolutePath}"

        val javaHome = System.getenv("JAVA_HOME")
            ?: "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

        val cmd = mutableListOf<String>().apply { addAll(Platform.gradleWrapper(dir)); add("bootRun") }
        // Carry the operator's proxy + TLS choices from the WebUI into
        // the Gradle daemon AND the bootRun JVM. Gradle's own HTTP (for
        // dep resolution) honors the -D args the daemon is launched
        // with; the banking-app JVM needs them mirrored via -PjvmArgs.
        val connectionArgs = connectionSettings.gradleSystemProps()
        cmd.addAll(connectionArgs)

        if (withAppMapAgent && !agentJarPath.isNullOrBlank()) {
            val jvmArgs = mutableListOf("-javaagent:$agentJarPath")
            if (!configFile.isNullOrBlank()) jvmArgs.add("-Dappmap.config.file=$configFile")
            // Agent emits trace files under tmp/appmap by default; opt
            // remote-recording on so /_appmap/record is exposed.
            jvmArgs.add("-Dappmap.recording.auto=false")
            jvmArgs.add("-Dappmap.recording.requests=true")
            // Propagate proxy + TLS onto the banking-app JVM too so any
            // HTTP it makes (webhooks, remote caches) goes through the
            // same egress path.
            jvmArgs.addAll(connectionArgs)
            cmd.add("-PjvmArgs=" + jvmArgs.joinToString(" "))
        } else if (connectionArgs.isNotEmpty()) {
            // Without the agent we still want the banking-app JVM to
            // honor the proxy so its outbound calls aren't blocked.
            cmd.add("-PjvmArgs=" + connectionArgs.joinToString(" "))
        }

        val pb = ProcessBuilder(cmd)
            .directory(dir)
            .redirectErrorStream(true)

        pb.environment()["JAVA_HOME"] = javaHome
        pb.environment()["PATH"] = "$javaHome/bin:" + System.getenv("PATH")

        // Mint a fresh autologin token, hand it to the subprocess via
        // env var. Both sides keep it in memory only — never persisted.
        // Rotates on each app start so a stopped+restarted app
        // invalidates any cached browser session.
        val token = generateAutologinToken()
        autologinTokenRef = token
        pb.environment()["OMNIBANK_AUTOLOGIN_TOKEN"] = token

        val logFile = File(dir, "tmp/bootRun.log")
        logFile.parentFile.mkdirs()
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile))

        val proc = pb.start()
        processRef.set(proc)
        startedWithAgent_ = withAppMapAgent
        log.info("Banking app starting (PID {}, withAgent={}), log at {}",
            proc.pid(), withAppMapAgent, logFile.absolutePath)

        val agentSuffix = if (withAppMapAgent) " with AppMap agent attached" else ""
        return "Banking app starting$agentSuffix at $url (PID ${proc.pid()}). Log: ${logFile.absolutePath}"
    }

    fun stop(): String {
        val proc = processRef.getAndSet(null)
        if (proc != null && proc.isAlive) {
            proc.destroy()
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (proc.isAlive) proc.destroyForcibly()
            log.info("Banking app stopped (PID {})", proc.pid())
            return "Banking app stopped."
        }

        // Try to kill anything on port 8080
        runCatching {
            val lsof = ProcessBuilder("lsof", "-ti", ":$port")
                .redirectErrorStream(true).start()
            val pids = lsof.inputStream.bufferedReader().readText().trim()
            if (pids.isNotEmpty()) {
                pids.lines().forEach { pid ->
                    ProcessBuilder("kill", "-9", pid.trim()).start().waitFor()
                }
                return "Banking app stopped (killed PID(s): $pids)."
            }
        }

        return "Banking app was not running."
    }

    fun logTail(lines: Int = 50): String {
        val logFile = File(bankingAppDir, "tmp/bootRun.log")
        if (!logFile.exists()) return "(no log file yet)"
        return logFile.readLines().takeLast(lines).joinToString("\n")
    }

    private fun generateAutologinToken(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Build the autologin URL the WebUI redirects the browser to. The
     * banking-app's OneTimeAutologinFilter exchanges the token for a
     * session cookie and then 302s back to the requested redirect path.
     * Returns null if no banking-app is running with a live token.
     */
    fun autologinUrl(redirectPath: String = "/"): String? {
        val token = autologinTokenRef ?: return null
        val enc = java.net.URLEncoder.encode(redirectPath, "UTF-8")
        return "$url/_demo/autologin?token=$token&redirect=$enc"
    }

    @PreDestroy
    fun cleanup() {
        val proc = processRef.getAndSet(null)
        if (proc != null && proc.isAlive) {
            proc.destroy()
        }
        // Clear the in-memory autologin token; never persisted in the
        // first place, but explicitly nuke the reference on shutdown.
        autologinTokenRef = null
    }
}
