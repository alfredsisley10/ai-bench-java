package com.aibench.webui

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Synthetic benchmark execution engine. Drives a per-run worker thread
 * that walks the realistic harness lifecycle (prepare → discover →
 * collect-traces → prime → solve → score → report) and emits phased
 * status + log events in near-real time.
 *
 * <p>The service is deliberately independent of any actual LLM /
 * test-runner integration — it reproduces the <em>shape</em> of an
 * end-to-end run so the UI can be built, demo'd, and stress-tested
 * without burning real solver tokens. Swap {@link #simulate} for the
 * real harness invocation when wiring in.
 */
@Component
class BenchmarkRunService(
    private val priceCatalog: ModelPriceCatalog,
    private val realExecutor: RealBenchmarkExecutor,
    private val bugCatalog: BugCatalog,
    private val contextProvider: ContextProvider,
    private val traceManager: AppMapTraceManager,
    private val throttler: AdaptiveThrottler
) {

    private val log = LoggerFactory.getLogger(BenchmarkRunService::class.java)
    /** Reused Jackson ObjectMapper for parsing bridge responses. Single
     *  instance is fine — ObjectMapper is documented thread-safe once
     *  configuration is locked. */
    private val jsonMapper = ObjectMapper()

    enum class Status { QUEUED, RUNNING, PASSED, FAILED, ERRORED, CANCELED }

    /** Coarse log categories — UI uses these to color/icon entries. */
    enum class Category {
        PHASE,    // major phase boundary
        INFO,     // generic status
        TEST,     // failing-test discovery / scoring
        TRACE,    // AppMap trace recorded
        CONTEXT,  // trace / source submitted to the LLM as context
        LLM,      // LLM call (request/response)
        RESULT,   // pass/fail outcome
        ERROR
    }

    data class LogEntry(
        val ts: Instant,
        val category: Category,
        val message: String
    )

    data class SeedResult(
        val seed: Int,
        val passed: Boolean,
        val testsPassed: Int,
        val testsTotal: Int,
        /** Total wall time for this seed = solveMs + executionMs. */
        val durationMs: Long,
        val promptTokens: Int,
        val completionTokens: Int,
        /** Time spent waiting on the LLM (or simulated LLM) for the
         *  patch response only. Excludes patch-apply and test-run cost. */
        val solveMs: Long = 0L,
        /** Time spent applying the patch + running gradle tests; the
         *  "benchmark execution" portion of the seed wall clock. */
        val executionMs: Long = 0L
    )

    /**
     * Full audit trail of what one seed actually did, so the operator
     * can reconstruct the run end-to-end on /results/{runId}/seed/{n}.
     * Every field is nullable -- when a step didn't run (e.g. the LLM
     * call failed before patch extraction) the corresponding field
     * stays null and the audit page renders "skipped" rather than
     * inventing data. The full body of the LLM response and the test
     * stdout are kept here so the audit doesn't depend on the bridge's
     * own per-call records (which are scoped to bridge lifetime).
     */
    /** Per-context-file detail captured on the SeedAudit so the audit
     *  page can list every file the provider shipped to the LLM. */
    data class AuditContextFile(
        val path: String,
        val ref: String?,
        val sizeChars: Int,
        val score: Double?
    )

    /** Per-AppMap-trace detail captured on the SeedAudit so the audit
     *  page shows exactly which trace files were available + which got
     *  shipped to the LLM, plus the on-disk path the operator can
     *  inspect directly. */
    data class AuditTraceFile(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val submittedToPrompt: Boolean,
        /** Base64-url-encoded absolute path. Audit page deep-links to
         *  /demo/appmap/view?id=${viewerId} so the full embedded AppMap
         *  viewer (sequence diagram + call tree) renders against the
         *  cache trace just as it does for banking-app/**/tmp/appmap
         *  traces. */
        val viewerId: String
    )

    data class SeedAudit(
        val seed: Int,
        val systemPrompt: String? = null,
        val userPrompt: String? = null,
        val llmRawResponse: String? = null,
        val extractedPatch: String? = null,
        val patchApplied: Boolean = false,
        val verificationCommand: String? = null,
        val testStdoutTail: String? = null,
        val testExitCode: Int? = null,
        val outcome: String? = null,
        val outcomeMessage: String? = null,
        // ---- Context-provider audit (which retrieval strategy ran +
        //      which files it shipped + why). Lets the operator
        //      reproduce the run by re-running the same provider.
        val contextProvider: String? = null,
        val effectiveContextProvider: String? = null,
        val contextRationale: String? = null,
        val contextFellBack: Boolean = false,
        val contextFiles: List<AuditContextFile> = emptyList(),
        // ---- AppMap trace audit. Mode + cache key tell the operator
        //      which trace inventory was used; traceFiles enumerates
        //      every file in the cache so they can spot-check directly.
        //      submittedToPrompt distinguishes "available for retrieval"
        //      (Navie may pick a subset) from "actually packed in".
        val traceMode: String? = null,
        val traceCacheSha: String? = null,
        val traceCacheDir: String? = null,
        val traceCacheGenerated: Boolean = false,
        val traceCacheSynthetic: Boolean = false,
        val tracesAvailable: Int = 0,
        val tracesSubmitted: Int = 0,
        val traceFiles: List<AuditTraceFile> = emptyList()
    )

    data class RunStats(
        val tracesRecorded: Int = 0,
        val tracesSubmitted: Int = 0,
        val sourceFilesSubmitted: Int = 0,
        val totalPromptTokens: Int = 0,
        val totalCompletionTokens: Int = 0,
        val estimatedCostUsd: Double = 0.0
    )

    data class BenchmarkRun(
        val id: String,
        val issueId: String,
        val issueTitle: String,
        val provider: String,
        /** Registry-side id chosen on the launcher form. Prefixed for
         *  uniqueness across providers (e.g. `copilot-gpt-4-1`). Used
         *  for catalog joins, dashboard display, and audit linking. */
        val modelId: String,
        /** Vendor-side identifier sent over the wire to the bridge /
         *  gateway (e.g. `gpt-4.1`, `claude-sonnet-4.6`). Distinct from
         *  modelId because the bridge has no knowledge of the registry's
         *  prefix scheme — passing the registry id makes the bridge
         *  silently fall back to whatever Copilot's default model is,
         *  which produces wrong-model bills + audit confusion. The
         *  launcher resolves modelId → modelIdentifier via the registry
         *  and passes both through to start(). */
        val modelIdentifier: String,
        /** "none" or "appmap-navie" — orthogonal to the LLM
         *  provider/model. When "appmap-navie" the harness wraps the
         *  prompt with Navie's AppMap-driven retrieval before calling
         *  the underlying LLM. */
        val contextProvider: String,
        val appmapMode: String,
        val seeds: Int,
        val startedAt: Instant,
        @Volatile var endedAt: Instant? = null,
        @Volatile var phase: String = "queued",
        @Volatile var currentSeed: Int = 0,
        @Volatile var status: Status = Status.QUEUED,
        @Volatile var stats: RunStats = RunStats(),
        @Volatile var seedResults: List<SeedResult> = emptyList(),
        /** Per-seed audit detail (prompts, raw LLM response, extracted
         *  patch, verification cmd + stdout, exit code). Filled in
         *  parallel with seedResults; survive for the lifetime of the
         *  in-memory run (lost on JVM restart, like seedResults). */
        @Volatile var seedAudits: List<SeedAudit> = emptyList(),
        /** True once at least one seed got a real bridge response back.
         *  Drives the result-detail banner: green "real LLM" vs amber
         *  "synthetic" so operators can tell the modes apart. */
        @Volatile var usedRealLlm: Boolean = false,
        /** True once at least one seed went through RealBenchmarkExecutor
         *  end-to-end (patch extracted + applied + tests run). Distinct
         *  from `usedRealLlm` because the bridge can succeed but the
         *  scoring path can still degrade to synthetic when the bug
         *  branch isn't seeded. */
        @Volatile var usedRealScoring: Boolean = false
    ) {
        // Keep the log structure thread-safe so the worker thread and
        // the HTTP poll handler can both touch it without locking.
        val logEntries: MutableList<LogEntry> = CopyOnWriteArrayList()

        val durationMs: Long get() =
            ((endedAt ?: Instant.now()).toEpochMilli() - startedAt.toEpochMilli())

        /** Wall time the LLM (or its simulator) was busy producing the
         *  patch — summed across every seed completed so far. The runs
         *  table shows this side-by-side with the overall durationMs so
         *  the operator can tell "fast model + slow tests" runs apart
         *  from "slow model + fast tests" ones. */
        val totalLlmMs: Long get() = seedResults.sumOf { it.solveMs }
        /** Wall time spent applying patches + running gradle for every
         *  completed seed. Roughly durationMs minus totalLlmMs minus
         *  per-run setup (prepare / discover-tests / collect-traces). */
        val totalExecutionMs: Long get() = seedResults.sumOf { it.executionMs }

        /** 0..100 progress estimate based on the phase order. */
        val percent: Int get() = when (phase) {
            "queued" -> 0
            "prepare" -> 5
            "discover-tests" -> 12
            "collect-traces" -> 25
            "prime-context" -> 40
            "complete" -> 100
            else -> {
                // solve-seed-N / score-seed-N
                if (seeds <= 0) 50
                else {
                    val perSeed = 50 / seeds   // 50% budget for the per-seed phases
                    val seedFrac = currentSeed.coerceAtLeast(0)
                    val withinSeed = if (phase.startsWith("solve")) 0 else perSeed / 2
                    40 + (seedFrac - 1).coerceAtLeast(0) * perSeed + withinSeed
                }
            }
        }.coerceIn(0, 100)
    }

    private val runs = ConcurrentHashMap<String, BenchmarkRun>()
    /**
     * Multi-threaded executor — multiple benchmark runs proceed in
     * parallel for everything EXCEPT the LLM call itself. The bridge
     * call is the only step that must be serialized (Copilot /
     * Corp-OpenAI rate-limit on concurrent requests); gradle build,
     * patch apply, AppMap trace overlay, and prompt assembly are all
     * happy to run concurrently and benefit from the parallelism.
     * The LLM-only serialization was previously enforced by a
     * ReentrantLock pinned at concurrency=1; replaced with the shared
     * [AdaptiveThrottler] bean so concurrency adaptively scales with
     * upstream rate-limit signals (HTTP 429 / quota errors halve the
     * cap + open a 60s cooldown; sustained success grows it back up
     * to a max of 4).
     */
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "bench-run").apply { isDaemon = true }
    }
    /** Heuristic match for rate-limit-shaped bridge responses. Same
     *  patterns as usage.ts's isQuotaExhaustionError on the VSIX side --
     *  we keep them in lockstep so a 429 downstream produces the same
     *  throttler signal whether it surfaces via the bridge return code
     *  or via the upstream Copilot error message. */
    private fun looksLikeRateLimit(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.lowercase()
        return t.contains("rate limit") || t.contains("rate_limit") ||
            t.contains("rate-limited") || t.contains("ratelimited") ||
            (t.contains("quota") && t.contains("exceed")) ||
            t.contains("monthly limit") || t.contains("reached your monthly") ||
            t.contains("429") || t.contains("too many requests")
    }
    /**
     * Single-threaded scheduler used for the per-run watchdog that
     * detects "stuck in QUEUED" runs. Daemon thread so it doesn't
     * block JVM shutdown.
     */
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "bench-run-watchdog").apply { isDaemon = true }
    }

    fun start(issueId: String, issueTitle: String, provider: String, modelId: String,
              modelIdentifier: String,
              contextProvider: String, appmapMode: String, seeds: Int): BenchmarkRun {
        val id = "run-" + UUID.randomUUID().toString().substring(0, 8)
        val run = BenchmarkRun(
            id = id, issueId = issueId, issueTitle = issueTitle,
            provider = provider, modelId = modelId,
            // Falls back to modelId when the caller didn't pass an
            // identifier (defensive — older call sites + tests). The
            // launcher always resolves and passes the vendor name.
            modelIdentifier = modelIdentifier.ifBlank { modelId },
            contextProvider = contextProvider,
            appmapMode = appmapMode, seeds = seeds,
            startedAt = Instant.now()
        )
        runs[id] = run
        // Surface ONE log entry the moment we register the run, so the
        // /results/{id} drill-down has SOMETHING to show right away
        // instead of the empty "Loading…" placeholder. The previous
        // behaviour gave the operator no signal that the run existed
        // until simulate's first line ran -- and if the executor was
        // somehow late picking the task up, the page looked broken.
        entry(run, Category.INFO, "Run queued — waiting for worker thread to pick it up.")
        executor.submit { runCatching { simulate(run) }.onFailure { logRunError(run, it) } }

        // Watchdog: if simulate hasn't transitioned the run out of
        // QUEUED within 15s AND no other run is currently RUNNING,
        // something is wrong (executor backlog, simulate threw before
        // status update, etc.). With the single-threaded executor a
        // queued run legitimately sits in QUEUED for hours when the
        // worker is busy on prior submits — only mark ERRORED if
        // we're in a "no worker is making progress" state.
        watchdog.schedule({
            val current = runs[id]
            if (current != null && current.status == Status.QUEUED) {
                val workerBusy = runs.values.any { it.id != id && it.status == Status.RUNNING }
                if (workerBusy) return@schedule
                current.status = Status.ERRORED
                current.endedAt = Instant.now()
                entry(current, Category.ERROR,
                    "Run never transitioned out of QUEUED within 15s and no other run is " +
                    "active. The simulate worker did not start (executor died, JVM thread " +
                    "limit, or an exception before the first status update). Try again or " +
                    "check bench-webui logs for stack traces from BenchmarkRunService.")
                log.warn("Benchmark run {} stuck in QUEUED >15s with no active worker -- marked ERRORED", id)
            }
        }, 15, java.util.concurrent.TimeUnit.SECONDS)

        log.info("Benchmark run {} queued: {} model={} ctx={} seeds={}",
            id, issueId, modelId, contextProvider, seeds)
        return run
    }

    fun get(id: String): BenchmarkRun? = runs[id]

    fun activeRuns(): List<BenchmarkRun> =
        runs.values.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
            .sortedBy { it.startedAt }

    fun recentRuns(n: Int = 20): List<BenchmarkRun> =
        runs.values.sortedByDescending { it.startedAt }.take(n)

    fun cancel(id: String): Boolean {
        val run = runs[id] ?: return false
        if (run.status == Status.RUNNING || run.status == Status.QUEUED) {
            run.status = Status.CANCELED
            run.endedAt = Instant.now()
            run.phase = "complete"
            entry(run, Category.INFO, "Run canceled by operator")
            return true
        }
        return false
    }

    /**
     * Bulk-delete completed runs from the in-memory store. Refuses to
     * touch any run that's still QUEUED or RUNNING — those need to be
     * cancelled first (or wait to finish), since deleting them would
     * leak the worker thread + bridge call. Returns a (deleted, skipped,
     * missing) breakdown so the dashboard can surface a precise toast
     * after the operation.
     */
    data class DeleteSummary(val deleted: Int, val skippedActive: Int, val missing: Int)
    fun deleteRuns(ids: Collection<String>): DeleteSummary {
        var deleted = 0; var skipped = 0; var missing = 0
        for (id in ids) {
            val r = runs[id]
            when {
                r == null -> missing++
                r.status == Status.RUNNING || r.status == Status.QUEUED -> skipped++
                else -> { runs.remove(id); deleted++ }
            }
        }
        return DeleteSummary(deleted, skipped, missing)
    }

    // ------------------------------------------------------------------
    // Worker — phased simulation that mirrors the real harness shape
    // ------------------------------------------------------------------

    private fun simulate(run: BenchmarkRun) {
        run.status = Status.RUNNING
        entry(run, Category.PHASE, "Run started — issue=${run.issueId}, model=${run.modelId}, seeds=${run.seeds}")

        // Phase 1: prepare worktree
        phase(run, "prepare", "Checking out break commit for ${run.issueId}…")
        sleep(700)
        entry(run, Category.INFO, "Worktree at /tmp/ai-bench-runs/${run.id}/banking-app/")
        entry(run, Category.INFO, "Validated build environment (JDK 25, Gradle 9.4)")

        // Phase 2: discover failing tests
        phase(run, "discover-tests", "Discovering failing tests…")
        sleep(800)
        val failingTests = sampleFailingTests(run.issueId)
        entry(run, Category.TEST, "Found ${failingTests.size} failing test(s):")
        failingTests.forEach { entry(run, Category.TEST, "  ✗ $it") }

        // Phase 3: AppMap trace collection. Delegated to AppMapTraceManager
        // which caches by (banking-app SHA, mode) and shares trace
        // artifacts across benchmarks — so launching {oracle, navie} ×
        // {ON_RECOMMENDED} only generates the recommended trace set
        // once. Concurrent benchmarks waiting on the same key block on
        // the manager's lock until the first finishes recording.
        var tracesRecorded = 0
        // Hoisted out of the phase block so each seed's audit can reference
        // the inventory (cache key, paths, generated/reused, synthetic).
        var traceInv: AppMapTraceManager.TraceInventory? = null
        // appmapMode used to be a tri-state (OFF / ON_RECOMMENDED / ON_ALL)
        // where the *mode* picked which traces to bundle. Now it's a
        // simple "ON" gate ("collapsedMode == ON" below); the actual
        // selection of which traces to ship is dispatched at prompt-
        // build time by ContextProvider.selectTracesForBug -- Navie
        // ctx uses Navie's own picks, every other ctx uses BM25 over
        // the available traces. ON_RECOMMENDED + ON_ALL incoming
        // values are folded to ON for compatibility with older runs.
        val collapsedMode =
            if (run.appmapMode.equals("OFF", ignoreCase = true)) "OFF" else "ON"
        if (collapsedMode == "ON") {
            phase(run, "collect-traces",
                "Ensuring AppMap traces (mode=${run.appmapMode}) — checking shared cache…")
            val bug = bugCatalog.getBug(run.issueId)
            val inv = traceManager.ensureTracesExist(collapsedMode, bug) { msg ->
                entry(run, Category.TRACE, msg)
            }
            traceInv = inv
            tracesRecorded = inv.count()
            run.stats = run.stats.copy(tracesRecorded = tracesRecorded)
            if (inv.generated) {
                entry(run, Category.TRACE,
                    "Generated $tracesRecorded trace(s) at ${inv.cacheDir} — cached for sibling runs.")
            } else {
                entry(run, Category.TRACE,
                    "Reused $tracesRecorded existing trace(s) from cache @ sha=${inv.sha} (no recording cost).")
            }
            if (inv.synthetic) {
                entry(run, Category.INFO,
                    "Note: traces are synthetic stubs (Layer B). Real `gradle test --appmap` " +
                    "recording lands in a follow-up; cache + sharing semantics are real.")
            }
        }

        // Phase 4: prime context
        phase(run, "prime-context", "Building solver prompt…")
        sleep(450)
        val isNavie = run.contextProvider == "appmap-navie"
        // Navie's context engine is much more selective — it does its
        // own AppMap-driven retrieval and tends to ship a smaller, more
        // focused source-file slice than the harness's default packer.
        val sourceFiles = if (isNavie) (4..8).random() else (8..14).random()
        // tracesSubmitted reflects what the prompt actually carries.
        // tracesRecorded is the cache size produced by TraceManager;
        // ON_RECOMMENDED pre-trims at the cache level (3 traces for the
        // hidden test + neighbors), ON_ALL hands the LLM the full set.
        // Navie's own retrieval narrows the wider set down further but
        // the count we report is "what Navie had to choose from."
        val tracesSubmitted = when {
            run.appmapMode.equals("OFF", ignoreCase = true) -> 0
            isNavie -> tracesRecorded
            // ON / ON_ALL / unknown-but-not-OFF -> ContextProvider's
            // BM25 selector picks top 5 from the available pool, so
            // that's what actually lands in the prompt. The prior
            // ON_RECOMMENDED behavior (cap at 3) is preserved as a
            // legacy alias.
            run.appmapMode.equals("ON_RECOMMENDED", ignoreCase = true) ->
                tracesRecorded.coerceAtMost(3)
            tracesRecorded > 0 -> tracesRecorded.coerceAtMost(5)
            else -> 0
        }
        val basePrompt = 6000 + sourceFiles * 600
        val tracePrompt = tracesSubmitted * (3000..5500).random()
        val totalPromptTokens = basePrompt + tracePrompt
        if (isNavie) {
            entry(run, Category.CONTEXT, "Navie: invoking `appmap navie` CLI against bridge OpenAI endpoint (http://localhost:11434/v1)")
            entry(run, Category.CONTEXT, "Navie: vector-searching ${tracesRecorded} AppMap(s) for code paths relevant to failing tests")
            sleep(220)
            entry(run, Category.CONTEXT, "Navie: selected $sourceFiles source file(s) and $tracesSubmitted trace excerpt(s) as relevant context")
        }
        entry(run, Category.CONTEXT, "Source files in prompt: $sourceFiles (≈$basePrompt tokens)")
        if (tracesSubmitted > 0) {
            entry(run, Category.CONTEXT, "AppMap traces in prompt: $tracesSubmitted of $tracesRecorded recorded (≈$tracePrompt tokens)")
        } else if (tracesRecorded > 0) {
            entry(run, Category.CONTEXT, "AppMap traces recorded but NOT submitted (mode=OFF)")
        }
        entry(run, Category.CONTEXT, "Total prompt size: ≈$totalPromptTokens tokens")
        run.stats = run.stats.copy(
            tracesSubmitted = tracesSubmitted,
            sourceFilesSubmitted = sourceFiles,
            totalPromptTokens = totalPromptTokens
        )

        // Phase 5: per-seed solve + score loop
        var totalCompletion = 0
        val seedResults = mutableListOf<SeedResult>()
        // Detect whether the Copilot bridge's OpenAI shim is actually
        // reachable. If yes, this run becomes "real LLM, synthetic
        // scoring" -- the per-seed call goes to 127.0.0.1:11434 and
        // captures real prompt/completion token counts. If no, fall
        // back to the original simulator's randomized counts so demos
        // without a bridge installed still produce a complete run shape.
        val bridgeLive = isCopilotBridgeReachable()
        if (bridgeLive) {
            entry(run, Category.INFO, "Copilot bridge reachable on 127.0.0.1:11434 — making real LLM calls (token counts authoritative).")
        } else {
            entry(run, Category.INFO, "Copilot bridge NOT reachable on 127.0.0.1:11434 — using simulated token counts. Start the VSCode Copilot bridge to capture real LLM activity.")
        }
        for (seed in 1..run.seeds) {
            run.currentSeed = seed

            phase(run, "solve-seed-$seed", "Calling ${run.modelId} (seed $seed of ${run.seeds})…")
            if (isNavie) {
                entry(run, Category.LLM, "Seed $seed → Navie request: appmap navie --explain (routes through bridge → Copilot)")
            } else {
                entry(run, Category.LLM, "Seed $seed → request: ${run.provider} model=${run.modelId}, prompt=${totalPromptTokens} tokens")
            }
            val solveStart = System.currentTimeMillis()
            var llmResponseText: String? = null
            // Audit slots filled in as the seed progresses; consolidated
            // into a SeedAudit record at the bottom of the iteration.
            var auditSystemPrompt: String? = null
            var auditUserPrompt: String? = null
            var auditContext: ContextProvider.Resolved? = null
            val completion: Int = if (bridgeLive) {
                val realResp = realLlmCall(run, seed, totalPromptTokens, traceInv)
                if (realResp != null) {
                    entry(run, Category.LLM,
                        "Seed $seed ← real response: ${realResp.completionTokens} completion tokens " +
                        "in ${realResp.latencyMillis}ms, patch ~${realResp.contentLength} chars")
                    llmResponseText = realResp.content
                    auditSystemPrompt = realResp.systemPrompt
                    auditUserPrompt = realResp.userPrompt
                    auditContext = realResp.resolvedContext
                    run.usedRealLlm = true
                    realResp.completionTokens
                } else {
                    entry(run, Category.LLM, "Seed $seed ← bridge call FAILED, falling back to simulated count for this seed")
                    if (isNavie) (700..2200).random() else (1200..4500).random()
                }
            } else {
                sleep(1400)
                val sim = if (isNavie) (700..2200).random() else (1200..4500).random()
                if (isNavie) {
                    entry(run, Category.LLM, "Seed $seed ← simulated response: $sim completion tokens, patch ~${sim * 4} chars (surgical)")
                } else {
                    entry(run, Category.LLM, "Seed $seed ← simulated response: $sim completion tokens, patch ~${sim * 4} chars")
                }
                sim
            }
            totalCompletion += completion
            val solveMs = System.currentTimeMillis() - solveStart

            phase(run, "score-seed-$seed", "Applying patch + running tests for seed $seed…")
            // Real scoring path: when the bridge gave us actual text,
            // hand it to RealBenchmarkExecutor which extracts the patch,
            // creates a per-seed git worktree on bug/<id>/break, runs
            // gradle :app-bootstrap:test, and scores by exit code. Falls
            // back to the synthetic path when (a) no bridge text, (b) no
            // bug branch seeded, or (c) git/gradle pipeline blew up --
            // each fallback is logged with the specific reason.
            // Real-scoring outcome captured here so the audit record at
            // the bottom of the loop can reach it whether we ran real
            // scoring or fell back to synthetic.
            var realScore: RealBenchmarkExecutor.ScoreResult? = null
            val seedResult: SeedResult = if (llmResponseText != null) {
                val real = realExecutor.scoreSeed(
                    runId = run.id,
                    seedNumber = seed,
                    issueId = run.issueId,
                    llmResponseText = llmResponseText,
                    logFn = { msg -> entry(run, Category.RESULT, msg) }
                )
                realScore = real
                // Outcome PASSED, FAILED_TESTS, FAILED_PATCH_APPLY, and
                // FAILED_NO_PATCH all mean the real path actually ran;
                // only NO_BRANCH and GRADLE_ERROR are fall-through.
                // FAILED_NO_BRANCH is the only outcome where the bug
                // can't be scored at all (the bug branch wasn't seeded
                // -- a setup issue, not a model failure). Everything
                // else (PASSED, FAILED_TESTS, FAILED_PATCH_APPLY,
                // FAILED_NO_PATCH, FAILED_GRADLE_ERROR) is a real
                // attempt and should be reported as a real fail rather
                // than masked by a Math.random() synthetic PASS --
                // earlier logic flipped FAILED_GRADLE_ERROR through a
                // 55%-chance synthetic PASS, which produced false-
                // positive PASSED runs in the dashboard despite gradle
                // exiting non-zero.
                val ranRealScoring = real.outcome != RealBenchmarkExecutor.Outcome.FAILED_NO_BRANCH
                if (ranRealScoring) run.usedRealScoring = true
                if (real.outcome == RealBenchmarkExecutor.Outcome.FAILED_NO_BRANCH) {
                    entry(run, Category.INFO,
                        "Seed $seed: real scoring not possible -> ${real.message}. " +
                        "Falling back to synthetic outcome (bug branch not seeded).")
                    val total = failingTests.size + (3..6).random()
                    val passed = if (Math.random() > 0.45) total
                        else (failingTests.size - (1..failingTests.size).random()).coerceAtLeast(0) + (3..5).random()
                    val ok = passed >= total
                    SeedResult(seed, ok, passed, total,
                        durationMs = solveMs + real.durationMs,
                        promptTokens = totalPromptTokens, completionTokens = completion,
                        solveMs = solveMs, executionMs = real.durationMs)
                } else {
                    SeedResult(seed,
                        passed = real.outcome == RealBenchmarkExecutor.Outcome.PASSED,
                        testsPassed = real.testsPassed,
                        testsTotal = real.testsTotal.coerceAtLeast(real.testsPassed),
                        durationMs = solveMs + real.durationMs,
                        promptTokens = totalPromptTokens, completionTokens = completion,
                        solveMs = solveMs, executionMs = real.durationMs)
                }
            } else {
                sleep(900)
                val total = failingTests.size + (3..6).random()
                val passed = if (Math.random() > 0.45) total
                             else (failingTests.size - (1..failingTests.size).random()).coerceAtLeast(0) + (3..5).random()
                val ok = passed >= total
                SeedResult(seed, ok, passed, total,
                    durationMs = solveMs + 900L, promptTokens = totalPromptTokens,
                    completionTokens = completion,
                    solveMs = solveMs, executionMs = 900L)
            }
            seedResults += seedResult
            run.seedResults = seedResults.toList()
            // Stash everything we know about this seed for the per-seed
            // audit drilldown. Truncating happens on the SCORE side
            // (testStdoutTail capped to 16KB) and within the LLM-
            // response capture; nothing else gets serialised over HTTP.
            val audit = SeedAudit(
                seed = seed,
                systemPrompt = auditSystemPrompt,
                userPrompt = auditUserPrompt,
                llmRawResponse = llmResponseText,
                extractedPatch = realScore?.extractedPatch,
                patchApplied = realScore?.patchApplied ?: false,
                verificationCommand = realScore?.verificationCommand,
                testStdoutTail = realScore?.testStdoutTail,
                testExitCode = realScore?.testExitCode,
                outcome = realScore?.outcome?.name ?: if (llmResponseText == null) "SYNTHETIC" else null,
                outcomeMessage = realScore?.message,
                contextProvider = run.contextProvider,
                effectiveContextProvider = auditContext?.effectiveProvider,
                contextRationale = auditContext?.rationale,
                contextFellBack = auditContext?.fellBack ?: false,
                contextFiles = auditContext?.files?.map {
                    AuditContextFile(it.path, it.ref, it.content?.length ?: 0, it.score)
                } ?: emptyList(),
                // Trace audit: pulls everything from the TraceManager's
                // inventory so the audit page can deep-link to the
                // on-disk trace files. submittedToPrompt is `true` for
                // the first `tracesSubmitted` files (the same dropoff
                // the prompt assembler uses).
                traceMode = run.appmapMode,
                traceCacheSha = traceInv?.sha,
                traceCacheDir = traceInv?.cacheDir?.absolutePath,
                traceCacheGenerated = traceInv?.generated ?: false,
                traceCacheSynthetic = traceInv?.synthetic ?: false,
                tracesAvailable = traceInv?.count() ?: 0,
                tracesSubmitted = tracesSubmitted,
                traceFiles = traceInv?.tracePaths?.mapIndexed { i, f ->
                    AuditTraceFile(
                        name = f.nameWithoutExtension,
                        path = f.absolutePath,
                        sizeBytes = f.length(),
                        submittedToPrompt = i < tracesSubmitted,
                        // Same base64-url scheme AppMapService.encodeId
                        // uses for banking-app traces — keeping it
                        // identical means /demo/appmap/view's existing
                        // controller can decode + load directly.
                        viewerId = java.util.Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(f.absolutePath.toByteArray(Charsets.UTF_8))
                    )
                } ?: emptyList()
            )
            run.seedAudits = (run.seedAudits + audit).toList()
            entry(run, Category.RESULT,
                "Seed $seed: ${if (seedResult.passed) "✓ PASS" else "✗ FAIL"} " +
                "(${seedResult.testsPassed}/${seedResult.testsTotal} tests, ${seedResult.durationMs}ms)")
        }

        // Phase 6: report
        phase(run, "report", "Summarizing pass@${run.seeds}…")
        sleep(300)
        val passCount = seedResults.count { it.passed }
        // Look up by `id` (registry-side, e.g. "copilot-gpt-4-1") rather
        // than `modelIdentifier` (vendor-side, e.g. "gpt-4.1"). run.modelId
        // is the registry id chosen on the launcher form, so it matches
        // ModelPrice.id directly. The earlier `it.modelIdentifier ==
        // run.modelId` comparison would never match for any prefixed
        // entry, leaving every Copilot run with $0 estimated cost.
        val priced = priceCatalog.catalog.firstOrNull { it.id == run.modelId }
        val pricedPrompt = priced?.costPer1kPrompt ?: 0.0
        val pricedCompletion = priced?.costPer1kCompletion ?: 0.0
        val cost = (totalPromptTokens * run.seeds / 1000.0) * pricedPrompt +
                   (totalCompletion / 1000.0) * pricedCompletion
        run.stats = run.stats.copy(
            totalCompletionTokens = totalCompletion,
            estimatedCostUsd = cost
        )
        run.endedAt = Instant.now()
        run.phase = "complete"
        // Strict PASSED: ALL seeds must pass. Earlier criterion was
        // `passCount > 0`, which marked a 2/3 run as PASSED in the
        // dashboard while the per-seed table showed two passes and
        // one failure -- visually inconsistent. PASSED now means
        // "the model resolved this issue every time"; partial
        // outcomes are FAILED. (Adding a PARTIAL status would split
        // these correctly but threads through the dashboard,
        // detail page, and stats query in too many places for the
        // same fix; keep the binary distinction strict for now.)
        run.status = if (passCount == run.seeds) Status.PASSED else Status.FAILED
        entry(run, Category.RESULT,
            "Final: $passCount/${run.seeds} seeds passed → " +
            "pass@${run.seeds}=${if (passCount == run.seeds) "PASS" else "FAIL"}, " +
            "estimated cost \$${"%.6f".format(cost)}")
    }

    private fun sampleFailingTests(issueId: String): List<String> {
        // Synthetic deterministic-looking sample so log entries reference
        // recognizable banking-app tests rather than placeholders.
        val pool = listOf(
            "PaymentLifecycleManagerTest.cutoff_window_off_by_one",
            "AchBatchBuilderTest.same_day_late_window",
            "JournalEntryValidatorTest.backdate_warning_in_adjustment_context",
            "OverdraftProtectionServiceTest.linked_transfer_cascade",
            "CardAuthorizationEngineTest.first_use_step_up",
            "TransactionRiskScorerTest.high_risk_jurisdiction"
        )
        // Pick 1-3 stable-by-issueId tests.
        val seed = issueId.hashCode().toLong()
        val rng = java.util.Random(seed)
        val n = 1 + rng.nextInt(3)
        return pool.shuffled(rng).take(n)
    }

    private fun phase(run: BenchmarkRun, name: String, msg: String) {
        run.phase = name
        entry(run, Category.PHASE, msg)
    }

    private fun entry(run: BenchmarkRun, cat: Category, msg: String) {
        run.logEntries.add(LogEntry(Instant.now(), cat, msg))
        // Cap to last 500 entries per run to keep memory bounded.
        if (run.logEntries.size > 500) run.logEntries.removeAt(0)
    }

    private fun logRunError(run: BenchmarkRun, t: Throwable) {
        run.status = Status.ERRORED
        run.endedAt = Instant.now()
        entry(run, Category.ERROR, "Run failed: ${t.javaClass.simpleName}: ${t.message}")
        log.error("Benchmark run {} threw", run.id, t)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { /* canceled */ }
    }

    /**
     * Compose the (system, user) prompt pair sent to the bridge for
     * each seed. The system prompt locks in the diff-only response
     * format; the user prompt embeds the bug's natural-language
     * problemStatement plus every file from filesTouched read off the
     * break branch -- this is what makes the patch-extraction step
     * usable.
     *
     * <p>Returns a wiring-only prompt (no source) when the bug isn't
     * in the catalog OR the break-branch source can't be read; the
     * caller's path still generates a valid bridge request, the seed
     * just falls into FAILED_NO_PATCH downstream.
     */
    /** Bundle returned by buildSolverPrompt so the seed loop can stash
     *  context-provider details on the SeedAudit (for the audit page). */
    private data class SolverPrompt(
        val systemPrompt: String,
        val userPrompt: String,
        val resolvedContext: ContextProvider.Resolved
    )

    private fun buildSolverPrompt(
        run: BenchmarkRun,
        seed: Int,
        traceInv: AppMapTraceManager.TraceInventory? = null
    ): SolverPrompt {
        val bug = bugCatalog.getBug(run.issueId)
        // Resolve the context provider regardless of bug-presence; it
        // returns a graceful 'no source' Resolved when the bug isn't in
        // the catalog so the prompt still has SOMETHING to send.
        val ctx = contextProvider.resolve(run.issueId, run.contextProvider)

        if (bug == null) {
            val sys = "You are a senior Java engineer triaging a banking-app bug. " +
                "Suggest a focused approach to investigate. Keep it under 6 bullet points; " +
                "no preamble, no apologies, just actionable steps."
            val usr = "Issue: ${run.issueId} -- ${run.issueTitle}.\n" +
                "Provider: ${run.provider}, model: ${run.modelId}, " +
                "context provider: ${run.contextProvider}, seed: $seed.\n" +
                "(Bug metadata not loaded -- diff response format won't extract.)"
            return SolverPrompt(sys, usr, ctx)
        }

        val systemPrompt = """
            You are a senior Java engineer fixing a single bug in a banking
            application. Respond with ONLY a unified diff in a fenced ```diff
            block -- no prose, no commentary, no explanation. The diff must:
              * apply cleanly with `git apply` against the file(s) shown below
              * use the exact path shown in the diff header (`--- a/<path>`
                and `+++ b/<path>`), NOT a placeholder. Paths are MULTI-MODULE
                (e.g. `shared-domain/src/main/java/com/omnibank/...`) -- do
                NOT shorten to single-module form like `src/main/java/...`
              * include a NUMERIC hunk header in the form
                `@@ -<oldStart>,<oldLines> +<newStart>,<newLines> @@` --
                `@@ ... @@` is NOT accepted by git apply and will be rejected
              * touch ONLY the file(s) shown -- do not invent or rename
              * be minimal -- change only what's needed to fix the bug,
                ideally a single hunk of a few lines
              * make a REAL change -- the `+` line MUST differ from the `-`
                line. A no-op patch (identical `-` and `+` lines) will be
                treated as a failed solution
              * preserve existing imports, package declarations, and formatting
            If you cannot solve the bug from the information given, still
            respond with a diff that takes a best-effort approach. A chat
            reply that isn't a diff will be rejected.

            FORMAT TEMPLATE (the placeholders show shape only — fill them
            with the real path / line numbers / code from the source files
            shown above; do NOT copy these placeholder identifiers):
            ```diff
            --- a/<exact path from a File: header above>
            +++ b/<same path>
            @@ -<startLine>,<oldCount> +<startLine>,<newCount> @@
             <unchanged context line copied verbatim from the file>
            -<line you are removing, copied verbatim from the file>
            +<line you are inserting, must differ from the removed line>
             <unchanged context line copied verbatim from the file>
            ```
        """.trimIndent()
        val sources = if (ctx.files.isEmpty()) {
            "[no source code provided -- context provider '${ctx.effectiveProvider}' " +
                "shipped the problem statement only]"
        } else {
            ctx.files.joinToString("\n\n") { f ->
                if (f.content == null) {
                    "[unable to read ${f.path} at ${f.ref ?: "?"}: ${f.error ?: "unknown error"}]"
                } else {
                    buildString {
                        append("File: ").append(f.path)
                        f.score?.let { append("  (BM25 score: %.3f)".format(it)) }
                        append("\n```java\n")
                        append(f.content)
                        if (!f.content.endsWith("\n")) append('\n')
                        append("```")
                    }
                }
            }
        }
        val hintsBlock = if (bug.hints.isNotEmpty())
            "\n\nHints:\n" + bug.hints.joinToString("\n") { "- $it" } else ""

        // Embed AppMap traces directly in the user message so the LLM
        // actually has runtime information to look at. Without this the
        // earlier code only inflated tracesSubmitted as a token-count
        // estimate but the prompt body was traces-free — the audit said
        // "3 traces shipped" and the model never saw them. Each trace
        // is capped at TRACE_BODY_CAP chars so a single huge AppMap
        // (Layer C real recordings can hit MB) doesn't blow the prompt
        // window; aggregate across all traces is also bounded.
        val tracesBlock = buildTracesBlock(traceInv, run.appmapMode,
            bug, run.contextProvider)

        val userPrompt = """
            Issue ${run.issueId}: ${run.issueTitle}

            Problem statement:
            ${bug.problemStatement}$hintsBlock

            Source code retrieved by the '${ctx.effectiveProvider}' context provider:

            $sources$tracesBlock

            Reply with a unified diff in a ```diff fenced block. Apply minimal
            changes. The diff will be piped directly into `git apply` against
            a worktree at the bug-break commit.
        """.trimIndent()
        return SolverPrompt(systemPrompt, userPrompt, ctx)
    }

    /** Per-trace cap (chars). Layer-B synthetic stubs are ~300 bytes; this
     *  is sized for Layer-C real recordings without breaking the prompt
     *  window. */
    private val TRACE_BODY_CAP = 12_000
    /** Aggregate cap across all traces in the prompt. */
    private val TRACE_AGGREGATE_CAP = 64_000

    private fun buildTracesBlock(
        inv: AppMapTraceManager.TraceInventory?,
        mode: String,
        bug: BugCatalog.BugMetadata?,
        contextProviderId: String
    ): String {
        if (inv == null || inv.tracePaths.isEmpty() ||
            mode.equals("OFF", ignoreCase = true) || bug == null) return ""
        // Trace SELECTION is dispatched by context provider:
        //   - appmap-navie: traces Navie itself referenced (cache HIT)
        //                   or BM25 fallback when cache is empty.
        //   - any other:    BM25 over all available traces against the
        //                   bug's problem statement.
        // The harness used to ship every trace in [inv.tracePaths]
        // capped only by the aggregate-byte budget; the targeted
        // selection here means the LLM's prompt actually has a chance
        // of staying focused on the bug at hand instead of getting
        // lost in 90+ unrelated trace bodies.
        val selection = contextProvider.selectTracesForBug(
            bug = bug,
            contextProvider = contextProviderId,
            availableTraces = inv.tracePaths,
            k = 5
        )
        if (selection.files.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\nAppMap traces (recorded at runtime — call graphs / SQL / HTTP "
            + "captured while running the failing test and neighbors). "
            + "Selection: ").append(selection.method).append(" (")
            .append(selection.rationale).append("); source=")
            .append(if (inv.synthetic) "synthetic stub (Layer B)" else "real recording")
            .append(":\n\n")
        var aggregate = 0
        var includedCount = 0
        for (f in selection.files) {
            val raw = runCatching { f.readText() }.getOrElse { e ->
                "[unable to read trace ${f.name}: ${e.message}]"
            }
            val clipped = if (raw.length > TRACE_BODY_CAP)
                raw.take(TRACE_BODY_CAP) +
                    "\n\n[... truncated ${raw.length - TRACE_BODY_CAP} chars ...]\n"
                else raw
            val piece = "Trace: ${f.nameWithoutExtension}\n```json\n$clipped\n```\n\n"
            if (aggregate + piece.length > TRACE_AGGREGATE_CAP && includedCount > 0) {
                val skipped = selection.files.size - includedCount
                sb.append("[... ${skipped} additional trace(s) omitted to stay under "
                    + "${TRACE_AGGREGATE_CAP} chars; see /results/{run}/seed/{n}/audit "
                    + "for the full list]\n")
                break
            }
            sb.append(piece)
            aggregate += piece.length
            includedCount++
        }
        return sb.toString()
    }

    /**
     * Reachability check for the Copilot bridge's OpenAI shim. One TCP
     * connect with 500ms timeout; called once per run, not per seed.
     */
    private fun isCopilotBridgeReachable(): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", 11434), 500)
            true
        }
    }.getOrDefault(false)

    /** Real LLM call response details captured from the OpenAI-shape JSON. */
    private data class LlmCallResult(
        val promptTokens: Int,
        val completionTokens: Int,
        val latencyMillis: Long,
        val contentLength: Int,
        /** Full assistant message text, JSON-decoded. */
        val content: String,
        /** System message we sent — surfaced on the audit page. */
        val systemPrompt: String,
        /** User message we sent — surfaced on the audit page. */
        val userPrompt: String,
        /** Context-provider resolution snapshot for this seed. */
        val resolvedContext: ContextProvider.Resolved? = null
    )

    /**
     * One real chat-completion request to the Copilot bridge, scoped
     * with the run id so the bridge's activity panel groups requests
     * under this BenchmarkRun. The prompt now ships the bug's
     * problemStatement + the buggy source files (read from the bug's
     * break branch via BugCatalog) and explicitly asks for a unified
     * diff so RealBenchmarkExecutor.extractPatch can pick it up. The
     * earlier "suggest an approach" prompt was useful for wiring
     * verification but always failed FAILED_NO_PATCH; this version is
     * the actual benchmark.
     *
     * <p>Falls back to the wiring-only prompt when bug metadata isn't
     * available (bugs/ dir missing or break-branch source unreadable)
     * so the run still produces a token-count without crashing.
     */
    private fun realLlmCall(
        run: BenchmarkRun,
        seed: Int,
        contextTokens: Int,
        traceInv: AppMapTraceManager.TraceInventory? = null
    ): LlmCallResult? {
        val solver = buildSolverPrompt(run, seed, traceInv)
        val systemPrompt = solver.systemPrompt
        val userPrompt = solver.userPrompt
        // Surface what the context provider produced in the run's log so
        // the operator can confirm "I picked BM25, here are the 5 files
        // it ranked." Files-list goes to the audit page on the SeedAudit
        // record below.
        entry(run, Category.CONTEXT,
            "Seed $seed: context=${solver.resolvedContext.effectiveProvider} -- " +
            solver.resolvedContext.rationale +
            (if (solver.resolvedContext.fellBack) " [fallback]" else ""))
        for (f in solver.resolvedContext.files) {
            val sc = f.score?.let { " score=%.3f".format(it) } ?: ""
            entry(run, Category.CONTEXT,
                "Seed $seed:   file ${f.path}@${f.ref ?: "?"}$sc " +
                "(${f.content?.length ?: 0} chars)")
        }
        // Send the vendor-side identifier (e.g. "gpt-4.1"), NOT the
        // registry-prefixed id. Copilot's bridge doesn't recognise the
        // "copilot-" prefix and silently falls back to whatever the
        // user's currently-active default model is when handed an
        // unknown id -- so passing run.modelId here would route every
        // request to (typically) Claude Sonnet 4.6 regardless of what
        // the operator picked on the launcher. The audit page surfaces
        // both fields so the operator can spot-check the routing.
        val body = """{"model":${jsonStr(run.modelIdentifier)},"temperature":0.2,"messages":[""" +
            """{"role":"system","content":${jsonStr(systemPrompt)}},""" +
            """{"role":"user","content":${jsonStr(userPrompt)}}""" +
            """]}"""
        val start = System.currentTimeMillis()
        return try {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:11434/v1/chat/completions"))
                // Copilot bridge round-trips can take 60-90s on the
                // first call (cold model) and 30-60s steady-state;
                // 180s gives headroom without blocking a stuck call
                // forever. The HTTP client's connectTimeout is still
                // 3s so a missing bridge fails fast.
                .timeout(java.time.Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                // Scope this request to the BenchmarkRun so the bridge's
                // /llm activity panel can filter by runId. The harness
                // module's CopilotSocketClient propagates this same id;
                // we mirror it on the HTTP shim path here.
                .header("X-Run-Id", run.id)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build()
            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build()
            // Gate the bridge call on the AdaptiveThrottler so multiple
            // benchmarks scale up / down with upstream rate-limit signals
            // instead of being permanently capped at 1 by the old
            // ReentrantLock. The throttler starts at cap=1 and grows on
            // sustained success; on a 429 / quota error it halves the
            // cap and opens a 60s cooldown. This is the same code path
            // the navie precompute submitter uses (when it had an LLM
            // dependency) -- one shared throttler bean per JVM.
            val capBefore = throttler.status().currentCap
            entry(run, Category.LLM,
                "Seed $seed: acquiring throttler permit (cap=${capBefore}, " +
                "${throttler.status().recentRateLimitCount} recent rate-limits)")
            throttler.acquire()
            val resp: java.net.http.HttpResponse<String>?
            val ms: Long
            try {
                // Send asynchronously so we can emit "still waiting…"
                // progress entries every 20s. Without this, the log tail
                // looks frozen for up to 3 minutes during slow Copilot
                // responses -- the operator can't tell "stuck" from
                // "working." Any polling client (result-detail's 1.5s
                // tick) sees the periodic entries and the latest elapsed
                // time, so the page never looks dead.
                val future = client.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                var pending: java.net.http.HttpResponse<String>? = null
                while (pending == null) {
                    pending = try {
                        future.get(20, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (te: java.util.concurrent.TimeoutException) {
                        val elapsed = (System.currentTimeMillis() - start) / 1000
                        entry(run, Category.LLM,
                            "Seed $seed … still waiting on bridge response (${elapsed}s elapsed)")
                        null
                    }
                }
                resp = pending
                ms = System.currentTimeMillis() - start
            } finally {
                throttler.release()
            }
            if (resp.statusCode() !in 200..299) {
                // Detect rate-limit signal in the bridge response so the
                // throttler shrinks the permit cap before the next call.
                // Pattern matches isQuotaExhaustionError -- 429, "rate
                // limit", "quota exceeded", etc.
                val body = resp.body()
                if (resp.statusCode() == 429 || looksLikeRateLimit(body)) {
                    throttler.reportRateLimit("HTTP ${resp.statusCode()}: ${body.take(160)}")
                    entry(run, Category.LLM,
                        "Seed $seed: rate-limit signal -- throttler cap " +
                        "${throttler.status().currentCap} (cooldown 60s)")
                }
                entry(run, Category.ERROR,
                    "Bridge returned HTTP ${resp.statusCode()}: ${body.take(200)}")
                return null
            }
            val payload = resp.body()
            // Parse with Jackson rather than regex. Earlier impl used
            // Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"") to
            // pull the assistant message, which exhibits catastrophic
            // backtracking on long responses with embedded escapes
            // (gpt-5-2's verbose patches blew the JVM stack with
            // StackOverflowError after 144s of regex recursion). Jackson
            // gives correct, linear-time JSON parsing AND already
            // unescapes string contents — no manual replace chain.
            val tree = jsonMapper.readTree(payload)
            val usage = tree.path("usage")
            val promptTokens = usage.path("prompt_tokens").asInt(contextTokens)
            val completionTokens = usage.path("completion_tokens").asInt(0)
            val content = tree.path("choices").firstOrNull()
                ?.path("message")?.path("content")?.asText("") ?: ""
            LlmCallResult(promptTokens, completionTokens, ms, content.length, content,
                systemPrompt = systemPrompt, userPrompt = userPrompt,
                resolvedContext = solver.resolvedContext)
        } catch (e: Exception) {
            // Unwrap CompletableFuture's wrapping if present.
            val root = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
            val msg = when (root) {
                is java.net.http.HttpTimeoutException ->
                    "Bridge accepted the request on 11434 but did NOT return a chat-completion " +
                    "response within 180s. Either Copilot itself is rate-limited / unreachable " +
                    "from this VSCode session, or the VSIX's internal worker is stuck. " +
                    "Try: (1) check VSCode's Copilot status icon for a sign-in/quota issue; " +
                    "(2) Developer: Reload Window in the VSCode hosting the bridge; " +
                    "(3) re-run the benchmark."
                is java.net.ConnectException ->
                    "Bridge HTTP shim isn't listening on 11434 (connection refused). " +
                    "Start the VSCode Copilot bridge extension."
                else ->
                    "Bridge call threw ${root.javaClass.simpleName}: ${root.message ?: ""}"
            }
            entry(run, Category.ERROR, msg)
            null
        }
    }

    /** Encode a string as a JSON string literal. */
    private fun jsonStr(s: String): String {
        val sb = StringBuilder().append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
