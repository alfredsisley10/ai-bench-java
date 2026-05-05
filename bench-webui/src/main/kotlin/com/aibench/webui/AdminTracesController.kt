package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Admin surface for the real AppMap-trace cache. Lists per-module
 * trace coverage and lets the operator kick off a per-module
 * `./gradlew :MODULE:test -Pappmap_enabled=true` job that produces
 * traces under banking-app/<module>/tmp/appmap/junit/. Once
 * populated, [AppMapTraceManager.ensureTracesExist] prefers those
 * real recordings over the synthetic-stub fallback when running
 * benchmarks with appmapMode != OFF.
 *
 * Generated-* modules (regional/channels/brands/swift/nacha) are
 * intentionally excluded from the default list -- they hold ~970
 * auto-generated tests that aren't useful for benchmarking and would
 * dominate the per-module run time.
 */
@Controller
class AdminTracesController(
    private val appmapTraces: AppMapTraceManager,
    private val bugCatalog: BugCatalog,
    private val bankingApp: BankingAppManager,
    private val llmDiagnostician: LlmDiagnostician,
    private val registeredModelsRegistry: RegisteredModelsRegistry
) {
    private val log = LoggerFactory.getLogger(AdminTracesController::class.java)

    /** Single-thread executor: AppMap test runs hammer the JVM's gradle
     *  daemon and the JIT cache; running multiple in parallel just
     *  thrashes both. Operator can queue a "generate-all" and walk away. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "appmap-trace-gen").apply { isDaemon = true }
    }

    /** Map module -> active job state (null when no job is running for
     *  this module). Survives across page renders so the UI can show
     *  "running…" without coupling to any particular request. */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    data class Job(
        val module: String,
        val startedAt: Instant,
        @Volatile var endedAt: Instant? = null,
        @Volatile var result: AppMapTraceManager.GenerationResult? = null,
        @Volatile var error: String? = null,
        @Volatile var process: Process? = null
    )

    data class Row(
        val module: String,
        val testCount: Int,
        val traceCount: Int,
        val running: Boolean,
        val lastError: String?,
        val lastDurationMs: Long?
    )

    /** Modules the bug catalog references, plus a curated default set
     *  of hand-written banking-app modules. Generated-* modules are
     *  excluded — they aren't used by benchmarks and would balloon
     *  the page. */
    private fun targetModules(): List<String> {
        val fromBugs = bugCatalog.allBugs().mapNotNull {
            it.module.takeIf { m -> m.isNotBlank() }
        }.toSet()
        // Auto-discover top-level gradle modules by walking
        // banking-app/<dir>/build.gradle.kts -- each subdir with a
        // build.gradle.kts is a gradle subproject. Drops the
        // generated:* product-flavor modules (~70+ auto-generated,
        // not useful for benchmarking) by excluding the "generated"
        // dir entirely. Survives banking-app adding / renaming /
        // removing modules with no code change here.
        val repo = runCatching { bankingApp.bankingAppDir }.getOrNull()
        val discovered = if (repo != null && repo.isDirectory) {
            (repo.listFiles { f -> f.isDirectory && f.name != "generated" } ?: emptyArray())
                .filter { java.io.File(it, "build.gradle.kts").isFile ||
                          java.io.File(it, "build.gradle").isFile }
                .map { it.name }
                .toSet()
        } else emptySet()
        return (fromBugs + discovered).sorted()
    }

    @GetMapping("/admin/appmap-traces")
    fun page(model: Model, session: jakarta.servlet.http.HttpSession): String {
        val modules = targetModules()
        val coverage = appmapTraces.realTraceCoverage(modules)
        val testCounts = countTestsPerModule(modules)
        val rows = modules.map { m ->
            val active = activeJobs[m]
            Row(
                module = m,
                testCount = testCounts[m] ?: 0,
                traceCount = coverage[m]?.size ?: 0,
                running = active != null && active.endedAt == null,
                lastError = active?.error,
                lastDurationMs = active?.result?.durationMs
            )
        }
        model.addAttribute("rows", rows)
        model.addAttribute("totalTraces", coverage.values.sumOf { it.size })
        model.addAttribute("totalTests", testCounts.values.sum())
        model.addAttribute("activeCount", rows.count { it.running })
        // Models available for the per-module "Diagnose trace recording
        // failure" buttons. Filter to chat-capable, non-Navie entries
        // (Navie is a context-search loop, not a chat completion).
        model.addAttribute("diagnoseModels",
            registeredModelsRegistry.availableModels(session)
                .filter { it.provider != "appmap-navie" })
        return "admin-traces"
    }

    /**
     * Diagnose a failed (or in-flight-but-stalled) trace generation
     * job using the operator's chosen LLM. Pairs the gradle output
     * tail captured on the Job with a system prompt that asks for
     * TWO sections of recommendations:
     *   1. Local resolutions the operator can apply on this machine
     *      now (env tweaks, gradle.properties edits, JDK install,
     *      cleared caches, etc).
     *   2. Source-code-project fixes that should be raised as PRs
     *      against the canonical repo so the failure mode is
     *      eliminated for everyone (build.gradle.kts adjustments,
     *      AppMap plugin pin, settings.gradle.kts checks, etc).
     */
    @org.springframework.web.bind.annotation.GetMapping("/admin/appmap-traces/diagnose-with-llm")
    @org.springframework.web.bind.annotation.ResponseBody
    fun diagnoseWithLlm(
        @RequestParam module: String,
        @RequestParam(defaultValue = "copilot-default") modelId: String,
        session: jakarta.servlet.http.HttpSession
    ): Map<String, Any?> {
        val job = activeJobs[module]
            ?: return mapOf(
                "ok" to false,
                "reason" to "No trace-generation job recorded for module '$module'. " +
                    "Click Generate first; the diagnose button uses the captured gradle output."
            )
        val tail = job.result?.tail
            ?: job.error
            ?: "(no output captured -- job may still be initialising)"
        val exit = job.result?.exitCode
        val durationMs = job.result?.durationMs ?: 0L

        val systemPrompt = """
            You are a senior Gradle / AppMap / JVM engineer triaging a
            failed AppMap trace recording job on an enterprise developer
            box. The operator clicked "Generate traces" for a single
            banking-app module, the gradle subprocess exited non-zero
            (or produced zero .appmap.json files), and the captured
            output tail is below.

            Respond in TWO clearly-delimited sections:

            ## Local resolutions
            Concrete steps the operator can take on THIS machine right
            now to unblock the trace generation. Examples: install a
            specific JDK toolchain, adjust ~/.gradle/gradle.properties,
            kill a stale daemon, set an env var, change a /proxy or
            /llm setting in bench-webui. Cite specific commands.

            ## Source-code project fixes
            Changes that should be raised as PRs against the
            ai-bench-java repo so this failure mode stops happening for
            future developers. Examples: bump or pin an AppMap plugin
            version in banking-app/build.gradle.kts, add a guard in
            settings.gradle.kts, write a clearer error from
            AppMapTraceManager.generateRealTracesForModule, document a
            JDK requirement in README. Name the file path and the
            change in 1-2 sentences each.

            Be concrete. Cite line numbers from the gradle output when
            possible. 2-5 bullets per section. If the output doesn't
            reveal anything actionable, say so in one line per section
            instead of inventing recommendations.
        """.trimIndent()

        val userPrompt = """
            Module: `$module`
            Gradle exit code: ${exit ?: "(none recorded)"}
            Job duration: ${durationMs / 1000}s
            Job error string: ${job.error ?: "(none)"}

            Captured gradle output tail:
            ```
            $tail
            ```
        """.trimIndent()

        return llmDiagnostician
            .diagnose(modelId, systemPrompt, userPrompt, session, timeoutSec = 60)
            .toMap()
    }

    @PostMapping("/admin/appmap-traces/generate")
    fun generateOne(
        @RequestParam module: String,
        @RequestParam(required = false) verbosity: String?
    ): String {
        if (module !in targetModules()) {
            return "redirect:/admin/appmap-traces?err=unknown-module"
        }
        kickJob(module, AppMapService.GradleVerbosity.parse(verbosity))
        return "redirect:/admin/appmap-traces?queued=$module"
    }

    @PostMapping("/admin/appmap-traces/generate-all")
    fun generateAll(
        @RequestParam(required = false) verbosity: String?
    ): String {
        val v = AppMapService.GradleVerbosity.parse(verbosity)
        for (m in targetModules()) {
            // Skip modules that already have traces -- operator can
            // refresh via the per-module Generate button to force.
            val current = appmapTraces.realTraceCoverage(listOf(m))[m].orEmpty().size
            if (current > 0) continue
            kickJob(m, v)
        }
        return "redirect:/admin/appmap-traces?queued=all"
    }

    private fun kickJob(
        module: String,
        verbosity: AppMapService.GradleVerbosity = AppMapService.GradleVerbosity.QUIET
    ) {
        val job = Job(module, Instant.now())
        activeJobs[module] = job
        executor.submit {
            try {
                job.result = appmapTraces.generateRealTracesForModule(module, verbosity) { p ->
                    job.process = p
                }
                if (job.result?.ok != true) {
                    job.error = "exit=${job.result?.exitCode}; tail: ${job.result?.tail?.takeLast(500)}"
                }
            } catch (e: Exception) {
                log.warn("appmap trace gen failed for {}: {}", module, e.message)
                job.error = e.message ?: e.javaClass.simpleName
            } finally {
                job.endedAt = Instant.now()
            }
        }
    }

    /** Snapshot of every trace-gen job currently in flight (endedAt
     *  == null). Used by the dashboard's "background tasks" tile so
     *  the operator sees gradle running invisible to the runs table. */
    fun runningJobs(): List<Job> =
        activeJobs.values.filter { it.endedAt == null }
            .sortedByDescending { it.startedAt }

    /** Cancel an in-flight trace generation for a module. Kills the
     *  gradle subprocess (and its descendants -- the test JVM is a
     *  child) so CPU is freed immediately. */
    @PostMapping("/admin/appmap-traces/cancel")
    fun cancelOne(@RequestParam module: String): String {
        val job = activeJobs[module] ?: return "redirect:/admin/appmap-traces?err=not-running"
        if (job.endedAt != null) return "redirect:/admin/appmap-traces?err=not-running"
        val p = job.process
        if (p != null && p.isAlive) {
            runCatching { p.descendants().forEach { it.destroyForcibly() } }
            runCatching { p.destroyForcibly() }
        }
        job.error = "canceled by operator"
        job.endedAt = Instant.now()
        return "redirect:/admin/appmap-traces?canceled=$module"
    }

    /** Count `*Test.java` files per module under banking-app. Used to
     *  show "12 of 47 tests have traces" coverage on the page. */
    private fun countTestsPerModule(modules: Collection<String>): Map<String, Int> {
        val repo = runCatching { bankingApp.bankingAppDir }.getOrNull()
            ?: return modules.associateWith { 0 }
        return modules.associateWith { m ->
            val dir = java.io.File(repo, "$m/src/test/java")
            if (dir.isDirectory)
                dir.walkTopDown().count { it.isFile && it.name.endsWith("Test.java") }
            else 0
        }
    }
}
