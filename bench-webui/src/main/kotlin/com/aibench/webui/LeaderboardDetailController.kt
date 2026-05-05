package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Per-configuration leaderboard drilldown on its own page.
 *
 * Was an inline expand/collapse row inside the dashboard
 * leaderboard tbody, but that approach scrambled sort + filter
 * + pagination -- the drill rows were siblings of the data rows,
 * so "show 10 rows" would surface 1 data row + 9 drill rows.
 * Splitting it onto a separate URL keeps the dashboard table
 * shape clean and gives the drilldown its own bookmarkable +
 * sortable view.
 *
 * Re-uses [DashboardController.BugDrilldown] so the row shape
 * matches what the dashboard's per-bug rollup used to render.
 */
@Controller
class LeaderboardDetailController(
    private val benchmarkRuns: BenchmarkRunService,
    private val bugCatalog: BugCatalog,
    private val hiddenBugs: HiddenBugsService
) {

    @GetMapping("/admin/leaderboard/detail")
    fun detail(
        @RequestParam provider: String,
        @RequestParam modelId: String,
        @RequestParam contextProvider: String,
        @RequestParam appmapMode: String,
        model: Model
    ): String {
        val hidden = hiddenBugs.hiddenBugs()
        val ctxRuns = benchmarkRuns.recentRuns(500)
            .filter { it.issueId !in hidden }
            .filter {
                it.provider == provider &&
                it.modelId == modelId &&
                it.contextProvider == contextProvider &&
                it.appmapMode == appmapMode
            }

        val byBug = ctxRuns.groupBy { it.issueId }
        val rows = byBug.map { (bugId, runsForBug) ->
            val passed = runsForBug.count { it.status.name == "PASSED" }
            val bug = bugCatalog.getBug(bugId)
            val newest = runsForBug.maxBy { it.startedAt }
            DashboardController.BugDrilldown(
                bugId = bugId,
                difficulty = bug?.difficulty?.takeIf { it.isNotBlank() } ?: "(unknown)",
                category = bug?.category?.takeIf { it.isNotBlank() } ?: "(unknown)",
                totalRuns = runsForBug.size,
                passedRuns = passed,
                avgDurationMs = runsForBug.map { it.durationMs }.average().toLong(),
                avgCostUsd = runsForBug.map { it.stats.estimatedCostUsd }.average(),
                latestRunId = newest.id,
                latestStatus = newest.status.name
            )
        }.sortedWith(
            compareByDescending<DashboardController.BugDrilldown> { it.passedRuns }
                .thenBy { it.bugId }
        )

        model.addAttribute("provider", provider)
        model.addAttribute("modelId", modelId)
        model.addAttribute("contextProvider", contextProvider)
        model.addAttribute("appmapMode", appmapMode)
        model.addAttribute("rows", rows)
        model.addAttribute("totalRuns", ctxRuns.size)
        model.addAttribute("passedRuns", ctxRuns.count { it.status.name == "PASSED" })
        model.addAttribute("solvedBugs",
            ctxRuns.filter { it.status.name == "PASSED" }.map { it.issueId }.distinct().size)
        return "admin-leaderboard-detail"
    }
}
