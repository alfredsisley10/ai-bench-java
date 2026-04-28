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
    private val priceCatalog: ModelPriceCatalog
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
        val modelId: String,
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
        @Volatile var seedResults: List<SeedResult> = emptyList()
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
              contextProvider: String, appmapMode: String, seeds: Int): BenchmarkRun {
        val id = "run-" + UUID.randomUUID().toString().substring(0, 8)
        val run = BenchmarkRun(
            id = id, issueId = issueId, issueTitle = issueTitle,
            provider = provider, modelId = modelId,
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
            val completion: Int = if (bridgeLive) {
                val realResp = realLlmCall(run, seed, totalPromptTokens)
                if (realResp != null) {
                    entry(run, Category.LLM,
                        "Seed $seed ← real response: ${realResp.completionTokens} completion tokens " +
                        "in ${realResp.latencyMillis}ms, patch ~${realResp.contentLength} chars")
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

            phase(run, "score-seed-$seed", "Applying patch + running tests for seed $seed…")
            sleep(900)
            val total = failingTests.size + (3..6).random()
            val passed = if (Math.random() > 0.45) total
                         else (failingTests.size - (1..failingTests.size).random()).coerceAtLeast(0) + (3..5).random()
            val seedPassed = passed >= total
            val sr = SeedResult(seed, seedPassed, passed, total,
                durationMs = (1400L + 900L), promptTokens = totalPromptTokens,
                completionTokens = completion)
            seedResults += sr
            run.seedResults = seedResults.toList()
            entry(run, if (seedPassed) Category.RESULT else Category.RESULT,
                "Seed $seed: ${if (seedPassed) "✓ PASS" else "✗ FAIL"} ($passed/$total tests)")
        }

        // Phase 6: report
        phase(run, "report", "Summarizing pass@${run.seeds}…")
        sleep(300)
        val passCount = seedResults.count { it.passed }
        val pricedPrompt = priceCatalog.catalog
            .firstOrNull { it.modelIdentifier == run.modelId }
            ?.costPer1kPrompt ?: 0.0
        val pricedCompletion = priceCatalog.catalog
            .firstOrNull { it.modelIdentifier == run.modelId }
            ?.costPer1kCompletion ?: 0.0
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
        val contentLength: Int
    )

    /**
     * One real chat-completion request to the Copilot bridge, scoped
     * with the run id so the bridge's activity panel groups requests
     * under this BenchmarkRun. Prompt is intentionally minimal -- we're
     * exercising the wiring + capturing real token counts, not (yet)
     * trying to actually solve the bug, since that needs the full source
     * tree + patch-apply + test-runner harness which is a larger task.
     */
    private fun realLlmCall(run: BenchmarkRun, seed: Int, contextTokens: Int): LlmCallResult? {
        val systemPrompt =
            "You are a senior Java engineer triaging a banking-app bug. " +
            "Suggest a focused approach to investigate. Keep it under 6 bullet points; " +
            "no preamble, no apologies, just actionable steps."
        val userPrompt = "Issue: ${run.issueId} — ${run.issueTitle}.\n" +
            "Provider: ${run.provider}, model: ${run.modelId}, " +
            "context provider: ${run.contextProvider}, seed: $seed."
        val body = """{"model":${jsonStr(run.modelId)},"temperature":0.2,"messages":[""" +
            """{"role":"system","content":${jsonStr(systemPrompt)}},""" +
            """{"role":"user","content":${jsonStr(userPrompt)}}""" +
            """]}"""
        val start = System.currentTimeMillis()
        return try {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:11434/v1/chat/completions"))
                .timeout(java.time.Duration.ofSeconds(60))
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
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
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
            val content = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(payload)?.groupValues?.get(1) ?: ""
            LlmCallResult(promptTokens, completionTokens, ms, content.length)
        } catch (e: Exception) {
            entry(run, Category.ERROR,
                "Bridge call threw ${e.javaClass.simpleName}: ${e.message ?: ""}")
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
