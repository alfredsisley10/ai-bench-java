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

    /**
     * Diagnostic snapshot of where bench-webui looked for banking-app and
     * what shape the resolved location is in. Surfaced verbatim on /demo
     * so users see exactly which paths were tried when the lookup misses.
     *
     * Resolution order:
     *   1. `AI_BENCH_BANKING_APP` env var (explicit override, no validation)
     *   2. Walk up to 6 parents of `user.dir` looking for `banking-app/gradlew`
     *      (handles the common "running from repo root" or "running from
     *      a sibling install dir" cases)
     *   3. Fall back to `${user.dir}/banking-app` (probably non-existent;
     *      surfaced with `source = NOT_FOUND` so the UI can show the help)
     */
    data class Location(
        val resolved: File,
        val exists: Boolean,
        val hasGradle: Boolean,
        val hasSettings: Boolean,
        val source: String,
        val searched: List<String>
    )

    /**
     * Persisted override file. Written by the "Apply" buttons on the
     * /demo "How to fix" panel so the user can point bench-webui at
     * their banking-app checkout from the UI without having to set
     * AI_BENCH_BANKING_APP and restart.
     */
    private val overrideFile: File =
        File(System.getProperty("user.home"), ".ai-bench/banking-app-path.txt")

    @Volatile
    private var runtimeOverride: String? = runCatching {
        if (overrideFile.isFile) overrideFile.readText().trim().takeIf { it.isNotBlank() } else null
    }.getOrNull()

    /**
     * Persist a banking-app path override. Subsequent calls to
     * [location] resolve via this path (after env var, before parent
     * walk). Writes the value under ~/.ai-bench/ so it survives a
     * bench-webui restart.
     */
    fun setRuntimeOverride(path: String) {
        overrideFile.parentFile?.mkdirs()
        overrideFile.writeText(path)
        runtimeOverride = path
    }

    /** Cached hit from the home-dir scan. Reset whenever the cached path
     *  no longer points at a real banking-app/gradlew so we re-scan if
     *  the user moves their checkout. */
    @Volatile
    private var cachedHomeScanHit: File? = null

    /**
     * Walk the user's home directory looking for any nested
     * `banking-app/gradlew`. Bounded by depth and total directory visits
     * to keep latency low even on a large profile. Skips well-known
     * noisy dirs (.git, node_modules, AppData, Library, etc.) so the
     * scan terminates quickly on a typical Windows or macOS profile.
     *
     * Returns the banking-app dir (the one containing gradlew), not the
     * parent — matching the shape used elsewhere in this resolver.
     */
    private fun scanHomeForBankingApp(tried: MutableList<String>): File? {
        cachedHomeScanHit?.let { hit ->
            if (hit.resolve("gradlew").exists()) {
                tried.add("home scan (cached) -> ${hit.absolutePath}")
                return hit
            }
            cachedHomeScanHit = null
        }
        val home = File(System.getProperty("user.home") ?: return null)
        if (!home.isDirectory) return null
        tried.add("home scan: ${home.absolutePath} (depth ≤ 6)")

        val skipNames = setOf(
            ".git", ".gradle", ".m2", ".idea", ".vscode", ".cache",
            "node_modules", "build", "out", "target", "dist",
            "AppData", "Library", "Applications", "Pictures", "Music",
            "Videos", "OneDrive", "Dropbox", "iCloud", "iCloudDrive",
            "Recordings", "Downloads"
        )
        val maxDepth = 6
        val maxVisits = 4000
        var visits = 0

        // Iterative BFS so we can hard-cap visits. Each frame = (dir, depth).
        val queue: ArrayDeque<Pair<File, Int>> = ArrayDeque()
        queue.addLast(home to 0)

        while (queue.isNotEmpty() && visits < maxVisits) {
            val (dir, depth) = queue.removeFirst()
            visits++

            val candidate = dir.resolve("banking-app")
            if (candidate.resolve("gradlew").exists()) {
                cachedHomeScanHit = candidate
                tried.add("home scan -> ${candidate.absolutePath} (visited $visits dirs)")
                return candidate
            }
            if (depth >= maxDepth) continue

            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (!child.isDirectory) continue
                val name = child.name
                if (name.startsWith(".") && name !in setOf(".")) continue
                if (name in skipNames) continue
                // Avoid junctions/symlinks that loop back into the profile.
                if (runCatching { java.nio.file.Files.isSymbolicLink(child.toPath()) }
                        .getOrDefault(false)) continue
                queue.addLast(child to depth + 1)
            }
        }
        if (visits >= maxVisits) {
            tried.add("home scan stopped at $maxVisits dir visits (no hit)")
        }
        return null
    }

    val location: Location
        get() {
            val tried = mutableListOf<String>()
            System.getenv("AI_BENCH_BANKING_APP")?.takeIf { it.isNotBlank() }?.let { p ->
                val f = File(p)
                tried.add("env AI_BENCH_BANKING_APP -> $p")
                if (f.resolve("gradlew").exists()) {
                    return Location(f, true, true, f.resolve("settings.gradle.kts").exists(),
                        "env AI_BENCH_BANKING_APP", tried)
                }
            }
            runtimeOverride?.takeIf { it.isNotBlank() }?.let { p ->
                val f = File(p)
                tried.add("UI override -> $p")
                if (f.resolve("gradlew").exists()) {
                    return Location(f, true, true, f.resolve("settings.gradle.kts").exists(),
                        "UI override (~/.ai-bench/banking-app-path.txt)", tried)
                }
            }
            var dir: File? = File(System.getProperty("user.dir"))
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = dir!!.resolve("banking-app")
                tried.add(candidate.absolutePath)
                if (candidate.resolve("gradlew").exists()) {
                    return Location(candidate, true, true,
                        candidate.resolve("settings.gradle.kts").exists(),
                        "user.dir parent walk", tried)
                }
                dir = dir!!.parentFile
            }
            // Last-ditch: walk the user's profile looking for any nested
            // banking-app/gradlew. Many users keep their checkout under
            // ~/Documents/.../ai-bench-java where the parent walk above
            // never reaches.
            scanHomeForBankingApp(tried)?.let { hit ->
                return Location(hit, true, true, hit.resolve("settings.gradle.kts").exists(),
                    "home-dir scan", tried)
            }
            val fallback = File(System.getProperty("user.dir"), "banking-app")
            return Location(fallback, fallback.isDirectory, false, false, "NOT_FOUND", tried)
        }

    val bankingAppDir: File get() = location.resolved

    /**
     * Result of the "Verify Gradle" diagnostic — runs `gradlew --version`
     * (or `gradlew.bat --version` on Windows) inside the resolved banking
     * app dir and reports the outcome so users see whether their setup
     * can actually launch a build before they kick off a benchmark.
     */
    data class VerifyResult(
        val ok: Boolean,
        val exitCode: Int,
        val durationMs: Long,
        val output: String,
        val message: String
    )

    /**
     * Read the toolchain version requested by banking-app's build
     * config. Used to compare against what's installed and flag a
     * mismatch *before* the user kicks off a multi-minute build that
     * would either fail or silently auto-provision via foojay.
     *
     * Simple regex match — banking-app currently has exactly one
     * `JavaLanguageVersion.of(N)` call in subprojects {} block. If the
     * config grows multiple, we take the first match (which is the
     * subprojects block in practice).
     */
    fun toolchainMajor(): Int? {
        val loc = location
        if (!loc.hasGradle) return null
        val build = File(loc.resolved, "build.gradle.kts")
        if (!build.isFile) return null
        val text = runCatching { build.readText() }.getOrNull() ?: return null
        val m = Regex("""JavaLanguageVersion\.of\((\d+)\)""").find(text) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /**
     * Run `<javaExe> -version` and surface the result as a [VerifyResult]
     * the existing /demo panel rendering can consume. Used by the
     * "Verify Java" button so the user can confirm a specific JDK is
     * working before kicking off the slower Gradle round-trip.
     */
    fun verifyJava(jdkPath: String?): VerifyResult {
        val javaExe = resolveJavaExe(jdkPath)
            ?: return VerifyResult(false, -1, 0, "(no JDK found)",
                "No JDK selected and none auto-detected. Install one or set JAVA_HOME.")
        val pb = ProcessBuilder(javaExe, "-version").redirectErrorStream(true)
        val start = System.currentTimeMillis()
        return try {
            val proc = pb.start()
            val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText().take(8000)
            val ms = System.currentTimeMillis() - start
            if (!finished) {
                runCatching { proc.destroyForcibly() }
                VerifyResult(false, -1, ms, out, "java -version timed out after 15s.")
            } else if (proc.exitValue() == 0) {
                val firstLine = out.lineSequence().map { it.trim() }
                    .firstOrNull { it.isNotBlank() } ?: "(no output)"
                VerifyResult(true, 0, ms, out, "Java works: $firstLine")
            } else {
                VerifyResult(false, proc.exitValue(), ms, out,
                    "java -version exited ${proc.exitValue()}. The binary may be corrupt or missing files.")
            }
        } catch (e: Exception) {
            VerifyResult(false, -1, System.currentTimeMillis() - start, e.toString(),
                "Failed to run java -version: ${e.message}")
        }
    }

    /**
     * Run `gradlew --version`. When [jdkPath] is non-blank, set
     * JAVA_HOME to its root and prepend its bin/ to PATH so the
     * wrapper picks it up. Detects common Gradle/foojay/JDK problems
     * in the output and synthesizes a hint into [VerifyResult.message]
     * so users don't have to read the stack trace to know what to do.
     */
    fun verifyGradle(jdkPath: String? = null): VerifyResult {
        val loc = location
        if (!loc.hasGradle) {
            return VerifyResult(false, -1, 0,
                "(gradlew not found at ${loc.resolved.absolutePath})",
                "Banking app not detected — fix the location first.")
        }
        val cmd = Platform.gradleWrapper(loc.resolved).toMutableList().apply {
            add("--version")
            add("--no-daemon")
        }
        val pb = ProcessBuilder(cmd).directory(loc.resolved).redirectErrorStream(true)

        // If the user selected a JDK in the dropdown, pin JAVA_HOME and
        // PATH for the subprocess so the wrapper picks it up regardless
        // of the operator shell's defaults. Crucial on Windows where
        // the parent process JAVA_HOME may point at JDK 17 but the
        // banking-app needs 21+.
        if (!jdkPath.isNullOrBlank() && File(jdkPath).isDirectory) {
            val env = pb.environment()
            env["JAVA_HOME"] = jdkPath
            val sep = File.pathSeparator
            val binDir = File(jdkPath, "bin").absolutePath
            env["PATH"] = binDir + sep + (env["PATH"] ?: "")
        }
        val start = System.currentTimeMillis()
        return try {
            val proc = pb.start()
            val finished = proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText().take(16000)
            val ms = System.currentTimeMillis() - start
            if (!finished) {
                runCatching { proc.destroyForcibly() }
                VerifyResult(false, -1, ms, out,
                    "Timed out after 180s — Gradle wrapper bootstrap may be stuck (proxy, JDK toolchain auto-download).")
            } else if (proc.exitValue() == 0) {
                VerifyResult(true, 0, ms, out, "Gradle launched successfully.")
            } else {
                val hint = diagnoseGradleFailure(out)
                VerifyResult(false, proc.exitValue(), ms, out,
                    if (hint != null) "gradlew exited ${proc.exitValue()}. $hint"
                    else "gradlew exited ${proc.exitValue()}. See output below for the underlying error.")
            }
        } catch (e: Exception) {
            VerifyResult(false, -1, System.currentTimeMillis() - start, e.toString(),
                "Failed to launch gradlew: ${e.message}")
        }
    }

    /**
     * Walk a JAVA_HOME-style path back to the `java` binary. Returns
     * null if the path doesn't look like a JDK. When [jdkPath] is null
     * or blank, falls back to PATH lookup so the verify path works
     * even if the user hasn't picked anything from the dropdown.
     */
    private fun resolveJavaExe(jdkPath: String?): String? {
        if (!jdkPath.isNullOrBlank()) {
            val home = File(jdkPath)
            val exe = File(home, if (Platform.isWindows) "bin/java.exe" else "bin/java")
            return if (exe.isFile) exe.absolutePath else null
        }
        // Scan PATH dirs for a `java` binary as a last-ditch fallback.
        val pathEnv = System.getenv("PATH") ?: return null
        val name = if (Platform.isWindows) "java.exe" else "java"
        for (dir in pathEnv.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }

    /**
     * Pattern-match common gradlew failure modes and return a one-line
     * actionable hint. Each rule is tied to a real, observed failure —
     * `NoSuchFieldError: IBM_SEMERU` (foojay-resolver-convention 0.8.0
     * vs Gradle 9.x), preview-feature errors (toolchain too old for
     * the source), and stale `.reserved.lock` files in `~/.gradle/jdks`
     * (failed JDK auto-provision leaves a half-created entry that
     * blocks the next attempt).
     */
    private fun diagnoseGradleFailure(output: String): String? {
        if (output.contains("NoSuchFieldError: IBM_SEMERU") ||
            output.contains("Could not initialize class org.gradle.toolchains.foojay")) {
            return "foojay-resolver-convention is too old for this Gradle version " +
                "(`NoSuchFieldError: IBM_SEMERU`). Bump the plugin to 1.0.0+ in banking-app/settings.gradle.kts."
        }
        if (output.contains("preview feature and are disabled")) {
            return "Source uses a Java preview feature your toolchain JDK doesn't ship as standard. " +
                "Bump `JavaLanguageVersion.of(N)` in banking-app/build.gradle.kts to a release where the feature is final " +
                "(switch patterns → 21+, virtual threads → 21+, sealed classes → 17+)."
        }
        if (output.contains("Cannot find a Java installation") ||
            output.contains("Unable to download toolchain matching")) {
            return "Toolchain auto-provision failed. Check ~/.gradle/jdks for stale `.reserved.lock` files left by a half-finished download — delete them and retry. If that doesn't fix it, install a matching JDK manually and re-run."
        }
        if (output.contains("JAVA_HOME is set to an invalid directory") ||
            output.contains("ERROR: JAVA_HOME is not set")) {
            return "JAVA_HOME is missing or wrong. Pick a JDK from the dropdown above and retry — the verify path will set JAVA_HOME for the subprocess automatically."
        }
        return null
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
        if (status() == Status.RUNNING) {
            // Caught a non-agent app trying to start with-agent (or vice
            // versa). Don't silently report "already running" — that
            // misleads the user into thinking the agent is attached
            // when it isn't. Tell them to stop first.
            if (withAppMapAgent && !startedWithAgent_) {
                return "Banking app is running WITHOUT the AppMap agent. " +
                    "Stop it first (Phase 3 below), then click Start with agent again. " +
                    "Recording will fail otherwise — /_appmap/record returns 404 without the agent."
            }
            return "Banking app already running at $url" +
                (if (startedWithAgent_) " (with AppMap agent attached)" else "")
        }

        val dir = bankingAppDir
        val wrapper = if (Platform.isWindows) "gradlew.bat" else "gradlew"
        if (!dir.resolve(wrapper).exists()) return "Error: $wrapper not found in ${dir.absolutePath}"

        // Pick a usable JAVA_HOME using JdkDiscovery so we don't blow
        // up on Windows / Linux without Homebrew. Honor the toolchain
        // requirement if banking-app's build.gradle.kts pins one — that
        // way Gradle's daemon launches with a JDK that matches the
        // toolchain (skipping the foojay download path entirely).
        val pinnedMajor = toolchainMajor()
        val javaHome = JdkDiscovery.bestAvailableHome(matchMajor = pinnedMajor)
        log.info("Banking app launch: toolchainMajor={}, JAVA_HOME={}", pinnedMajor, javaHome)

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
            // Walk descendants first. On Windows, `proc` is the
            // gradlew.bat cmd.exe wrapper — calling destroy() on it
            // closes the .bat but leaves the actual JVM child (Spring
            // Boot on port 8080) orphaned. ProcessHandle.descendants()
            // gives us every transitive child so we can SIGTERM/CTRL-C
            // the JVM directly. Destroy bottom-up so a killed parent
            // doesn't reparent its grandchildren to PID 1 mid-walk.
            val descendants = runCatching { proc.descendants().toList() }.getOrDefault(emptyList())
            val killed = mutableListOf<Long>()
            for (d in descendants.reversed()) {
                if (d.isAlive) {
                    runCatching { d.destroy() }
                    killed += d.pid()
                }
            }
            proc.destroy()
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            // Anything still alive (Windows particularly stubborn
            // JVMs) gets a forcible kill.
            for (d in descendants) {
                if (d.isAlive) runCatching { d.destroyForcibly() }
            }
            if (proc.isAlive) proc.destroyForcibly()
            log.info("Banking app stopped (parent PID {}, descendants {})", proc.pid(), killed)
            return if (killed.isEmpty())
                "Banking app stopped (PID ${proc.pid()})."
            else
                "Banking app stopped (parent PID ${proc.pid()}, killed ${killed.size} child JVM(s): $killed)."
        }

        // Fallback: webui restarted while banking-app kept running, so
        // we no longer have a Process handle. Find whoever's holding
        // port 8080 and kill them.
        val orphaned = killByPort(port)
        if (orphaned.isNotEmpty()) {
            return "Banking app stopped (killed orphaned PID(s) on port $port: $orphaned)."
        }
        return "Banking app was not running."
    }

    /**
     * Find and kill the process listening on [tcpPort]. Used when the
     * webui has lost its handle to the banking-app subprocess (typical
     * after a webui restart) but the JVM is still alive and answering
     * on port 8080. Cross-platform — uses
     * <code>Get-NetTCPConnection</code> on Windows, <code>lsof</code>
     * elsewhere. Returns the list of PIDs that were killed.
     */
    private fun killByPort(tcpPort: Int): List<Long> {
        val pids = if (Platform.isWindows) windowsPidsOnPort(tcpPort) else unixPidsOnPort(tcpPort)
        if (pids.isEmpty()) return emptyList()
        for (pid in pids) {
            val cmd = if (Platform.isWindows)
                listOf("taskkill", "/PID", pid.toString(), "/T", "/F")
            else
                listOf("kill", "-9", pid.toString())
            runCatching {
                ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor()
            }
        }
        return pids
    }

    private fun windowsPidsOnPort(tcpPort: Int): List<Long> = runCatching {
        // Get-NetTCPConnection is the canonical answer on Windows 10+;
        // falls back to netstat parsing if PowerShell isn't reachable.
        val ps = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-Command",
            "Get-NetTCPConnection -LocalPort $tcpPort -State Listen -ErrorAction SilentlyContinue " +
                "| Select-Object -ExpandProperty OwningProcess"
        ).redirectErrorStream(true).start()
        val out = ps.inputStream.bufferedReader().readText()
        ps.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        out.lines().mapNotNull { it.trim().toLongOrNull() }.distinct()
    }.getOrElse { emptyList() }

    private fun unixPidsOnPort(tcpPort: Int): List<Long> = runCatching {
        val proc = ProcessBuilder("lsof", "-ti", ":$tcpPort")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        out.lines().mapNotNull { it.trim().toLongOrNull() }.distinct()
    }.getOrElse { emptyList() }

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
