package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

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
class DashboardController {

    @GetMapping("/")
    fun dashboard(model: Model): String {
        model.addAttribute("totalRuns", 0)
        model.addAttribute("passRate", 0.0)
        model.addAttribute("solvers", listOf("corp-openai", "copilot"))
        model.addAttribute("connectedRepos", 0)
        model.addAttribute("availableBugs", 12)
        // Merged template iterates `runs` for the full-history table.
        // Empty until the benchmark service is plumbed in.
        model.addAttribute("runs", emptyList<Any>())
        return "dashboard"
    }
}
