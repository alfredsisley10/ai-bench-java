package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@Controller
class ResultsController(
    private val benchmarkRuns: BenchmarkRunService,
    private val bugCatalog: BugCatalog
) {

    /**
     * Bare /results used to render its own list page; that's been
     * merged into the dashboard so the operator gets summary + full
     * history in one scroll. Redirect cached bookmarks back to the
     * dashboard. The /results/{runId} transcript route below is
     * unchanged.
     */
    @GetMapping("/results")
    fun list(): String = "redirect:/"

    @GetMapping("/results/{runId}")
    fun detail(@PathVariable runId: String, model: Model): String {
        // Fetch the live run from the service so the template has
        // real fields (status, phase, stats, seedResults, logEntries)
        // to render. The previous stub passed run=null, which left the
        // template stuck on its empty-state branch and hid every
        // failing run from drilldown.
        val run = benchmarkRuns.get(runId)
        model.addAttribute("runId", runId)
        model.addAttribute("run", run)
        return "result-detail"
    }

    /**
     * Per-seed audit drill-down: bug description, full source files
     * shown to the solver, prompts sent, raw LLM response, extracted
     * patch, verification gradle command, test stdout tail + exit
     * code. Renders a single page so the operator can read the run
     * top-to-bottom without bouncing between tools.
     */
    @GetMapping("/results/{runId}/seed/{seedNumber}/audit")
    fun seedAudit(
        @PathVariable runId: String,
        @PathVariable seedNumber: Int,
        model: Model
    ): String {
        val run = benchmarkRuns.get(runId)
        model.addAttribute("runId", runId)
        model.addAttribute("run", run)
        model.addAttribute("seedNumber", seedNumber)
        if (run != null) {
            val audit = run.seedAudits.firstOrNull { it.seed == seedNumber }
            val seedResult = run.seedResults.firstOrNull { it.seed == seedNumber }
            model.addAttribute("audit", audit)
            model.addAttribute("seedResult", seedResult)
            // Bug metadata + buggy source snapshots. Both can be null /
            // empty when the bugs/ dir or banking-app git checkout
            // aren't reachable -- the template degrades gracefully with
            // a "metadata not loaded" banner.
            val bug = bugCatalog.getBug(run.issueId)
            model.addAttribute("bug", bug)
            val buggyFiles = bug?.filesTouched?.map { path ->
                bugCatalog.readFileAtRef(bug.breakCommit, path)
            } ?: emptyList()
            val fixedFiles = bug?.filesTouched?.map { path ->
                bugCatalog.readFileAtRef(bug.fixCommit, path)
            } ?: emptyList()
            // Pull the hidden verification test source so the operator
            // can see exactly what the solver was graded against.
            val hiddenTestSnapshot = bug?.hiddenTestFile?.let {
                bugCatalog.readFileAtRef(bug.fixCommit, it)
            }
            model.addAttribute("buggyFiles", buggyFiles)
            model.addAttribute("fixedFiles", fixedFiles)
            model.addAttribute("hiddenTestSnapshot", hiddenTestSnapshot)
        } else {
            model.addAttribute("audit", null)
            model.addAttribute("seedResult", null)
            model.addAttribute("bug", null)
            model.addAttribute("buggyFiles", emptyList<Any>())
            model.addAttribute("fixedFiles", emptyList<Any>())
            model.addAttribute("hiddenTestSnapshot", null)
        }
        return "result-seed-audit"
    }
}
