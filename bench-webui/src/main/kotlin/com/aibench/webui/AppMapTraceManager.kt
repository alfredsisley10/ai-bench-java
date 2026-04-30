package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit

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
class AppMapTraceManager(private val bankingApp: BankingAppManager) {

    private val log = LoggerFactory.getLogger(AppMapTraceManager::class.java)

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
        val sha = headSha() ?: "unknown-sha"
        val rootDir = File(System.getProperty("user.home"), ".ai-bench/appmap-traces")
        val cacheDir = File(rootDir, "$sha/$mode")
        val markerFile = File(cacheDir, ".generated")
        val lockFile = File(rootDir, ".$sha-$mode.lock")
        rootDir.mkdirs()

        // FileChannel.lock blocks; the second concurrent caller waits
        // here while the first records, then sees the marker and
        // returns the cached inventory without re-generating.
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
            // AppMap files are JSON with a 'metadata' block + 'classMap'
            // + 'events' arrays. The stub here keeps the shape so a
            // reader can parse it; bodies are empty until Layer C wires
            // real recording. Identifiable as synthetic via the
            // 'recorder' field so a downstream Navie call can refuse to
            // search synthetic-only caches if desired.
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
                  "classMap": [],
                  "events": []
                }
                """.trimIndent()
            )
            file
        }
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
