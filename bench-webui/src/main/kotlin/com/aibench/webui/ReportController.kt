package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Instant

/**
 * Executive-friendly run report. Renders an HTML page tuned for
 * print (operator uses the browser's Save-as-PDF) covering:
 *   - cover page (title + generated timestamp)
 *   - what is being benchmarked + why
 *   - methodology (context providers, AppMap modes, scoring rubric)
 *   - configuration matrix actually executed
 *   - key findings (per-context pass rates + best/avg cost/time)
 *   - per-bug results table
 *   - glossary explaining the technical terms
 *
 * Pure render-from-current-state -- no parameters, no caching;
 * regenerated every page load so the operator can refresh the
 * dashboard, hit /admin/report, save-as-PDF and have the latest.
 */
@Controller
class ReportController(
    private val benchmarkRuns: BenchmarkRunService,
    private val bugCatalog: BugCatalog
) {

    data class CtxRollup(
        val provider: String,
        val modelId: String,
        val contextProvider: String,
        val appmapMode: String,
        val solved: Int,
        val total: Int,
        val passRate: Double,
        val avgMs: Long,
        val avgCostUsd: Double
    )

    data class BugRow(
        val bugId: String,
        val title: String,
        val difficulty: String,
        val category: String,
        val solvedBy: List<String>,   // human-readable config labels
        val totalAttempts: Int,
        val passedAttempts: Int
    )

    data class ContaminationRow(
        val provider: String,
        val modelId: String,
        val issueId: String,
        val issueTitle: String,
        val passes: Int,
        val total: Int,
        val problemStatement: String,
        val hints: List<String>
    )

    @GetMapping("/admin/report")
    fun report(model: Model): String {
        val runs = benchmarkRuns.recentRuns(500)
        val bugs = bugCatalog.allBugs()

        // Per-config rollup, Oracle excluded (matches leaderboard).
        data class CtxKey(val provider: String, val modelId: String,
                          val contextProvider: String, val appmapMode: String)
        val byCtx: Map<CtxKey, List<BenchmarkRunService.BenchmarkRun>> =
            runs.groupBy { CtxKey(it.provider, it.modelId, it.contextProvider, it.appmapMode) }
        val ctxRollups = byCtx.map { (k, ctxRuns) ->
            val passed = ctxRuns.count { it.status.name == "PASSED" }
            val passingRuns = ctxRuns.filter { it.status.name == "PASSED" }
            CtxRollup(
                provider = k.provider,
                modelId = k.modelId,
                contextProvider = k.contextProvider,
                appmapMode = k.appmapMode,
                solved = passed,
                total = ctxRuns.size,
                passRate = if (ctxRuns.isNotEmpty()) passed.toDouble() / ctxRuns.size else 0.0,
                avgMs = if (passingRuns.isNotEmpty())
                    passingRuns.map { it.durationMs }.average().toLong() else 0L,
                avgCostUsd = if (passingRuns.isNotEmpty())
                    passingRuns.map { it.stats.estimatedCostUsd }.average() else 0.0
            )
        }.sortedWith(compareByDescending<CtxRollup> { it.solved }.thenBy { it.avgMs })

        // Per-bug summary: which configurations solved this bug?
        val byBug: Map<String, List<BenchmarkRunService.BenchmarkRun>> = runs.groupBy { it.issueId }
        val bugRows = bugs.map { bug ->
            val attempts = byBug[bug.id] ?: emptyList()
            val passing = attempts.filter { it.status.name == "PASSED" }
            BugRow(
                bugId = bug.id,
                title = bug.title,
                difficulty = bug.difficulty.ifBlank { "(unknown)" },
                category = bug.category.ifBlank { "(unknown)" },
                solvedBy = passing.map {
                    "${it.provider}/${it.modelId} · ${it.contextProvider} · ${it.appmapMode}"
                }.distinct().sorted(),
                totalAttempts = attempts.size,
                passedAttempts = passing.size
            )
        }

        // Distinct configs, models, contexts, modes that were actually run.
        val distinctModels = runs.map { it.modelId }.distinct().sorted()
        val distinctContexts = runs.map { it.contextProvider }.distinct().sorted()
        val distinctModes = runs.map { it.appmapMode }.distinct().sorted()
        val distinctBugs = runs.map { it.issueId }.distinct().sorted()

        model.addAttribute("generatedAt", Instant.now().toString())
        model.addAttribute("totalRuns", runs.size)
        model.addAttribute("totalBugs", bugs.size)
        model.addAttribute("bugsBenchmarked", distinctBugs.size)
        model.addAttribute("ctxRollups", ctxRollups)
        model.addAttribute("nonOracleRollups",
            ctxRollups.filter { it.contextProvider.lowercase() != "oracle" })
        model.addAttribute("bugRows", bugRows)
        model.addAttribute("distinctModels", distinctModels)
        model.addAttribute("distinctContexts", distinctContexts)
        model.addAttribute("distinctModes", distinctModes)

        // Pre-training contamination block. Same logic as the dashboard:
        // any (model, bug) pair that PASSED with contextProvider="none"
        // is suspect -- either model memorization OR a leaky problem
        // statement. The report shows the problem statement + hints
        // inline so the reader can audit the bug definition.
        val contamination = runs
            .filter { it.contextProvider.equals("none", ignoreCase = true) }
            .groupBy { Triple(it.provider, it.modelId, it.issueId) }
            .mapNotNull { (k, group) ->
                val passes = group.count { it.status.name == "PASSED" }
                if (passes == 0) return@mapNotNull null
                val sample = group.first()
                val bug = bugCatalog.getBug(k.third)
                ContaminationRow(
                    provider = k.first, modelId = k.second, issueId = k.third,
                    issueTitle = sample.issueTitle,
                    passes = passes, total = group.size,
                    problemStatement = bug?.problemStatement ?: "",
                    hints = bug?.hints ?: emptyList()
                )
            }
            .sortedWith(compareByDescending<ContaminationRow> { it.passes }
                .thenBy { it.modelId }.thenBy { it.issueId })
        model.addAttribute("contamination", contamination)
        return "admin-report"
    }
}
