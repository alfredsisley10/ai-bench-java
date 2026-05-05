package com.aibench.webui

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cross-platform JDK discovery. Scans a handful of well-known install
 * locations on macOS, Windows, and Linux, plus the obvious env-var
 * (`JAVA_HOME`) and `PATH` entry points, and runs `java -version`
 * against each candidate to validate it. Used by the /demo "Verify
 * Java" panel so the user can pick which installed JDK their
 * banking-app build should run against.
 *
 * <p>The scan is bounded — at most ~30 candidates are probed and each
 * `java -version` call gets a 5-second timeout — so a corporate
 * machine with a dozen JetBrains-managed JDKs and a couple of system
 * ones still finishes well under a second. Results are cached for the
 * lifetime of the process; call {@link #invalidate} if you need to
 * re-scan after the user installed a new JDK.
 */
object JdkDiscovery {

    private val log = LoggerFactory.getLogger(JdkDiscovery::class.java)

    data class Jdk(
        /** JAVA_HOME-style root (the dir whose `bin/` contains java). */
        val home: String,
        /** Absolute path to the `java`/`java.exe` binary. */
        val javaExe: String,
        /** Parsed major version (8, 11, 17, 21, 25, …) or -1 if unparseable. */
        val major: Int,
        /** First non-blank line of `java -version` output. */
        val versionLine: String,
        /** Where this JDK was found (env JAVA_HOME, PATH, /Library/…, etc.). */
        val source: String
    ) {
        /** Short label for the dropdown — version + vendor + path. */
        val label: String
            get() {
                val v = if (major > 0) "JDK $major" else "JDK ?"
                return "$v — $home"
            }
    }

    @Volatile
    private var cached: List<Jdk>? = null

    fun invalidate() { cached = null }

    /**
     * Pick a usable JAVA_HOME for spawning JVM subprocesses (gradlew,
     * banking-app bootRun, agent-discovery probes). Replaces the old
     * macOS-specific hardcode that broke immediately on Windows /
     * Linux without Homebrew. Resolution order:
     *   1. <code>JAVA_HOME</code> env var if it points at a real bin/.
     *   2. The highest-version JDK detected on this host (or
     *      <code>matchMajor</code> if it's installed).
     *   3. <code>java.home</code> of the current process — guaranteed
     *      to exist since we're running in it.
     */
    fun bestAvailableHome(matchMajor: Int? = null): String {
        val jdks = discover()
        val saved = readSavedDefaultHome()

        // OPERATOR INTENT WINS. When the operator has pinned a default
        // on /demo's "Save as default" button, honor it -- even when
        // it doesn't match the requested matchMajor. The previous logic
        // silently demoted the saved default whenever matchMajor was
        // set, which caused operators to see Java 8 (or whatever
        // happened to be first in PATH) in subprocess logs even though
        // they had explicitly picked Java 25 on the demo page. The
        // resulting toolchain mismatch (saved default newer or older
        // than the build's toolchain) is now surfaced as a WARN log
        // rather than silently overridden -- the gradle build will
        // either succeed (matching) or fail loudly with "Unsupported
        // class file major version" so the operator can adjust.
        if (saved != null) {
            if (matchMajor != null) {
                val savedMajor = jdks.firstOrNull { it.home == saved }?.major
                if (savedMajor != null && savedMajor != matchMajor) {
                    log.warn("Saved default JDK ({}, major={}) does not match the build's " +
                        "toolchain pin (major={}). Honoring operator's choice; if the gradle " +
                        "subprocess fails with 'Unsupported class file major version', either " +
                        "pick a matching JDK on /demo or align the toolchain.",
                        saved, savedMajor, matchMajor)
                }
            }
            return saved
        }

        // No saved default. matchMajor wins next when set.
        if (matchMajor != null && matchMajor > 0) {
            val matching = jdks.filter { it.major == matchMajor }
            if (matching.isNotEmpty()) {
                val env = System.getenv("JAVA_HOME")
                matching.firstOrNull { it.home == env }?.let { return it.home }
                return matching.first().home
            }
            // No matching JDK; fall through to the env/first heuristic
            // below. The build will surface the mismatch loudly.
        }

        // No saved default, no matchMajor (or no matching install):
        // JAVA_HOME env > first discovered > java.home of bench-webui.
        val env = System.getenv("JAVA_HOME")
        if (!env.isNullOrBlank() && File(env, "bin").isDirectory) return env
        jdks.firstOrNull()?.let { return it.home }
        return System.getProperty("java.home") ?: ""
    }

    /**
     * File where user-added JDK paths are persisted, one absolute path
     * per line. Read on every scan so users can drop a path in via the
     * /demo "Add JDK path" form even if their install lives somewhere
     * we don't probe (a portable JDK on a USB stick, an enterprise tool
     * suite under a non-standard root, etc.).
     */
    private val extraJdksFile: File = File(
        System.getProperty("user.home"), ".ai-bench/extra-jdk-paths.txt")

    /**
     * Persisted "default JDK" — the JAVA_HOME the operator pinned via
     * the /demo "Save as default" button. Survives webui restarts and
     * takes precedence over the toolchain-match heuristic when the
     * verify panel's dropdown picks an initial selection. Validated
     * on every read; an invalid (deleted/moved) JDK is removed
     * automatically so the next render falls back to auto-pick.
     */
    private val defaultJdkFile: File = File(
        System.getProperty("user.home"), ".ai-bench/default-jdk-home.txt")

    /**
     * Read the persisted default JDK home, validating it still has a
     * runnable {@code bin/java}. Returns null if no default is saved
     * or the saved one no longer resolves; in the latter case, the
     * stale file is deleted so subsequent calls don't keep paying
     * the validation cost.
     */
    fun readSavedDefaultHome(): String? {
        if (!defaultJdkFile.isFile) return null
        val saved = runCatching { defaultJdkFile.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: run { defaultJdkFile.delete(); return null }
        val javaExe = File(saved,
            if (Platform.isWindows) "bin/java.exe" else "bin/java")
        if (!javaExe.isFile) {
            defaultJdkFile.delete()
            return null
        }
        // Cheap validity check — the dir still exists and has bin/java.
        // We don't re-run `java -version` here because that's expensive
        // for every page render; the discovery cache handles full probe
        // freshness when the dropdown gets populated.
        return saved
    }

    /**
     * Persist [home] as the user's default JDK choice. Validates that
     * it's a real JDK before writing — an invalid path returns false
     * without disturbing the previously-saved default.
     */
    fun saveDefaultHome(home: String): Boolean {
        val trimmed = home.trim().trim('"', '\'').trim()
        if (trimmed.isEmpty()) return false
        val javaExe = File(trimmed,
            if (Platform.isWindows) "bin/java.exe" else "bin/java")
        if (!javaExe.isFile) return false
        defaultJdkFile.parentFile?.mkdirs()
        defaultJdkFile.writeText(trimmed)
        return true
    }

    fun clearDefaultHome() { defaultJdkFile.delete() }

    /**
     * Append a user-supplied JDK path to the persistent list. Validates
     * up front: must be a directory containing a real `java`/`java.exe`
     * binary that successfully reports a version. Returns the resulting
     * Jdk on success, null on validation failure.
     */
    fun addCustomPath(rawPath: String): Jdk? {
        val trimmed = rawPath.trim().trim('"', '\'').trim()
        if (trimmed.isEmpty()) return null
        val home = File(trimmed)
        // Accept either the JAVA_HOME root or the bin/java exe itself.
        val resolvedHome = when {
            home.resolve(if (Platform.isWindows) "bin/java.exe" else "bin/java").isFile -> home
            home.name.startsWith("java") && home.parentFile?.parentFile?.let {
                File(it, if (Platform.isWindows) "bin/java.exe" else "bin/java").isFile
            } == true -> home.parentFile.parentFile
            else -> return null
        }
        val javaExe = File(resolvedHome,
            if (Platform.isWindows) "bin/java.exe" else "bin/java")
        if (!javaExe.isFile) return null
        val probe = probeJava(javaExe.absolutePath) ?: return null

        // Persist (de-dup against canonical path).
        val canonical = runCatching { resolvedHome.canonicalPath }
            .getOrDefault(resolvedHome.absolutePath)
        val existing = readExtraPaths().toMutableSet()
        existing += canonical
        extraJdksFile.parentFile?.mkdirs()
        extraJdksFile.writeText(existing.joinToString("\n") + "\n")

        // Bust the cache so the next discover() call picks it up.
        invalidate()
        return Jdk(canonical, javaExe.absolutePath, probe.major, probe.versionLine, "user-added")
    }

    data class ScanOutcome(val jdks: List<Jdk>, val visitedDirs: Int, val truncated: Boolean)

    /**
     * Walk [rootPath] recursively (bounded by depth + visit count) and
     * return every directory that looks like a JDK home — i.e. has a
     * runnable `bin/java(.exe)`. Stops descending into a hit so we
     * don't wander through a JDK's `lib/`, `legal/`, etc. counting
     * non-JDK subdirs. Skips the usual noise dirs and follows
     * symlinks defensively.
     *
     * <p>Each successful hit is also persisted to
     * <code>~/.ai-bench/extra-jdk-paths.txt</code> so the JDK
     * dropdown picks it up on the next /demo render. The cache is
     * invalidated so the next discover() call re-scans.
     */
    fun scanFolderForJdks(rootPath: String): ScanOutcome {
        val root = File(rootPath.trim().trim('"', '\'').trim())
        if (!root.isDirectory) return ScanOutcome(emptyList(), 0, false)

        // Targeted block-list. Skip filesystem noise (git, build outputs)
        // and Windows system dirs. Do NOT skip "Program Files" — that's
        // exactly where Adoptium / Microsoft / Corretto live, which is
        // a likely picked folder.
        val skipNames = setOf(
            ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".m2", ".cache",
            "node_modules", "build", "out", "target", "dist",
            "\$Recycle.Bin", "System Volume Information", "Windows", "Recovery"
        )

        val maxDepth = 8
        val maxVisits = 5000
        var visits = 0

        val javaBinName = if (Platform.isWindows) "java.exe" else "java"
        val found = mutableListOf<Jdk>()
        val queue: ArrayDeque<Pair<File, Int>> = ArrayDeque()
        queue.addLast(root to 0)

        while (queue.isNotEmpty() && visits < maxVisits) {
            val (dir, depth) = queue.removeFirst()
            visits++

            // Is THIS dir a JDK home?
            val javaExe = File(dir, "bin/$javaBinName")
            if (javaExe.isFile) {
                val probe = probeJava(javaExe.absolutePath)
                if (probe != null) {
                    val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                    found += Jdk(canonical, javaExe.absolutePath, probe.major, probe.versionLine, "scanned")
                    // Don't descend into a JDK's internals — pointless.
                    continue
                }
            }
            if (depth >= maxDepth) continue

            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (!child.isDirectory) continue
                val name = child.name
                if (name.startsWith(".") && name !in setOf(".jdks", ".sdkman")) continue
                if (name in skipNames) continue
                if (runCatching { java.nio.file.Files.isSymbolicLink(child.toPath()) }
                        .getOrDefault(false)) continue
                queue.addLast(child to depth + 1)
            }
        }

        // Persist hits.
        if (found.isNotEmpty()) {
            val existing = readExtraPaths().toMutableSet()
            found.forEach { existing += it.home }
            extraJdksFile.parentFile?.mkdirs()
            extraJdksFile.writeText(existing.joinToString("\n") + "\n")
            invalidate()
        }
        return ScanOutcome(found, visits, visits >= maxVisits)
    }

    /** Drop a user-added JDK path so it stops appearing in the dropdown. */
    fun removeCustomPath(home: String): Boolean {
        val canonical = runCatching { File(home).canonicalPath }.getOrDefault(home)
        val existing = readExtraPaths().toMutableSet()
        if (!existing.remove(canonical)) return false
        if (existing.isEmpty()) extraJdksFile.delete()
        else extraJdksFile.writeText(existing.joinToString("\n") + "\n")
        invalidate()
        return true
    }

    private fun readExtraPaths(): List<String> {
        if (!extraJdksFile.isFile) return emptyList()
        return runCatching {
            extraJdksFile.readLines().map { it.trim() }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun discover(): List<Jdk> {
        cached?.let { return it }
        val candidates = LinkedHashMap<String, String>() // canonical home -> source label

        fun add(homePath: String?, source: String) {
            if (homePath.isNullOrBlank()) return
            val home = File(homePath)
            if (!home.isDirectory) return
            val canonical = runCatching { home.canonicalPath }.getOrDefault(home.absolutePath)
            if (canonical !in candidates) candidates[canonical] = source
        }

        // 0. User-added paths (highest precedence in the candidate list
        //    so they show up at the top when versions tie).
        readExtraPaths().forEach { add(it, "user-added") }

        // 1. JAVA_HOME — the canonical env var.
        add(System.getenv("JAVA_HOME"), "JAVA_HOME")

        // 2. The JDK we're currently running on. Useful even if JAVA_HOME
        //    is unset because Spring Boot itself can run on the system
        //    JRE; user's gradlew may also need a real JDK though.
        add(System.getProperty("java.home"), "java.home (current process)")

        // 3. `java` on PATH — resolve via `where java` / `which -a java`
        //    so multiple PATH hits are picked up. Then walk back from the
        //    binary path to the JAVA_HOME root.
        whereJava().forEach { exe ->
            val root = exe.parentFile?.parentFile
            if (root != null) add(root.absolutePath, "PATH (${exe.absolutePath})")
        }

        // 4. Platform-specific install root globs.
        if (Platform.isMac) {
            // /usr/libexec/java_home -V is the macOS-canonical answer —
            // every JDK registered with the system surfaces here, with
            // its actual JAVA_HOME path on the right side.
            macJavaHomeRegistry().forEach { add(it, "/usr/libexec/java_home -V") }
            globHomes("/Library/Java/JavaVirtualMachines/*/Contents/Home", "/Library/Java/JavaVirtualMachines")
                .forEach { add(it, "/Library/Java/JavaVirtualMachines") }
            val userHome = System.getProperty("user.home") ?: ""
            globHomes("$userHome/Library/Java/JavaVirtualMachines/*/Contents/Home", "~/Library/Java/JavaVirtualMachines")
                .forEach { add(it, "~/Library/Java/JavaVirtualMachines") }
            // Homebrew openjdk casks
            globHomes("/opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home", "homebrew openjdk")
                .forEach { add(it, "homebrew (/opt/homebrew)") }
            globHomes("/usr/local/opt/openjdk*/libexec/openjdk.jdk/Contents/Home", "homebrew openjdk")
                .forEach { add(it, "homebrew (/usr/local)") }
            // SDKMAN
            globHomes("$userHome/.sdkman/candidates/java/*", "sdkman")
                .forEach { add(it, "SDKMAN") }
            // Gradle toolchain auto-provisioned JDKs on macOS. Layout
            // is ~/.gradle/jdks/<distro>/<inner>/Contents/Home/bin/java
            // (the .tar.gz from foojay extracts into a vendor-named
            // subdir AND a macOS bundle), so the scan walks two
            // levels deep before checking for Contents/Home/bin/java.
            findGradleJdks(File(userHome, ".gradle/jdks")).forEach {
                add(it, "~/.gradle/jdks (foojay)")
            }
        } else if (Platform.isWindows) {
            // Common Windows installer roots. Each child whose `bin\java.exe`
            // exists is treated as a JAVA_HOME.
            val winRoots = listOf(
                "C:\\Program Files\\Java",
                "C:\\Program Files\\Eclipse Adoptium",
                "C:\\Program Files\\Eclipse Foundation",
                "C:\\Program Files\\Microsoft",
                "C:\\Program Files\\Amazon Corretto",
                "C:\\Program Files\\Zulu",
                "C:\\Program Files\\BellSoft",
                "C:\\Program Files\\OpenJDK",
                "C:\\Program Files\\GraalVM",
                "C:\\Program Files\\SapMachine",
                "C:\\Program Files (x86)\\Java"
            )
            for (root in winRoots) {
                val dir = File(root)
                if (!dir.isDirectory) continue
                dir.listFiles()?.forEach { child ->
                    if (child.isDirectory && File(child, "bin/java.exe").isFile) {
                        add(child.absolutePath, root)
                    }
                }
            }
            // IntelliJ-managed JDKs
            val userHome = System.getProperty("user.home") ?: ""
            val ijJdks = File(userHome, ".jdks")
            if (ijJdks.isDirectory) {
                ijJdks.listFiles()?.forEach { child ->
                    if (child.isDirectory && File(child, "bin/java.exe").isFile) {
                        add(child.absolutePath, "~/.jdks (IntelliJ)")
                    }
                }
            }
            // Gradle toolchain auto-provisioned JDKs
            val gradleJdks = File(userHome, ".gradle/jdks")
            if (gradleJdks.isDirectory) {
                gradleJdks.listFiles()?.forEach { child ->
                    if (child.isDirectory && File(child, "bin/java.exe").isFile) {
                        add(child.absolutePath, "~/.gradle/jdks")
                    }
                }
            }
        } else {
            // Linux. Most distros use /usr/lib/jvm/<dist>; SDKMAN is
            // also popular.
            val linRoots = listOf("/usr/lib/jvm", "/opt", "/usr/java")
            for (root in linRoots) {
                val dir = File(root)
                if (!dir.isDirectory) continue
                dir.listFiles()?.forEach { child ->
                    if (child.isDirectory && File(child, "bin/java").isFile) {
                        add(child.absolutePath, root)
                    }
                }
            }
            val userHome = System.getProperty("user.home") ?: ""
            val sdkman = File(userHome, ".sdkman/candidates/java")
            if (sdkman.isDirectory) {
                sdkman.listFiles()?.forEach { child ->
                    if (child.isDirectory && File(child, "bin/java").isFile) {
                        add(child.absolutePath, "SDKMAN")
                    }
                }
            }
            // Gradle toolchain auto-provisioned JDKs on Linux:
            // ~/.gradle/jdks/<distro>/<inner>/bin/java
            findGradleJdks(File(userHome, ".gradle/jdks")).forEach {
                add(it, "~/.gradle/jdks (foojay)")
            }
        }

        // Probe each candidate. Filter out anything that doesn't actually
        // run — broken installers, half-deleted dirs, JDK-less JREs.
        val results = mutableListOf<Jdk>()
        for ((home, source) in candidates) {
            val javaExe = File(home, if (Platform.isWindows) "bin/java.exe" else "bin/java")
            if (!javaExe.isFile) continue
            val probe = probeJava(javaExe.absolutePath) ?: continue
            results += Jdk(home, javaExe.absolutePath, probe.major, probe.versionLine, source)
        }
        // Stable order: highest major first, then by path.
        val sorted = results.sortedWith(compareByDescending<Jdk> { it.major }.thenBy { it.home })
        cached = sorted
        log.info("JDK discovery found {} usable JDKs", sorted.size)
        return sorted
    }

    private data class ProbeResult(val major: Int, val versionLine: String)

    /**
     * Run `<javaExe> -version` with stderr merged into stdout. Returns
     * null if the binary failed, timed out, or printed nothing
     * recognizable. `java -version` writes to STDERR by convention so
     * `redirectErrorStream` is required.
     */
    private fun probeJava(javaExe: String): ProbeResult? {
        return try {
            val proc = ProcessBuilder(javaExe, "-version")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return null
            }
            if (proc.exitValue() != 0) return null
            val firstLine = output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() } ?: return null
            ProbeResult(parseMajor(firstLine), firstLine)
        } catch (e: Exception) {
            log.debug("probeJava failed for {}: {}", javaExe, e.message)
            null
        }
    }

    /**
     * Pull the major version out of a `java -version` first line such as:
     *   openjdk version "21.0.4" 2024-07-16 LTS
     *   java version "1.8.0_311"
     *   openjdk version "11.0.20" 2023-07-18
     * Falls back to -1 if the format is unrecognized.
     */
    private fun parseMajor(versionLine: String): Int {
        // Quoted version literal.
        val m = Regex("""version\s+"([^"]+)"""").find(versionLine) ?: return -1
        val raw = m.groupValues[1]
        // Java 8 reports as "1.8.0_..."; Java 9+ drops the "1." prefix.
        val parts = raw.split('.')
        val first = parts.getOrNull(0)?.toIntOrNull() ?: return -1
        return if (first == 1) parts.getOrNull(1)?.toIntOrNull() ?: -1 else first
    }

    /** All `java`/`java.exe` hits on PATH, in PATH order. */
    private fun whereJava(): List<File> {
        val cmd = if (Platform.isWindows) listOf("where", "java")
                  else listOf("/bin/sh", "-c", "command -v java; type -a -p java 2>/dev/null || true")
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return emptyList()
            }
            out.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val f = File(line)
                    if (f.isFile) f else null
                }
                .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * macOS: parse `/usr/libexec/java_home -V` to enumerate every JDK
     * the system knows about. Output lines look like:
     *   21.0.4 (arm64) "Eclipse Temurin" - "OpenJDK 21.0.4" /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
     */
    private fun macJavaHomeRegistry(): List<String> {
        val tool = File("/usr/libexec/java_home")
        if (!tool.isFile) return emptyList()
        return try {
            val proc = ProcessBuilder(tool.absolutePath, "-V")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) { proc.destroyForcibly(); return emptyList() }
            out.lineSequence()
                .map { it.trim() }
                .mapNotNull { line ->
                    // Path is the trailing `/Contents/Home` token.
                    val idx = line.indexOf("/Library/Java/JavaVirtualMachines/")
                    if (idx >= 0) line.substring(idx) else null
                }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Lightweight glob: matches a single literal `*` segment in the path.
     * Suffices for our needs (no recursive globbing, no character classes).
     */
    /**
     * Walk ~/.gradle/jdks (or wherever foojay-resolver extracts toolchain
     * downloads) and return JAVA_HOME-style root paths. Each foojay
     * extract has its own per-platform layout:
     *   Windows: <gradle-jdks>/<distro>/bin/java.exe
     *   Linux:   <gradle-jdks>/<distro>/<inner>/bin/java
     *            (extra <inner> level because the .tar.gz includes a
     *            vendor-named top dir; foojay doesn't strip it)
     *   macOS:   <gradle-jdks>/<distro>/<inner>/Contents/Home/bin/java
     *            (mac bundle layout adds Contents/Home)
     * Walk depth ≤ 3 from <gradle-jdks>, stop as soon as bin/java(.exe)
     * is found, return the JAVA_HOME-style ancestor (the dir whose
     * `bin/` contains the binary).
     */
    private fun findGradleJdks(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val javaExeName = if (Platform.isWindows) "java.exe" else "java"
        val results = mutableListOf<String>()
        // Visit each top-level entry and walk up to 3 levels deep
        // looking for a JAVA_HOME-style root.
        root.listFiles()?.forEach { topLevel ->
            if (!topLevel.isDirectory) return@forEach
            // Try direct bin/java first (Windows-style layout)
            if (File(topLevel, "bin/$javaExeName").isFile) {
                results += topLevel.absolutePath
                return@forEach
            }
            // Walk one level deeper for the per-distro inner dir
            topLevel.listFiles()?.forEach { inner ->
                if (!inner.isDirectory) return@forEach
                // Linux: <inner>/bin/java
                if (File(inner, "bin/$javaExeName").isFile) {
                    results += inner.absolutePath
                    return@forEach
                }
                // macOS: <inner>/Contents/Home/bin/java
                val macHome = File(inner, "Contents/Home")
                if (File(macHome, "bin/$javaExeName").isFile) {
                    results += macHome.absolutePath
                    return@forEach
                }
            }
        }
        return results
    }

    private fun globHomes(pattern: String, label: String): List<String> {
        val parts = pattern.split('/')
        val starIdx = parts.indexOfFirst { it.contains('*') }
        if (starIdx < 0) return if (File(pattern).isDirectory) listOf(pattern) else emptyList()
        val prefix = parts.subList(0, starIdx).joinToString("/").ifEmpty { "/" }
        val starSeg = parts[starIdx]
        val suffix = parts.subList(starIdx + 1, parts.size).joinToString("/")
        val parent = File(prefix)
        if (!parent.isDirectory) return emptyList()
        val regex = Regex("^" + Regex.escape(starSeg).replace("\\*", ".*") + "$")
        val results = mutableListOf<String>()
        parent.listFiles()?.forEach { entry ->
            if (regex.matches(entry.name)) {
                val full = if (suffix.isEmpty()) entry.absolutePath
                           else File(entry, suffix).absolutePath
                if (File(full).isDirectory) results += full
            }
        }
        return results
    }
}
