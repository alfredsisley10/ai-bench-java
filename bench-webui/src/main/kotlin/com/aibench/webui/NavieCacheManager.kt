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

    /** One LLM round-trip extracted from the appmap CLI's trajectory.
     *  Pairs the most-recent 'sent' event(s) with the immediately
     *  following 'received' so the operator can see WHAT Navie sent
     *  Copilot for each completion -- not just "Requesting completion
     *  / Received in 6.4s" with no idea why. */
    data class RoundTrip @com.fasterxml.jackson.annotation.JsonCreator constructor(
        @com.fasterxml.jackson.annotation.JsonProperty("seq") val seq: Int,
        @com.fasterxml.jackson.annotation.JsonProperty("sentAtMs") val sentAtMs: Long,
        @com.fasterxml.jackson.annotation.JsonProperty("receivedAtMs") val receivedAtMs: Long,
        @com.fasterxml.jackson.annotation.JsonProperty("durationMs") val durationMs: Long,
        @com.fasterxml.jackson.annotation.JsonProperty("sentRole") val sentRole: String,
        @com.fasterxml.jackson.annotation.JsonProperty("sentPreview") val sentPreview: String,
        @com.fasterxml.jackson.annotation.JsonProperty("sentChars") val sentChars: Int,
        @com.fasterxml.jackson.annotation.JsonProperty("receivedPreview") val receivedPreview: String,
        @com.fasterxml.jackson.annotation.JsonProperty("receivedChars") val receivedChars: Int,
        /** Heuristic label of which Navie stage produced this call:
         *  "classify" / "search" / "context-pack" / "answer" /
         *  "tool-use" / "?". Inferred from the system-prompt content. */
        @com.fasterxml.jackson.annotation.JsonProperty("inferredStage") val inferredStage: String
    )

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
        val durationMs: Long,
        /** Per-LLM-call breakdown for inspection. Lets the operator
         *  spot-check "did Navie really need 30 round-trips for a
         *  3-line bug fix?" by seeing the actual sent/received
         *  message previews and the inferred Navie stage. Empty for
         *  legacy cache entries. */
        val roundTrips: List<RoundTrip> = emptyList()
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
        @Volatile var commandLine: String? = null,
        /** Live round-trip count parsed from the in-flight trajectory.
         *  Updated by the heartbeat thread along with trajectoryEventsLive
         *  / lastEventAt. Lets the dashboard show "completion N of ~M
         *  (Z%)" by comparing to the median across cached results. */
        @Volatile var liveRoundTripCount: Int? = null,
        /** Reference temp trajectory file for the per-round-trip
         *  inspection table on the live detail page (parsed lazily by
         *  the controller; null after the job ends). */
        @Volatile var liveTrajectoryFile: java.io.File? = null
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

    private fun cacheFile(bugId: String, breakSha: String): File {
        // Sanitize the sha-or-ref segment to a flat filename. breakCommit
        // is a literal git ref ("bug/BUG-0001/break"), so the raw value
        // has '/' in it -- joining with '-' would create unintended
        // subdirectories. Map every non-[A-Za-z0-9._] char to '-'.
        val safeSha = breakSha.replace(Regex("[^A-Za-z0-9._-]"), "-")
        return File(cacheRoot, "${bugId}-${safeSha}.json")
    }

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

    /** Median round-trip count across all cached results in this
     *  install. Used as a "typical N completions" denominator for the
     *  live progress estimate ("8 of ~30 typical, ~27%"). Returns 0
     *  when the cache is empty (the UI then just shows the raw
     *  count without a percent). */
    fun medianCachedRoundTripCount(): Int {
        val counts = (cacheRoot.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: emptyArray())
            .mapNotNull {
                runCatching { mapper.readValue(it, NavieResult::class.java).roundTrips.size }
                    .getOrNull()?.takeIf { n -> n > 0 }
            }
            .sorted()
        if (counts.isEmpty()) return 0
        return counts[counts.size / 2]
    }

    /** Live round-trip table for the in-flight precompute -- parses
     *  the temp trajectory file each call. Caller should already
     *  guard on activeJob != null && job.endedAt == null. Returns
     *  empty list when there's no live trajectory file (job in
     *  preparing/parsing phase between subprocess events). */
    fun liveRoundTrips(bugId: String): List<RoundTrip> {
        val job = activeJobs[bugId] ?: return emptyList()
        val tf = job.liveTrajectoryFile ?: return emptyList()
        return parseRoundTrips(tf)
    }

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
            val module = bug.module.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("bug ${bug.id} has no module")
            val traceRoot = File(repo, "$module/tmp/appmap")
            val traces = if (traceRoot.isDirectory)
                traceRoot.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".appmap.json") }
                    .toList()
            else emptyList()
            if (traces.isEmpty()) {
                throw IllegalStateException(
                    "no AppMap traces under ${traceRoot.absolutePath} -- " +
                    "generate via /admin/appmap-traces first")
            }
            // Use the bug title + first 200 chars of the problem statement
            // as the search query. AppMap search tokenizes against the
            // function names + classes captured in the trace, so concise
            // domain-language terms (e.g. "ACH cutoff time") match best.
            val query = (bug.title + " " + bug.problemStatement.take(200))
                .replace(Regex("[^A-Za-z0-9 ]"), " ")
                .replace(Regex(" +"), " ").trim()
            log.info("appmap-search[{}] starting: module={} traces={} query=\"{}\"",
                bug.id, module, traces.size, query.take(80))
            // Reproducible command for the detail page
            job.commandLine = "for trace in (${traces.size} traces under " +
                "$module/tmp/appmap):\n  ${cli.absolutePath} search " +
                "-a <trace> --find-events \"${query.take(80)}\""
            val started = System.currentTimeMillis()
            data class FileScore(val location: String, var totalScore: Double, var hitCount: Int)
            val fileScores = HashMap<String, FileScore>()
            val traceScores = HashMap<File, Double>()
            val tail = StringBuilder()
            // Per-trace search. Each invocation is sub-second; we get
            // function-level relevance scores + the source-file
            // location each function lives in. Aggregate by location.
            for ((idx, trace) in traces.withIndex()) {
                job.phase = "searching ${idx + 1}/${traces.size}"
                job.liveRoundTripCount = idx + 1
                val proc = ProcessBuilder(
                    cli.absolutePath, "search",
                    "-a", trace.absolutePath,
                    "--find-events", query
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                val ok = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!ok) { proc.destroyForcibly(); continue }
                val node = runCatching { mapper.readTree(out) }.getOrNull() ?: continue
                val results = node.get("results") ?: continue
                var traceTotal = 0.0
                var hits = 0
                for (r in results) {
                    val loc = r.get("location")?.asText() ?: continue
                    val score = r.get("score")?.asDouble() ?: 0.0
                    val pathOnly = loc.substringBeforeLast(":")
                    fileScores.getOrPut(pathOnly) { FileScore(pathOnly, 0.0, 0) }
                        .also { it.totalScore += score; it.hitCount++ }
                    traceTotal += score
                    hits++
                }
                if (traceTotal > 0) traceScores[trace] = traceTotal
                val msg = "trace ${idx + 1}/${traces.size}: ${trace.name.take(60)} " +
                    "-> ${hits} hits, score=${"%.2f".format(traceTotal)}"
                tail.appendLine(msg)
                if (tail.length > TAIL_CAP) tail.delete(0, tail.length - TAIL_CAP)
                job.stdoutTail = tail.toString()
                log.info("appmap-search[{}] {}", bug.id, msg)
            }
            val ms = System.currentTimeMillis() - started
            // Rank main-source files ahead of test-source files. Test
            // files often score higher on keyword overlap (the test
            // describes the bug behaviorally) but the LLM solving the
            // bug needs the actual implementation in its prompt --
            // showing the test first risks the model patching the
            // test rather than the source. Within each tier, sort by
            // aggregate AppMap-search score.
            fun isMain(loc: String) = loc.contains("/src/main/")
            val ranked = fileScores.values.sortedWith(
                compareByDescending<FileScore> { isMain(it.location) }
                    .thenByDescending { it.totalScore }
            )
            val topFiles = ranked.take(10).map { it.location }
            val topTraces = traceScores.entries.sortedByDescending { it.value }
                .take(10).map {
                    it.key.absolutePath.removePrefix(repo.absolutePath).trimStart('/')
                }
            val answer = buildString {
                appendLine("AppMap search across ${traces.size} trace(s) for module '${bug.module}'.")
                appendLine("Query: \"${query.take(120)}\"")
                appendLine()
                appendLine("Top files by aggregate relevance score:")
                for ((i, fs) in fileScores.values.sortedByDescending { it.totalScore }.take(8).withIndex()) {
                    appendLine("  ${i + 1}. ${fs.location} (score=${"%.2f".format(fs.totalScore)}, ${fs.hitCount} hit(s))")
                }
            }
            log.info("appmap-search[{}] done in {}s: {} files, {} traces",
                bug.id, ms / 1000, topFiles.size, topTraces.size)
            val result = NavieResult(
                bugId = bug.id,
                breakCommit = bug.breakCommit,
                generatedAt = Instant.now().toString(),
                cliVersion = readCliVersion(cli),
                model = "appmap-search",  // not Navie; no LLM model used
                filesIdentified = topFiles,
                tracesIdentified = topTraces,
                answer = answer,
                trajectoryEventCount = traces.size,
                durationMs = ms,
                roundTrips = emptyList()  // search makes no LLM calls
            )
            cacheFile(bug.id, bug.breakCommit).writeText(mapper.writeValueAsString(result))
            job.result = result
            job.phase = "cached"
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

    /** Walk the trajectory and pair each 'received' event with the
     *  immediately-preceding contiguous block of 'sent' events into
     *  a [RoundTrip]. Lets the detail page show "what was actually
     *  sent for completion N" instead of just timings. The inferred-
     *  stage classifier is heuristic -- looks at the most-recent
     *  system-message content for keywords ("classify", "search",
     *  "context", "answer") -- so it's a hint, not a guarantee. */
    fun parseRoundTrips(trajFile: File): List<RoundTrip> {
        if (!trajFile.isFile) return emptyList()
        data class Sent(val ts: Long, val role: String, val content: String)
        val pendingSent = mutableListOf<Sent>()
        val out = mutableListOf<RoundTrip>()
        var seq = 0
        trajFile.useLines { lines ->
            for (ln in lines) {
                val node = runCatching { mapper.readTree(ln) }.getOrNull() ?: continue
                val type = node.get("type")?.asText() ?: continue
                val ts = parseTsToMs(node.get("timestamp")?.asText())
                val msg = node.get("message")
                val role = msg?.get("role")?.asText() ?: "?"
                val content = (msg?.get("content")?.asText() ?: "")
                if (type == "sent") {
                    pendingSent.add(Sent(ts, role, content))
                } else if (type == "received") {
                    seq++
                    val lastUserOrSystem = pendingSent.lastOrNull { it.role == "user" || it.role == "system" }
                        ?: pendingSent.lastOrNull()
                    val firstSentTs = pendingSent.firstOrNull()?.ts ?: ts
                    val sentChars = pendingSent.sumOf { it.content.length }
                    val sentPreview = lastUserOrSystem?.content?.take(280) ?: ""
                    val sentRole = lastUserOrSystem?.role ?: "?"
                    out += RoundTrip(
                        seq = seq,
                        sentAtMs = firstSentTs,
                        receivedAtMs = ts,
                        durationMs = (ts - firstSentTs).coerceAtLeast(0),
                        sentRole = sentRole,
                        sentPreview = sentPreview,
                        sentChars = sentChars,
                        receivedPreview = content.take(280),
                        receivedChars = content.length,
                        inferredStage = inferStage(pendingSent.map { it.content })
                    )
                    pendingSent.clear()
                }
            }
        }
        return out
    }

    private fun parseTsToMs(s: String?): Long {
        if (s == null) return 0
        return runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
    }

    private fun inferStage(sentContents: List<String>): String {
        val all = sentContents.joinToString("\n").lowercase()
        return when {
            all.contains("classify") || all.contains("classification") -> "classify"
            all.contains("search appmap") || all.contains("vector_search") ||
                all.contains("search the appmap") -> "search"
            all.contains("context.context") || all.contains("context-pack") ||
                all.contains("read the file") || all.contains("file_contents") -> "context-pack"
            all.contains("formulate the response") || all.contains("draft your answer") ||
                all.contains("synthes") -> "answer"
            all.contains("tool_call") || all.contains("function_call") -> "tool-use"
            else -> "?"
        }
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
