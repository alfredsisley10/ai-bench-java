package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@Controller
class ResultsController {

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
        model.addAttribute("runId", runId)
        model.addAttribute("run", null)
        return "result-detail"
    }
}
