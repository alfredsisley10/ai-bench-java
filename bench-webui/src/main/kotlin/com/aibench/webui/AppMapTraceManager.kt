package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Generates AppMap traces once and reuses them across benchmark runs.
 *
 * The bench-webui's previous trace-collection step recorded a random
 * counter per benchmark — every run pretended to record traces from
 * scratch even when an identical (banking-app SHA, mode) combination
 * had just finished. Two problems with that:
 *   1. Real recording is expensive (running the test suite under the
 *      AppMap agent), so duplicating it per benchmark is wasteful.
 *   2. AppMap Navie *consumes* traces — it doesn't create them. If
 *      Navie is the context provider and no traces exist on disk, the
 *      bench has nothing for Navie to vector-search against.
 *
 * This manager is the single choke point for "ensure the right set of
 * traces exists for this banking-app revision and this AppMap mode."
 * Cache layout, keyed off the banking-app HEAD SHA so a fresh checkout
 * triggers a regen:
 *
 * `~/.ai-bench/appmap-traces/<sha>/<mode>/`
 *
 *   - `.generated` — marker file (timestamp + tool versions)
 *   - `junit/<TestName>.appmap.json` — one trace per recorded test
 *
 * Concurrency: a per-(sha,mode) lock file held via FileChannel.lock()
 * so two benchmark runs that both kick off "ON_RECOMMENDED" at the
 * same time don't both try to record. The second caller blocks until
 * the first finishes, then sees the marker and reuses.
 *
 * Layer-B (this commit) writes synthetic stub files so the architecture
 * — cache key, locking, share-across-runs — is correct and verifiable.
 * Layer-C will swap [generateSynthetic] for an actual gradle invocation
 * with the AppMap agent, producing real traces.
 */
