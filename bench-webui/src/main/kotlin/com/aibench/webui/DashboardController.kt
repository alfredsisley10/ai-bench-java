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
        // BugCatalog already resolves bugs/ as a sibling of the
        // BankingAppManager-located banking-app/ -- same path getBug()
        // uses for per-bug yaml lookups, so the tile and the per-run
        // audit page can never disagree on whether bugs/ is reachable.
        model.addAttribute("availableBugs", bugCatalog.count())
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
