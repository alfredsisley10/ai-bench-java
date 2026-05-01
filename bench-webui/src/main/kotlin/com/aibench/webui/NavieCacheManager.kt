package com.aibench.webui

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Layer-C AppMap-Navie integration: drives the local `appmap navie`
 * CLI to produce a per-bug, per-break-commit context bundle and
 * persists the result to disk so subsequent benchmark runs can
 * pick the LLM-selected files instantly (cache hit) instead of
 * re-running the 15-30 minute Navie agentic loop every time.
 *
 * Cache shape:
 *   ~/.ai-bench/navie-cache/<bugId>-<break-sha>.json
 * containing the parsed [NavieResult]. Stale entries (CLI version
 * change, model change) are NOT auto-invalidated — operator
 * runs precompute again to refresh.
 *
 * The CLI is wired to the locally-running Copilot bridge via
 * OPENAI_BASE_URL so Navie's LangChain client routes through the
 * same endpoint the rest of the harness uses. APPMAP_NAVIE_MODEL
 * picks which Copilot-published model Navie's reasoning uses.
 */
@Component
class NavieCacheManager(
    private val bugCatalog: BugCatalog,
    private val bankingApp: BankingAppManager
) {
    private val log = LoggerFactory.getLogger(NavieCacheManager::class.java)
    // Plain Jackson — no jackson-module-kotlin on the classpath. Reading
    // back into a Kotlin data class works because Jackson can construct
    // Kotlin `data class` POJOs through their Java-style ctor when
    // there's a single matching ctor and no defaulted params, which is
    // the case for [NavieResult].
    private val mapper: ObjectMapper = ObjectMapper()

    /** Stdout/stderr tail cap (chars) per active job. ~16K is enough
     *  to span Navie's "configuration loaded -> classifying -> searching
     *  -> reading file X" preamble plus a recent batch of progress
     *  lines without growing unbounded. */
    private val TAIL_CAP = 16_000

    /** Background precompute jobs the operator launched, keyed by bugId.
     *  Lives in-memory only — restarts wipe the running set, but the
     *  on-disk cache itself survives. */
    private val activeJobs = ConcurrentHashMap<String, JobStatus>()

    data class NavieResult(
        val bugId: String,
        val breakCommit: String,
        val generatedAt: String,
        val cliVersion: String?,
        val model: String?,
        /** Repo-relative .java paths Navie surfaced during its
         *  search-and-context loop. Parsed out of the trajectory
         *  JSONL since Navie doesn't expose a structured "selected
         *  files" output. */
        val filesIdentified: List<String>,
        /** Repo-relative .appmap.json trace paths Navie referenced
         *  during the same loop -- the runtime side of its retrieval
         *  (call graphs / SQL / HTTP captures from the AppMap index).
         *  Lets the operator audit "Navie said it looked at these N
         *  traces" separately from the source-file picks above. */
        val tracesIdentified: List<String> = emptyList(),
        /** Final markdown answer Navie wrote — useful as additional
         *  prompt context for the solver. */
        val answer: String,
        val trajectoryEventCount: Int,
        val durationMs: Long
    )

    data class JobStatus(
        val bugId: String,
        val startedAt: Instant,
        @Volatile var phase: String,
        @Volatile var endedAt: Instant? = null,
        @Volatile var error: String? = null,
        @Volatile var result: NavieResult? = null,
        /** Live OS process backing this job, captured the moment the
         *  appmap CLI is spawned. cancel() destroyForcibly's it.
         *  Null while we're in the "preparing"/"parsing" phases or
         *  after the job ends. */
        @Volatile var process: Process? = null,
        /** Heartbeat fields populated by the trajectory-watcher thread
         *  in [precompute] every few seconds while the appmap CLI is
         *  running. Lets the dashboard / admin UI distinguish "Navie
         *  is slow" (events ticking, file growing) from "Navie hung"
         *  (no progress for minutes despite still being process-alive).
         *  Updated to null after the job ends. */
        @Volatile var trajectoryEventsLive: Int? = null,
        @Volatile var trajectoryBytesLive: Long? = null,
        @Volatile var lastEventAt: Instant? = null,
        /** Tail of the appmap CLI's stdout/stderr (combined; we
         *  redirectErrorStream(true) when starting the subprocess).
         *  Capped at TAIL_CAP chars so a multi-hour run doesn't
         *  balloon memory; still enough to see what stage the CLI
         *  is in. Surfaced on the per-bug detail page. */
        @Volatile var stdoutTail: String? = null,
        /** Reproducible command line that was launched. Joined with
         *  spaces; quoting is approximate (good enough for an
         *  operator to copy-paste and rerun manually). Includes the
         *  Navie-relevant env vars prepended in `KEY=value` form. */
        @Volatile var commandLine: String? = null
    )

    /** Cancel an in-flight precompute. Kills the appmap subprocess,
     *  marks the job failed, and returns true when there was actually
     *  something to cancel. The harness's bridge-mutex serialization
     *  means the next queued job will start almost immediately. */
    fun cancel(bugId: String): Boolean {
        val job = activeJobs[bugId] ?: return false
        if (job.endedAt != null) return false
        val p = job.process
        if (p != null && p.isAlive) {
            runCatching { p.descendants().forEach { it.destroyForcibly() } }
            runCatching { p.destroyForcibly() }
        }
        job.error = "canceled by operator"
        job.phase = "canceled"
        job.endedAt = Instant.now()
        return true
    }

    private val cacheRoot: File = File(System.getProperty("user.home"), ".ai-bench/navie-cache")
        .also { it.mkdirs() }

    private fun cacheFile(bugId: String, breakSha: String): File =
        File(cacheRoot, "${bugId}-${breakSha}.json")

    /** Cached result for the bug at its CURRENT break commit, or null. */
    fun get(bug: BugCatalog.BugMetadata): NavieResult? {
        val sha = bug.breakCommit
        val f = cacheFile(bug.id, sha)
        if (!f.isFile) return null
        return runCatching { mapper.readValue(f, NavieResult::class.java) }
            .onFailure { log.warn("navie cache: failed to parse {}: {}", f, it.message) }
            .getOrNull()
    }

    /** Lightweight per-bug status: cached / running / missing. */
    fun status(bug: BugCatalog.BugMetadata): String {
        if (activeJobs[bug.id]?.endedAt == null && activeJobs[bug.id] != null) return "running"
        if (cacheFile(bug.id, bug.breakCommit).isFile) return "cached"
        return "missing"
    }

    fun activeJob(bugId: String): JobStatus? = activeJobs[bugId]

    /** Snapshot of every job currently in flight (endedAt == null).
     *  Used by the dashboard's "background tasks" tile so the operator
     *  sees Navie precomputes monopolizing the bridge without having
     *  to navigate to /admin/navie. Newest-first. */
    fun runningJobs(): List<JobStatus> =
        activeJobs.values.filter { it.endedAt == null }
            .sortedByDescending { it.startedAt }

    /**
     * Run `appmap navie` for the bug, write the result to cache. Blocks
     * the caller; intended to be invoked from a background executor in
     * the admin controller. Returns the JobStatus (also stored in
     * [activeJobs] so the UI can poll without holding a reference).
     */
    fun precompute(bug: BugCatalog.BugMetadata): JobStatus {
        val job = JobStatus(bug.id, Instant.now(), phase = "preparing")
        activeJobs[bug.id] = job
        try {
            val repo = bankingApp.bankingAppDir
            if (!File(repo, ".git").isDirectory) {
                throw IllegalStateException("banking-app not a git checkout at ${repo.absolutePath}")
            }
            val cli = locateCli() ?: throw IllegalStateException(
                "appmap CLI not found. Install JetBrains AppMap plugin or " +
                    "`npm install -g @appland/appmap`.")
            // Per-job tmp files; Navie writes both the trajectory and the
            // final answer file. We delete them after parsing.
            val workDir = createTempDir("ai-bench-navie-${bug.id}-")
            val trajFile = File(workDir, "trajectory.jsonl")
            val answerFile = File(workDir, "answer.md")
            try {
                job.phase = "running navie"
                val started = System.currentTimeMillis()
                val argv = listOf(
                    cli.absolutePath, "navie",
                    "-d", repo.absolutePath,
                    "--trajectory-file", trajFile.absolutePath,
                    "-o", answerFile.absolutePath,
                    buildQuestion(bug)
                )
                val env = navieEnv()
                // Capture the exact command line + relevant env vars so
                // the operator can copy-paste-rerun manually if they
                // need to reproduce a specific Navie call. Quoting is
                // approximate (good-enough); the question arg gets
                // extra escaping since it's multiline.
                fun shesc(s: String): String =
                    if (s.matches(Regex("[A-Za-z0-9_/.:-]+"))) s
                    else "'" + s.replace("'", "'\\''") + "'"
                job.commandLine = (env.entries.joinToString(" ") {
                    "${it.key}=${shesc(it.value)}"
                }) + " " + argv.joinToString(" ") { shesc(it) }
                val proc = ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .also { it.environment().putAll(env) }
                    .start()
                job.process = proc
                // Best-effort: kill the subprocess if the bench-webui
                // JVM exits while this job is in flight. Without this
                // hook the appmap CLI is orphaned to launchd/init and
                // keeps hammering the bridge invisibly across restarts.
                val shutdown = Thread { runCatching {
                    proc.descendants().forEach { it.destroyForcibly() }
                    proc.destroyForcibly()
                }}
                runCatching { Runtime.getRuntime().addShutdownHook(shutdown) }
                // Heartbeat thread: poll the trajectory file every 5s so
                // the dashboard can show "still progressing -- N events,
                // last event Xs ago" vs "looks hung -- 0 events written
                // in 60s". Cheap (just a stat + line-count); the read
                // happens off the main wait thread so the subprocess
                // I/O loop below isn't blocked.
                val heartbeat = Thread({
                    while (proc.isAlive && !Thread.currentThread().isInterrupted) {
                        try {
                            Thread.sleep(5000)
                            if (trajFile.isFile) {
                                val sz = trajFile.length()
                                val ec = trajFile.useLines { it.count() }
                                val mt = java.nio.file.Files.getLastModifiedTime(trajFile.toPath())
                                job.trajectoryBytesLive = sz
                                job.trajectoryEventsLive = ec
                                job.lastEventAt = mt.toInstant()
                            }
                        } catch (_: InterruptedException) { break }
                        catch (_: Exception) { /* ignore transient stat errors */ }
                    }
                }, "navie-heartbeat-${bug.id}").apply { isDaemon = true; start() }
                // Stream combined stdout/stderr; keep a rolling tail in
                // [job.stdoutTail] so the per-bug detail page can show
                // recent CLI output without holding the full byte stream
                // in memory.
                val tail = StringBuilder()
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (ln in lines) {
                        tail.appendLine(ln)
                        if (tail.length > TAIL_CAP) {
                            tail.delete(0, tail.length - TAIL_CAP)
                        }
                        job.stdoutTail = tail.toString()
                        log.debug("navie[{}]: {}", bug.id, ln)
                    }
                }
                heartbeat.interrupt()
                val exit = proc.waitFor()
                val ms = System.currentTimeMillis() - started
                if (exit != 0) {
                    throw RuntimeException("appmap navie exit=$exit; tail:\n${tail.takeLast(2000)}")
                }
                job.phase = "parsing"
                val files = parseFilesFromTrajectory(trajFile)
                val traces = parseTracesFromTrajectory(trajFile)
                val answer = if (answerFile.isFile) answerFile.readText() else ""
                val eventCount = if (trajFile.isFile) trajFile.useLines { it.count() } else 0
                val result = NavieResult(
                    bugId = bug.id,
                    breakCommit = bug.breakCommit,
                    generatedAt = Instant.now().toString(),
                    cliVersion = readCliVersion(cli),
                    model = navieEnv()["APPMAP_NAVIE_MODEL"],
                    filesIdentified = files,
                    tracesIdentified = traces,
                    answer = answer,
                    trajectoryEventCount = eventCount,
                    durationMs = ms
                )
                cacheFile(bug.id, bug.breakCommit).writeText(mapper.writeValueAsString(result))
                job.result = result
                job.phase = "cached"
            } finally {
                workDir.deleteRecursively()
            }
        } catch (e: Exception) {
            log.warn("navie precompute failed for {}: {}", bug.id, e.message)
            job.phase = "failed"
            job.error = e.message ?: e.javaClass.simpleName
        } finally {
            job.endedAt = Instant.now()
        }
        return job
    }

    /** Default CLI lookup order: $APPMAP_CLI override → user-local
     *  install (matches JetBrains plugin layout) → system PATH. */
    fun locateCli(): File? {
        System.getenv("APPMAP_CLI")?.let { File(it) }?.takeIf { it.canExecute() }?.let { return it }
        val home = File(System.getProperty("user.home"), ".appmap/bin/appmap")
        if (home.canExecute()) return home
        for (p in System.getenv("PATH").orEmpty().split(File.pathSeparator)) {
            val f = File(p, "appmap")
            if (f.canExecute()) return f
        }
        return null
    }

    private fun readCliVersion(cli: File): String? = runCatching {
        ProcessBuilder(cli.absolutePath, "--version").redirectErrorStream(true).start()
            .let { p -> p.inputStream.bufferedReader().readText().trim().also { p.waitFor() } }
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Question Navie searches against. Builds from the bug's catalog
     *  problem statement + a hint that we want the buggy file
     *  identified, since Navie's default agentic loop is open-ended
     *  and we want it focused on locating the source under fix. */
    private fun buildQuestion(bug: BugCatalog.BugMetadata): String =
        "Identify the file(s) containing the bug described below and " +
            "explain the root cause.\n\nBug ${bug.id}: ${bug.title}\n\n" +
            bug.problemStatement

    /** Wires Navie's LangChain OpenAI client at the local Copilot
     *  bridge. APPMAP_NAVIE_MODEL is read by the AppMap CLI even
     *  though it isn't documented in --help; the source uses it
     *  in `local-navie` config.
     *  APPMAP_API_KEY is set to a sentinel so Navie doesn't print
     *  "Warning: No license key provided. Please set the APPMAP_API_KEY
     *  environment variable." on every invocation -- our local-only
     *  setup doesn't need an AppMap-hosted license but the CLI's
     *  warning message clutters the stdout tail and slows the
     *  startup banner. The sentinel is treated as "no real key" by
     *  the CLI but suppresses the noisy warning. */
    private fun navieEnv(): Map<String, String> = mapOf(
        "OPENAI_API_KEY" to (System.getenv("OPENAI_API_KEY") ?: "anything"),
        "OPENAI_BASE_URL" to (System.getenv("APPMAP_BRIDGE_URL")
            ?: "http://127.0.0.1:11434/v1"),
        "APPMAP_API_KEY" to (System.getenv("APPMAP_API_KEY")
            ?: "no-license-local-only"),
        // VSCode's LanguageModelChat exposes Copilot's gpt-4.1 model
        // under the literal id "gpt-4.1" (no "copilot-" prefix). Sending
        // any other name like "copilot-gpt-4-1" used to hit the bridge's
        // pickModel() which silently fell back to all[0] -- which is
        // claude-sonnet-4.6, a premium model. The bridge's pickModel
        // is now strict (rejects unknown names) AND it tries fuzzy
        // matching, but we send the exact id here as primary defense.
        "APPMAP_NAVIE_MODEL" to (System.getenv("APPMAP_NAVIE_MODEL")
            ?: "gpt-4.1")
    )

    /** Walk the JSONL trajectory and pull every repo-relative
     *  `<module>/src/(main|test)/java/.../Foo.java` path Navie
     *  mentioned. Dedup + return sorted. Navie does emit explicit
     *  tool-call events for file reads, but the path also appears
     *  inline in many "received" message bodies, so a regex sweep
     *  catches both shapes. */
    private fun parseFilesFromTrajectory(trajFile: File): List<String> {
        if (!trajFile.isFile) return emptyList()
        val pathRegex = Regex(
            """([a-z][a-z0-9-]+/)+src/(main|test)/java/[A-Za-z0-9_/]+\.java"""
        )
        val seen = sortedSetOf<String>()
        trajFile.useLines { lines ->
            for (ln in lines) {
                for (m in pathRegex.findAll(ln)) seen.add(m.value)
            }
        }
        return seen.toList()
    }

    /** Parse `.appmap.json` references out of the trajectory. Navie's
     *  agentic loop uses appmap CLI's vector index to pick relevant
     *  traces; the trajectory records each load as either an absolute
     *  path or a repo-relative path. We return the relative form
     *  ("<module>/tmp/appmap/junit/<sanitized-name>.appmap.json") so
     *  it lines up with what AppMapTraceManager.realTraceCoverage
     *  enumerates -- gives the operator a clean cross-reference between
     *  "Navie said it looked at these traces" and "these traces actually
     *  exist on disk". */
    private fun parseTracesFromTrajectory(trajFile: File): List<String> {
        if (!trajFile.isFile) return emptyList()
        val absRegex = Regex(
            """/[A-Za-z0-9_/.-]+/tmp/appmap/[a-z]+/[A-Za-z0-9_.-]+\.appmap\.json"""
        )
        val relRegex = Regex(
            """([a-z][a-z0-9-]+/)+tmp/appmap/[a-z]+/[A-Za-z0-9_.-]+\.appmap\.json"""
        )
        val repoPrefix = runCatching { bankingApp.bankingAppDir.absolutePath }.getOrNull()
        val seen = sortedSetOf<String>()
        trajFile.useLines { lines ->
            for (ln in lines) {
                for (m in absRegex.findAll(ln)) {
                    val abs = m.value
                    val rel = if (repoPrefix != null && abs.startsWith(repoPrefix))
                        abs.removePrefix(repoPrefix).trimStart('/')
                    else abs
                    seen.add(rel)
                }
                for (m in relRegex.findAll(ln)) seen.add(m.value)
            }
        }
        return seen.toList()
    }
}