@Component
class AppMapTraceManager(
    private val bankingApp: BankingAppManager,
    private val connectionSettings: ConnectionSettings
) {

    private val log = LoggerFactory.getLogger(AppMapTraceManager::class.java)

    /**
     * In-process per-(sha,mode) mutex map. FileChannel.lock() is only
     * exclusive across PROCESSES — within the same JVM, a second thread
     * acquiring a lock on the same file path throws
     * OverlappingFileLockException immediately rather than blocking. So
     * when 12 benchmarks launched simultaneously and all wanted the
     * same (sha, ON_RECOMMENDED) lock, exactly 1 succeeded and 6
     * threads ERRORED with that exception. The fix: serialize first
     * on a JVM-local ReentrantLock keyed by (sha,mode), then take the
     * file-channel lock for cross-process coordination. Inside the
     * JVM lock the file-channel call sees only one contender at a time
     * and never trips the overlap check.
     */
    private val inProcessLocks = ConcurrentHashMap<String, ReentrantLock>()
    private fun jvmLockFor(key: String): ReentrantLock =
        inProcessLocks.computeIfAbsent(key) { ReentrantLock() }

    /** What `ensureTracesExist` returned: where the traces live and
     *  whether this call did the work or hit the cache. */
    data class TraceInventory(
        val mode: String,
        val sha: String,
        val cacheDir: File?,
        val tracePaths: List<File>,
        /** true when this invocation generated the traces; false when
         *  the cache was already populated by a prior call. */
        val generated: Boolean,
        /** Synthetic flag: true while Layer B is in place. Lets the UI
         *  surface "synthetic stub traces" instead of pretending they
         *  came from a real recording. */
        val synthetic: Boolean = true
    ) {
        fun count() = tracePaths.size
    }

    /**
     * Idempotent. For mode=OFF returns an empty inventory immediately.
     * For ON_*: looks up the cache dir, generates if missing, and
     * returns paths to the trace files — guaranteed to exist on disk
     * by the time this call returns.
     */
    fun ensureTracesExist(
        mode: String,
        bug: BugCatalog.BugMetadata?,
        log: (String) -> Unit = { this.log.info(it) }
    ): TraceInventory {
        if (mode == "OFF") {
            return TraceInventory(mode, "", null, emptyList(), generated = false)
        }
        // Prefer real traces from banking-app/<module>/tmp/appmap/junit/
        // if any exist for the bug's module — they're produced by the
        // operator's `Generate traces` admin action and reflect the
        // real test execution.
        val real = realTracesForBug(mode, bug)
        if (real.isNotEmpty()) {
            log("AppMap mode=$mode: using ${real.size} real recording(s) " +
                "from banking-app/${bug?.module ?: "?"}/tmp/appmap/junit/.")
            return TraceInventory(mode, headSha() ?: "unknown-sha", null,
                real, generated = false, synthetic = false)
        }
        // Real traces missing for the bug's module. Before falling
        // through to synthetic stubs (which previously gave the
        // operator a silent "AppMap=ON but no real traces shipped"
        // experience -- particularly painful on Windows where the
        // gradle wrapper bug above stopped generation entirely),
        // auto-trigger a `gradle test` with the AppMap agent for
        // the bug's module. If the run succeeds we get real
        // traces; if it fails (Windows JDK mismatch, gradle plugin
        // crash, etc.) we fall through to synthetic stubs WITH a
        // loud warning so the operator knows the AppMap=ON setting
        // didn't deliver real recordings.
        val module = bug?.module?.takeIf { it.isNotBlank() }
        if (module != null) {
            log("AppMap mode=$mode: no real traces in banking-app/$module/tmp/appmap/junit/ " +
                "— auto-running `gradle :$module:test` with AppMap agent (this may take 30s-5min) …")
            val gen = runCatching { generateRealTracesForModule(module) }
                .onFailure { log("AppMap auto-generation threw: ${it.message}; " +
                                  "falling through to synthetic stubs.") }
                .getOrNull()
            if (gen != null && gen.ok && gen.tracesAfter > 0) {
                val regenerated = realTracesForBug(mode, bug)
                log("AppMap mode=$mode: auto-generation produced ${regenerated.size} " +
                    "real trace(s) in ${gen.durationMs / 1000}s.")
                return TraceInventory(mode, headSha() ?: "unknown-sha", null,
                    regenerated, generated = true, synthetic = false)
            }
            // Generation failed or produced 0 traces. Surface the
            // gradle exit code + tail so the operator can diagnose
            // (often: JDK class-file-version mismatch on a Windows
            // host where banking-app expects JDK 25 but the host
            // has 21; or AppMap plugin incompatibility with current
            // Gradle version).
            if (gen != null) {
                log("AppMap auto-generation FAILED (exit=${gen.exitCode}, " +
                    "tracesAfter=${gen.tracesAfter}). Last gradle output: " +
                    gen.tail.takeLast(400) +
                    " — falling through to synthetic stubs; AppMap context " +
                    "in this run will NOT include real recordings.")
            }
        }
        val sha = headSha() ?: "unknown-sha"
        val rootDir = File(System.getProperty("user.home"), ".ai-bench/appmap-traces")
        val cacheDir = File(rootDir, "$sha/$mode")
        val markerFile = File(cacheDir, ".generated")
        val lockFile = File(rootDir, ".$sha-$mode.lock")
        rootDir.mkdirs()

        // First serialize all in-JVM contenders on a ReentrantLock —
        // see jvmLockFor doc. Only one in-JVM thread enters the
        // file-channel section at a time, so OverlappingFileLockException
        // can't fire. The file lock then handles cross-JVM coordination
        // (a second bench-webui process on the same machine, etc).
        val jvmLock = jvmLockFor("$sha/$mode")
        jvmLock.lock()
        try {
            // Re-check the marker under the JVM lock — by the time we
            // got here, a sibling thread that beat us into the section
            // may have already populated the cache. Common case in a
            // multi-launch matrix: 11 of 12 threads hit this fast path.
            if (markerFile.isFile) {
                val files = listTraceFiles(cacheDir)
                log("AppMap mode=$mode: cache HIT @ $sha (${files.size} trace(s) — reused).")
                return TraceInventory(mode, sha, cacheDir, files, generated = false)
            }
            RandomAccessFile(lockFile, "rw").use { raf ->
                val ch = raf.channel
                val held = ch.lock()
                try {
                    if (markerFile.isFile) {
                        val files = listTraceFiles(cacheDir)
                        log("AppMap mode=$mode: cache HIT @ $sha (${files.size} trace(s) — reused).")
                        return TraceInventory(mode, sha, cacheDir, files, generated = false)
                    }
                    log("AppMap mode=$mode: cache MISS @ $sha — recording (synthetic stub) …")
                    cacheDir.mkdirs()
                    val generated = generateSynthetic(mode, bug, cacheDir, sha, log)
                    markerFile.writeText(buildString {
                        appendLine("generatedAt=${java.time.Instant.now()}")
                        appendLine("mode=$mode")
                        appendLine("sha=$sha")
                        appendLine("count=${generated.size}")
                        appendLine("synthetic=true")
                    })
                    log("AppMap mode=$mode: recorded ${generated.size} synthetic trace(s) at $cacheDir.")
                    return TraceInventory(mode, sha, cacheDir, generated, generated = true)
                } finally {
                    held.release()
                }
            }
        } finally {
            jvmLock.unlock()
        }
    }

    private fun listTraceFiles(cacheDir: File): List<File> =
        cacheDir.walkTopDown().filter { it.isFile && it.name.endsWith(".appmap.json") }.toList()

    /** Layer B placeholder: write a small set of valid-looking AppMap
     *  JSON stubs. Layer C replaces this with a real gradle invocation. */
    private fun generateSynthetic(
        mode: String,
        bug: BugCatalog.BugMetadata?,
        cacheDir: File,
        sha: String,
        log: (String) -> Unit
    ): List<File> {
        val junitDir = File(cacheDir, "junit").also { it.mkdirs() }
        // Recommended: bug's hidden test + 2 neighbors. ON_ALL: a wider
        // representative sweep across the touched modules' suites.
        val testNames = when (mode) {
            "ON_RECOMMENDED" -> {
                val hidden = bug?.let { (it.hiddenTestClass ?: "") + "_" + (it.hiddenTestMethod ?: "") }
                    ?: "UnknownTest_unknownMethod"
                listOf(
                    hidden,
                    "${bug?.module ?: "module"}_NeighborCaseA",
                    "${bug?.module ?: "module"}_NeighborCaseB"
                )
            }
            "ON_ALL" -> (1..12).map { "BankingApp_SuiteCase_${it.toString().padStart(2, '0')}" }
            else -> emptyList()
        }
        return testNames.map { name ->
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
            val file = File(junitDir, "$safe.appmap.json")
            // AppMap-format JSON. Earlier the stub had empty events +
            // empty classMap, which left the embedded /demo/appmap/view
            // page rendering "0 events" and the Vue-based AppMap
            // component complaining "no $store found / no child
            // component found" — the user's "viewer fails to load"
            // symptom. Layer-B stubs now ship a minimal but valid
            // call/return pair plus a classMap entry mirroring it so
            // the viewer renders ONE non-trivial row. The defined_class
            // / method_id come from the bug's hiddenTest + filesTouched
            // when known so the synthetic call path at least references
            // real code; otherwise we fall back to placeholder names
            // that still parse.
            val hiddenClass = bug?.hiddenTestClass ?: "com.example.placeholder.Test"
            val hiddenMethod = bug?.hiddenTestMethod ?: "synthetic_stub_method"
            val targetClass = bug?.filesTouched?.firstOrNull()
                ?.removeSuffix(".java")?.substringAfterLast('/') ?: "PlaceholderImpl"
            val targetPath = bug?.filesTouched?.firstOrNull() ?: "src/main/java/Placeholder.java"
            val pkg = hiddenClass.substringBeforeLast('.', "com.example")
            val testCls = hiddenClass.substringAfterLast('.')
            file.writeText(
                """
                {
                  "version": "1.10",
                  "metadata": {
                    "name": "$name",
                    "app": "banking-app",
                    "sha": "$sha",
                    "mode": "$mode",
                    "recorder": {"name": "ai-bench-trace-stub", "type": "synthetic"},
                    "language": {"name": "java"}
                  },
                  "classMap": [
                    {
                      "name": "$pkg",
                      "type": "package",
                      "children": [
                        {
                          "name": "$testCls",
                          "type": "class",
                          "children": [
                            {
                              "name": "$hiddenMethod",
                              "type": "function",
                              "static": false,
                              "location": "$targetPath:1"
                            }
                          ]
                        },
                        {
                          "name": "$targetClass",
                          "type": "class",
                          "children": [
                            {
                              "name": "validate",
                              "type": "function",
                              "static": false,
                              "location": "$targetPath:1"
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "events": [
                    {
                      "id": 1,
                      "event": "call",
                      "thread_id": 1,
                      "defined_class": "$hiddenClass",
                      "method_id": "$hiddenMethod",
                      "static": false,
                      "path": "$targetPath",
                      "lineno": 1
                    },
                    {
                      "id": 2,
                      "event": "call",
                      "thread_id": 1,
                      "parent_id": 1,
                      "defined_class": "$pkg.$targetClass",
                      "method_id": "validate",
                      "static": false,
                      "path": "$targetPath",
                      "lineno": 1
                    },
                    {
                      "id": 3,
                      "event": "return",
                      "thread_id": 1,
                      "parent_id": 2,
                      "elapsed": 0.001
                    },
                    {
                      "id": 4,
                      "event": "return",
                      "thread_id": 1,
                      "parent_id": 1,
                      "elapsed": 0.002
                    }
                  ]
                }
                """.trimIndent()
            )
            file
        }
    }

    // ---------------------- Real-trace surfacing -----------------------
    //
    // The synthetic-stub path above is the floor — it guarantees the
    // bench has *something* to ship even on a fresh checkout. Real
    // traces, written by `./gradlew :MODULE:test -Pappmap_enabled=true`,
    // are vastly preferable: they're produced by actually running the
    // module's JUnit tests under the AppMap Java agent, so the resulting
    // .appmap.json files reflect the live call graph + SQL + HTTP path
    // the bug touches. The admin page at /admin/appmap-traces lets the
    // operator generate them per-module.

    /** Map module name -> list of `*.appmap.json` files under
     *  banking-app/<module>/tmp/appmap/junit/. Modules without any
     *  recordings get an empty list rather than being omitted, so
     *  the admin page can render every meaningful module + a "0 / N"
     *  coverage cell. */
    fun realTraceCoverage(modules: Collection<String>): Map<String, List<File>> {
        val repo = runCatching { bankingApp.bankingAppDir }.getOrNull()
            ?: return modules.associateWith { emptyList<File>() }
        return modules.associateWith { module ->
            // Walk every subdir under <module>/tmp/appmap/ -- AppMap writes
            // gradle-driven recordings to junit/ but operators may also
            // record manually via the "Record from tests" button on
            // /demo/appmap (lands under interactive/) or use other tools
            // that drop to test/. Picking up every subdir means a manual
            // recording the operator made for a tricky bug becomes
            // available to BM25-trace-selection without any extra
            // configuration. Skips dotfiles + the harness's own work
            // dirs (lock files, the .generated marker, etc.).
            val root = File(repo, "$module/tmp/appmap")
            if (!root.isDirectory) emptyList()
            else root.walkTopDown()
                .onEnter { d -> !d.name.startsWith(".") }
                .filter { it.isFile && it.name.endsWith(".appmap.json") }
                .toList()
        }
    }

    /** ON / ON_ALL -> every trace under the bug's module (selection of
     *  which subset to ship is now done at prompt-build time by
     *  ContextProvider.selectTracesForBug, dispatched on context
     *  provider). ON_RECOMMENDED (legacy) -> the bug's hidden-test
     *  trace + filesTouched-mentioning neighbors; kept so historical
     *  runs that still record this mode value behave the same. Empty
     *  when the bug has no module or no real recordings exist yet. */
    private fun realTracesForBug(mode: String, bug: BugCatalog.BugMetadata?): List<File> {
        val module = bug?.module?.takeIf { it.isNotBlank() } ?: return emptyList()
        val all = realTraceCoverage(listOf(module))[module].orEmpty()
        if (all.isEmpty()) return emptyList()
        return when (mode) {
            "ON", "ON_ALL" -> all
            "ON_RECOMMENDED" -> {
                val targetClass = bug.hiddenTestClass?.substringAfterLast('.')
                val targetMethod = bug.hiddenTestMethod
                val byClass = all.filter { f ->
                    targetClass != null && f.name.contains(targetClass.replace('.', '_'))
                }
                val exact = if (targetMethod != null)
                    byClass.firstOrNull { it.name.contains(targetMethod) } else null
                val recommended = LinkedHashSet<File>().apply {
                    exact?.let { add(it) }
                    addAll(byClass.take(3))
                }
                if (recommended.isNotEmpty()) recommended.toList() else all.take(3)
            }
            else -> emptyList()
        }
    }

    /** Result of a generateRealTraces invocation. */
    data class GenerationResult(
        val module: String,
        val exitCode: Int,
        val tracesAfter: Int,
        val durationMs: Long,
        val ok: Boolean,
        val tail: String
    )

    /** Run gradle :MODULE:test under the AppMap agent and report what
     *  was produced. The flags below are all required to make traces
     *  actually appear -- omit any one and gradle silently exits
     *  SUCCESSFUL with zero .appmap.json files:
     *    - --no-configuration-cache: AppMap plugin 1.2.0 calls
     *      Task.project at execution time, which Gradle 9's
     *      configuration cache rejects.
     *    - --no-build-cache: gradle's build cache restores test
     *      outputs FROM-CACHE on the second invocation (the test
     *      sources/classpath are unchanged, so the cache key matches).
     *      Without this, the test task body never runs and no agent
     *      attaches.
     *    - :MODULE:cleanTest: deletes the local test report dir so
     *      the test task is no longer UP-TO-DATE for the local
     *      avoidance check (this is necessary IN ADDITION to
     *      --no-build-cache, not instead of).
     *  Caller is responsible for off-thread invocation -- a single
     *  module run takes 30s-5min depending on suite size. */
    fun generateRealTracesForModule(
        module: String,
        onProcessStarted: (Process) -> Unit = {}
    ): GenerationResult {
        val repo = bankingApp.bankingAppDir
        val started = System.currentTimeMillis()
        // Use Platform.gradleWrapper so Windows hosts launch
        // `gradlew.bat`, not the POSIX `gradlew` shell script (which
        // ProcessBuilder cannot invoke directly under cmd.exe and was
        // the root cause of "no traces collected" on Windows test runs).
        // Append connectionSettings.gradleSystemProps() so corp proxy
        // + mirror flags reach the gradle invocation -- without them,
        // testcontainers / datafaker / other public artifacts can't
        // resolve behind a corporate firewall and the trace-gen step
        // fails with "Could not resolve all files for configuration
        // ':shared-testing:compileClasspath'". The --no-daemon flag
        // means the build runs in the gradle CLIENT JVM (not a daemon),
        // so the -D flags from gradleSystemProps DO reach
        // System.getProperty() inside settings.gradle.kts.
        val cmd = mutableListOf<String>().apply {
            addAll(Platform.gradleWrapper(repo))
            add(":$module:cleanTest")
            add(":$module:test")
            add("-Pappmap_enabled=true")
            add("--no-configuration-cache")
            add("--no-build-cache")
            add("--no-daemon")
            add("-q")
            addAll(connectionSettings.gradleSystemProps())
        }
        val pb = ProcessBuilder(cmd)
            .directory(repo)
            .redirectErrorStream(true)
        // Pin JAVA_HOME to a JDK matching banking-app's toolchain
        // (currently 25). Without this, `gradle test` on a host
        // whose default `java -version` is older than the toolchain
        // fails with "Unsupported class file major version 69" once
        // gradle's daemon-internal tasks try to load compiled
        // bytecode. Same pattern AppMapService.kt uses for its other
        // gradle launches.
        val pinnedMajor = bankingApp.toolchainMajor()
        if (pinnedMajor != null) {
            val javaHome = runCatching {
                JdkDiscovery.bestAvailableHome(matchMajor = pinnedMajor)
            }.getOrNull()
            if (javaHome != null) {
                pb.environment()["JAVA_HOME"] = javaHome
                log.info("appmap-gen[{}]: JAVA_HOME={} (toolchain major={})",
                    module, javaHome, pinnedMajor)
            } else {
                log.warn("appmap-gen[{}]: no JDK {} found via JdkDiscovery; gradle daemon " +
                    "will use the host's default java which is likely to fail with " +
                    "'Unsupported class file major version' for banking-app's toolchain pin.",
                    module, pinnedMajor)
            }
        }
        val proc = pb.start()
        // Hand the live Process back to the caller (AdminTracesController)
        // so a cancel button can destroyForcibly() it. Also register a
        // shutdown hook so the gradle child dies when bench-webui dies
        // -- otherwise it's orphaned to launchd and keeps thrashing
        // CPU invisibly across restarts.
        onProcessStarted(proc)
        val shutdown = Thread { runCatching {
            proc.descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
        }}
        runCatching { Runtime.getRuntime().addShutdownHook(shutdown) }
        val tail = StringBuilder()
        proc.inputStream.bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (tail.length < 8_000) tail.appendLine(ln)
                log.debug("appmap-gen[{}]: {}", module, ln)
            }
        }
        val exit = proc.waitFor()
        val ms = System.currentTimeMillis() - started
        val tracesAfter = realTraceCoverage(listOf(module))[module].orEmpty().size
        return GenerationResult(
            module = module,
            exitCode = exit,
            tracesAfter = tracesAfter,
            durationMs = ms,
            ok = exit == 0 && tracesAfter > 0,
            tail = tail.takeLast(3000).toString()
        )
    }

    /** Best-effort `git rev-parse --short HEAD` against the banking-app
     *  repo. Returns null if the repo isn't located or git fails — the
     *  caller falls back to "unknown-sha" so the cache still works (just
     *  doesn't share across SHAs). */
    private fun headSha(): String? = runCatching {
        val repo = bankingApp.bankingAppDir
        if (!File(repo, ".git").exists()) return@runCatching null
        val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(repo).redirectErrorStream(true).start()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            runCatching { proc.destroyForcibly() }
            return@runCatching null
        }
        val sha = proc.inputStream.bufferedReader().readText().trim()
        if (proc.exitValue() == 0 && sha.isNotEmpty()) sha else null
    }.getOrNull()
}
