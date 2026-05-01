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
        @Volatile var result: NavieResult? = null
    )

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
                val proc = ProcessBuilder(
                    cli.absolutePath, "navie",
                    "-d", repo.absolutePath,
                    "--trajectory-file", trajFile.absolutePath,
                    "-o", answerFile.absolutePath,
                    buildQuestion(bug)
                )
                    .redirectErrorStream(true)
                    .also { it.environment().putAll(navieEnv()) }
                    .start()
                // Stream stdout to log so a hung Navie is visible.
                val tail = StringBuilder()
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (ln in lines) {
                        if (tail.length < 8_000) tail.appendLine(ln)
                        log.debug("navie[{}]: {}", bug.id, ln)
                    }
                }
                val exit = proc.waitFor()
                val ms = System.currentTimeMillis() - started
                if (exit != 0) {
                    throw RuntimeException("appmap navie exit=$exit; tail:\n${tail.takeLast(2000)}")
                }
                job.phase = "parsing"
                val files = parseFilesFromTrajectory(trajFile)
                val answer = if (answerFile.isFile) answerFile.readText() else ""
                val eventCount = if (trajFile.isFile) trajFile.useLines { it.count() } else 0
                val result = NavieResult(
                    bugId = bug.id,
                    breakCommit = bug.breakCommit,
                    generatedAt = Instant.now().toString(),
                    cliVersion = readCliVersion(cli),
                    model = navieEnv()["APPMAP_NAVIE_MODEL"],
                    filesIdentified = files,
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
     *  in `local-navie` config. */
    private fun navieEnv(): Map<String, String> = mapOf(
        "OPENAI_API_KEY" to (System.getenv("OPENAI_API_KEY") ?: "anything"),
        "OPENAI_BASE_URL" to (System.getenv("APPMAP_BRIDGE_URL")
            ?: "http://127.0.0.1:11434/v1"),
        "APPMAP_NAVIE_MODEL" to (System.getenv("APPMAP_NAVIE_MODEL")
            ?: "copilot-gpt-4-1")
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
}
