package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Combined dashboard + results page. Was two separate routes (`/`
 * for summary cards + recent-runs table, `/results` for the full
 * run history); merged so the operator sees the at-a-glance summary
 * and the complete run history in a single scroll. The
 * <code>/results/{runId}</code> transcript route still lives in
 * {@link ResultsController}, which also redirects bare
 * <code>/results</code> here for any cached bookmarks.
 */
@Controller
class DashboardController(
    private val benchmarkRuns: BenchmarkRunService,
    private val registeredModels: RegisteredModelsRegistry,
    private val bugCatalog: BugCatalog
) {

    /** A single PASSED run, denormalised against its bug metadata so the
     *  leaderboard can pivot on difficulty / category without a second
     *  catalog hit per row. */
    data class PassRecord(
        val bugId: String,
        val durationMs: Long,
        val costUsd: Double,
        val difficulty: String,
        val category: String
    )

    /** Per-model leaderboard summary. Lists the operator's fastest /
     *  cheapest successful solve and how the model's solve set breaks
     *  down by bug difficulty + category — the answer to "which models
     *  are best for which kinds of problems?". */
    data class LeaderboardEntry(
        val modelId: String,
        val provider: String,
        /** Context provider used (none / oracle / bm25 / appmap-navie). */
        val contextProvider: String,
        /** AppMap recording mode (OFF / ON_RECOMMENDED / ON_ALL). */
        val appmapMode: String,
        val totalRuns: Int,
        val passedRuns: Int,
        val passRate: Double,
        val fastest: PassRecord?,
        val cheapest: PassRecord?,
        val avgPassMs: Long,
        val avgPassCostUsd: Double,
        val solvedByDifficulty: Map<String, Int>,
        val solvedByCategory: Map<String, Int>
    )

    @GetMapping("/")
    fun dashboard(model: Model, session: HttpSession): String {
        // Pull a wide window so the per-page slicing JS has every
        // available run to choose from. 500 is plenty for a single
        // operator's lifetime; older runs roll off naturally as the
        // in-memory map fills.
        val runs = benchmarkRuns.recentRuns(500)
        val total = runs.size
        val passed = runs.count { it.status.name == "PASSED" }

        model.addAttribute("totalRuns", total)
        model.addAttribute("passRate", if (total > 0) passed.toDouble() / total else 0.0)
        model.addAttribute("solvers", registeredModels.availableProviders(session))
        model.addAttribute("runs", runs)
        model.addAttribute("connectedRepos", 0)
        // "Bugs" tile counts unique bug IDs that have at least one
        // benchmark run -- consistent with Runs / Pass rate / Solvers
        // which are all derived from the live runs list. The catalog's
        // total (bugCatalog.count()) is the right number for the
        // launcher's dropdown but on the dashboard the operator wants
        // to know "how many distinct bugs have I actually benchmarked"
        // -- a 12/12 catalog with no runs should read 0 here.
        model.addAttribute("availableBugs", runs.map { it.issueId }.distinct().size)

        // Leaderboard — group PASSED runs by the FULL execution context
        // (LLM provider, model id, context provider, AppMap mode), so
        // each row is one specific configuration the operator launched.
        // Earlier the leaderboard collapsed all runs of a model into a
        // single row regardless of context/mode; that hid the very
        // signal the matrix is supposed to surface ("does adding traces
        // help?"). Difficulty + category come from the YAML catalog
        // (BugCatalog.getBug); enterprise issueIds (repo:ticket form) or
        // catalog-misses land in "(unknown)" so the totals still tally.
        data class CtxKey(val provider: String, val modelId: String,
                          val contextProvider: String, val appmapMode: String)
        fun keyOf(r: BenchmarkRunService.BenchmarkRun) =
            CtxKey(r.provider, r.modelId, r.contextProvider, r.appmapMode)
        val totalsByCtx = runs.groupingBy(::keyOf).eachCount()
        val passedRuns = runs.filter { it.status.name == "PASSED" }
        val passRecordsByCtx: Map<CtxKey, List<PassRecord>> = passedRuns
            .groupBy(::keyOf)
            .mapValues { (_, ctxRuns) ->
                ctxRuns.map { run ->
                    val bug = bugCatalog.getBug(run.issueId)
                    PassRecord(
                        bugId = run.issueId,
                        durationMs = run.durationMs,
                        costUsd = run.stats.estimatedCostUsd,
                        difficulty = bug?.difficulty?.takeIf { it.isNotBlank() } ?: "(unknown)",
                        category = bug?.category?.takeIf { it.isNotBlank() } ?: "(unknown)"
                    )
                }
            }
        val leaderboard = passRecordsByCtx.map { (ctx, records) ->
            val totalForCtx = totalsByCtx[ctx] ?: records.size
            LeaderboardEntry(
                modelId = ctx.modelId,
                provider = ctx.provider,
                contextProvider = ctx.contextProvider,
                appmapMode = ctx.appmapMode,
                totalRuns = totalForCtx,
                passedRuns = records.size,
                passRate = records.size.toDouble() / totalForCtx,
                fastest = records.minByOrNull { it.durationMs },
                cheapest = records.minByOrNull { it.costUsd },
                avgPassMs = records.map { it.durationMs }.average().toLong(),
                avgPassCostUsd = records.map { it.costUsd }.average(),
                solvedByDifficulty = records.groupingBy { it.difficulty }.eachCount()
                    .toList().sortedByDescending { it.second }.toMap(LinkedHashMap()),
                solvedByCategory = records.groupingBy { it.category }.eachCount()
                    .toList().sortedByDescending { it.second }.toMap(LinkedHashMap())
            )
        }
        // Disqualify Oracle context entries from the leaderboard. Oracle
        // ships the bug's hand-curated filesTouched list -- essential for
        // benchmarking and ceiling-measurement, but not a real-world
        // "leader" since an operator wouldn't have a curated file list
        // per bug in production. Oracle runs still appear in the runs
        // table; this only filters them out of the per-config ranking.
        .filter { it.contextProvider.lowercase() != "oracle" }
        .sortedWith(
            compareByDescending<LeaderboardEntry> { it.passedRuns }
                .thenBy { it.avgPassMs }
        )
        model.addAttribute("leaderboard", leaderboard)
        // Surface delete-result toast — set by deleteRuns() before
        // redirecting back here so the table re-renders with a one-
        // shot summary message.
        model.addAttribute("runsDeleteResult", session.getAttribute("runsDeleteResult"))
        session.removeAttribute("runsDeleteResult")
        return "dashboard"
    }

    /**
     * Bulk-delete runs by id. The dashboard table renders a checkbox
     * per row and a hidden "select all" toggle that POSTs the chosen
     * ids back here as repeated `runIds` form params. Active runs
     * (QUEUED / RUNNING) are skipped — the operator must cancel them
     * first; the response toast spells out exactly what happened.
     */
    /**
     * Cancel one in-flight run from the dashboard. Flips the run's
     * status to CANCELED so the worker thread will bail out at the
     * next checkpoint -- the LLM call already in flight still
     * completes (we can't interrupt vscode.LanguageModelChat mid-
     * stream), but no further bridge calls are made for this run
     * and the queue-aware watchdog stops counting it as active.
     * Useful when the bridge is rate-limited and the operator
     * wants to stop wasting cycles on calls that won't succeed.
     */
    @PostMapping("/runs/{runId}/cancel")
    fun cancelRun(
        @org.springframework.web.bind.annotation.PathVariable runId: String,
        session: HttpSession
    ): String {
        val ok = benchmarkRuns.cancel(runId)
        session.setAttribute("runsDeleteResult", if (ok)
            "Cancel signal sent to $runId. The current LLM call may complete; no new ones will fire."
        else
            "$runId is not active (already PASSED/FAILED/CANCELED) -- nothing to cancel.")
        return "redirect:/"
    }

    @PostMapping("/runs/delete")
    fun deleteRuns(
        @RequestParam(required = false) runIds: List<String>?,
        session: HttpSession
    ): String {
        val ids = runIds ?: emptyList()
        if (ids.isEmpty()) {
            session.setAttribute("runsDeleteResult",
                "No runs selected — pick at least one before clicking Delete.")
            return "redirect:/"
        }
        val s = benchmarkRuns.deleteRuns(ids)
        session.setAttribute("runsDeleteResult", buildString {
            append("Deleted ").append(s.deleted).append(" run").append(if (s.deleted == 1) "" else "s")
            if (s.skippedActive > 0) {
                append("; skipped ").append(s.skippedActive)
                  .append(" active (cancel first)")
            }
            if (s.missing > 0) {
                append("; ").append(s.missing).append(" id(s) not found")
            }
            append('.')
        })
        return "redirect:/"
    }

}
