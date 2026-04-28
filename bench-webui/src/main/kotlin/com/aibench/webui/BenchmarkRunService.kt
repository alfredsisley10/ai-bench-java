package com.aibench.webui

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
    private val bugCatalog: BugCatalog
) {

    private val log = LoggerFactory.getLogger(BenchmarkRunService::class.java)

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
        val durationMs: Long,
        val promptTokens: Int,
        val completionTokens: Int
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
        val outcomeMessage: String? = null
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
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "bench-run").apply { isDaemon = true }
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
        // QUEUED within 15s, something is wrong (executor backlog,
        // simulate threw before status update, etc.). Fail it loudly
        // instead of leaving the operator staring at "Queued forever".
        watchdog.schedule({
            val current = runs[id]
            if (current != null && current.status == Status.QUEUED) {
                current.status = Status.ERRORED
                current.endedAt = Instant.now()
                entry(current, Category.ERROR,
                    "Run never transitioned out of QUEUED within 15s. The simulate worker " +
                    "did not start (executor backlog, JVM thread limit, or an exception " +
                    "before the first status update). Try again or check bench-webui logs " +
                    "for stack traces from BenchmarkRunService.")
                log.warn("Benchmark run {} stuck in QUEUED >15s -- marked ERRORED", id)
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

        // Phase 3: AppMap trace collection
        var tracesRecorded = 0
        if (run.appmapMode != "OFF") {
            phase(run, "collect-traces", "Recording AppMap traces from failing tests…")
            for (t in failingTests) {
                sleep(450)
                val events = (300..2400).random()
                val sql = (0..18).random()
                val http = (0..6).random()
                entry(run, Category.TRACE,
                    "Recorded trace for $t — $events events / $sql SQL / $http HTTP")
                tracesRecorded++
                run.stats = run.stats.copy(tracesRecorded = tracesRecorded)
            }
            // ON_ALL also pulls passing-test traces for surrounding context.
            if (run.appmapMode == "ON_ALL") {
                val extras = listOf("ChartOfAccountsTest.gl_hierarchy_loads",
                                    "PostingServiceImplTest.balanced_entry_posts")
                for (t in extras) {
                    sleep(280)
                    val events = (180..900).random()
                    entry(run, Category.TRACE, "Recorded trace for $t — $events events (passing test, context only)")
                    tracesRecorded++
                    run.stats = run.stats.copy(tracesRecorded = tracesRecorded)
                }
            }
            sleep(250)
        }

        // Phase 4: prime context
        phase(run, "prime-context", "Building solver prompt…")
        sleep(450)
        val isNavie = run.contextProvider == "appmap-navie"
        // Navie's context engine is much more selective — it does its
        // own AppMap-driven retrieval and tends to ship a smaller, more
        // focused source-file slice than the harness's default packer.
        val sourceFiles = if (isNavie) (4..8).random() else (8..14).random()
        val tracesSubmitted = when {
            run.appmapMode == "OFF" -> 0
            // Navie always pulls its own AppMap context regardless of
            // mode (that's the point of the integration), so even
            // mode=OFF surfaces traces here.
            isNavie -> tracesRecorded.coerceAtLeast(1)
            run.appmapMode == "ON_RECOMMENDED" -> tracesRecorded.coerceAtMost(3)
            run.appmapMode == "ON_ALL" -> tracesRecorded
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
            val completion: Int = if (bridgeLive) {
                val realResp = realLlmCall(run, seed, totalPromptTokens)
                if (realResp != null) {
                    entry(run, Category.LLM,
                        "Seed $seed ← real response: ${realResp.completionTokens} completion tokens " +
                        "in ${realResp.latencyMillis}ms, patch ~${realResp.contentLength} chars")
                    llmResponseText = realResp.content
                    auditSystemPrompt = realResp.systemPrompt
                    auditUserPrompt = realResp.userPrompt
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
                val ranRealScoring = real.outcome != RealBenchmarkExecutor.Outcome.FAILED_NO_BRANCH &&
                                     real.outcome != RealBenchmarkExecutor.Outcome.FAILED_GRADLE_ERROR
                if (ranRealScoring) run.usedRealScoring = true
                if (real.outcome == RealBenchmarkExecutor.Outcome.FAILED_NO_BRANCH ||
                    real.outcome == RealBenchmarkExecutor.Outcome.FAILED_GRADLE_ERROR) {
                    entry(run, Category.INFO,
                        "Seed $seed: real scoring degraded → ${real.message}. Falling back to synthetic outcome.")
                    val total = failingTests.size + (3..6).random()
                    val passed = if (Math.random() > 0.45) total
                        else (failingTests.size - (1..failingTests.size).random()).coerceAtLeast(0) + (3..5).random()
                    val ok = passed >= total
                    SeedResult(seed, ok, passed, total,
                        durationMs = solveMs + real.durationMs,
                        promptTokens = totalPromptTokens, completionTokens = completion)
                } else {
                    SeedResult(seed,
                        passed = real.outcome == RealBenchmarkExecutor.Outcome.PASSED,
                        testsPassed = real.testsPassed,
                        testsTotal = real.testsTotal.coerceAtLeast(real.testsPassed),
                        durationMs = solveMs + real.durationMs,
                        promptTokens = totalPromptTokens, completionTokens = completion)
                }
            } else {
                sleep(900)
                val total = failingTests.size + (3..6).random()
                val passed = if (Math.random() > 0.45) total
                             else (failingTests.size - (1..failingTests.size).random()).coerceAtLeast(0) + (3..5).random()
                val ok = passed >= total
                SeedResult(seed, ok, passed, total,
                    durationMs = solveMs + 900L, promptTokens = totalPromptTokens,
                    completionTokens = completion)
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
                outcomeMessage = realScore?.message
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
    private fun buildSolverPrompt(run: BenchmarkRun, seed: Int): Pair<String, String> {
        val bug = bugCatalog.getBug(run.issueId)
        if (bug == null || bug.filesTouched.isEmpty()) {
            val sys = "You are a senior Java engineer triaging a banking-app bug. " +
                "Suggest a focused approach to investigate. Keep it under 6 bullet points; " +
                "no preamble, no apologies, just actionable steps."
            val usr = "Issue: ${run.issueId} -- ${run.issueTitle}.\n" +
                "Provider: ${run.provider}, model: ${run.modelId}, " +
                "context provider: ${run.contextProvider}, seed: $seed.\n" +
                "(Bug metadata not loaded -- diff response format won't extract.)"
            return sys to usr
        }
        val systemPrompt = """
            You are a senior Java engineer fixing a single bug in a banking
            application. Respond with ONLY a unified diff in a fenced ```diff
            block -- no prose, no commentary, no explanation. The diff must:
              * apply cleanly with `git apply` against the file(s) shown below
              * use the exact path shown in the diff header (`--- a/<path>`
                and `+++ b/<path>`), NOT a placeholder
              * touch ONLY the file(s) shown -- do not invent or rename
              * be minimal -- change only what's needed to fix the bug,
                ideally a single hunk of a few lines
              * preserve existing imports, package declarations, and formatting
            If you cannot solve the bug from the information given, still
            respond with a diff that takes a best-effort approach. A chat
            reply that isn't a diff will be rejected.
        """.trimIndent()
        // Read each file at the break commit so the LLM sees exactly what
        // RealBenchmarkExecutor will hand to `git apply`. Truncate is on
        // the BugCatalog side (256KB cap) so what the operator sees on
        // the audit page matches what the LLM saw.
        val sources = bug.filesTouched.joinToString("\n\n") { path ->
            val snap = bugCatalog.readFileAtRef(bug.breakCommit, path)
            if (snap.content == null) {
                "[unable to read $path at ${bug.breakCommit}: ${snap.error ?: "unknown error"}]"
            } else {
                buildString {
                    append("File: ").append(path).append("\n")
                    append("```java\n")
                    append(snap.content)
                    if (!snap.content.endsWith("\n")) append('\n')
                    append("```")
                }
            }
        }
        val hintsBlock = if (bug.hints.isNotEmpty())
            "\n\nHints:\n" + bug.hints.joinToString("\n") { "- $it" } else ""
        val userPrompt = """
            Issue ${run.issueId}: ${run.issueTitle}

            Problem statement:
            ${bug.problemStatement}$hintsBlock

            Source code that may need modification (the file(s) listed in the
            bug's filesTouched, read off the bug-break branch):

            $sources

            Reply with a unified diff in a ```diff fenced block. Apply minimal
            changes. The diff will be piped directly into `git apply` against
            a worktree at the bug-break commit.
        """.trimIndent()
        return systemPrompt to userPrompt
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
        val userPrompt: String
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
    private fun realLlmCall(run: BenchmarkRun, seed: Int, contextTokens: Int): LlmCallResult? {
        val (systemPrompt, userPrompt) = buildSolverPrompt(run, seed)
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
            // Send asynchronously so we can emit "still waiting…"
            // progress entries every 20s. Without this, the log tail
            // looks frozen for up to 3 minutes during slow Copilot
            // responses -- the operator can't tell "stuck" from
            // "working." Any polling client (result-detail's 1.5s
            // tick) sees the periodic entries and the latest elapsed
            // time, so the page never looks dead.
            val future = client.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            var resp: java.net.http.HttpResponse<String>? = null
            while (resp == null) {
                resp = try {
                    future.get(20, java.util.concurrent.TimeUnit.SECONDS)
                } catch (te: java.util.concurrent.TimeoutException) {
                    val elapsed = (System.currentTimeMillis() - start) / 1000
                    entry(run, Category.LLM,
                        "Seed $seed … still waiting on bridge response (${elapsed}s elapsed)")
                    null
                }
            }
            val ms = System.currentTimeMillis() - start
            if (resp.statusCode() !in 200..299) {
                entry(run, Category.ERROR,
                    "Bridge returned HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
                return null
            }
            val payload = resp.body()
            val promptTokens = Regex("\"prompt_tokens\"\\s*:\\s*(\\d+)")
                .find(payload)?.groupValues?.get(1)?.toIntOrNull() ?: contextTokens
            val completionTokens = Regex("\"completion_tokens\"\\s*:\\s*(\\d+)")
                .find(payload)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val rawContent = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(payload)?.groupValues?.get(1) ?: ""
            // Decode the JSON string escapes so downstream code (patch
            // extractor, git apply) sees real newlines and quotes.
            val content = rawContent
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\\\", "\\")
            LlmCallResult(promptTokens, completionTokens, ms, content.length, content,
                systemPrompt = systemPrompt, userPrompt = userPrompt)
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
