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
    private val bankingApp: BankingAppManager
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
        // Hand-written modules in the banking-app product surface. Pulled
        // statically rather than walked because we deliberately want to
        // exclude generated-* and exclude any module whose tests aren't
        // worth recording (e.g. demo apps).
        val handWritten = setOf(
            "shared-domain", "ledger-core",
            "accounts-consumer", "accounts-business",
            "payments-hub", "cards", "lending-corporate", "lending-consumer",
            "fraud-detection", "compliance", "reg-reporting",
            "risk-engine", "statements", "app-bootstrap"
        )
        return (fromBugs + handWritten).sorted()
    }

    @GetMapping("/admin/appmap-traces")
    fun page(model: Model): String {
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
        return "admin-traces"
    }

    @PostMapping("/admin/appmap-traces/generate")
    fun generateOne(@RequestParam module: String): String {
        if (module !in targetModules()) {
            return "redirect:/admin/appmap-traces?err=unknown-module"
        }
        kickJob(module)
        return "redirect:/admin/appmap-traces?queued=$module"
    }

    @PostMapping("/admin/appmap-traces/generate-all")
    fun generateAll(): String {
        for (m in targetModules()) {
            // Skip modules that already have traces -- operator can
            // refresh via the per-module Generate button to force.
            val current = appmapTraces.realTraceCoverage(listOf(m))[m].orEmpty().size
            if (current > 0) continue
            kickJob(m)
        }
        return "redirect:/admin/appmap-traces?queued=all"
    }

    private fun kickJob(module: String) {
        val job = Job(module, Instant.now())
        activeJobs[module] = job
        executor.submit {
            try {
                job.result = appmapTraces.generateRealTracesForModule(module) { p ->
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
