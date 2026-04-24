package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@Controller
class ResultsController {

    @GetMapping("/results")
    fun list(model: Model): String {
        model.addAttribute("runs", emptyList<Any>())
        return "results"
    }

    @GetMapping("/results/{runId}")
    fun detail(@PathVariable runId: String, model: Model): String {
        model.addAttribute("runId", runId)
        model.addAttribute("run", null)
        return "result-detail"
    }
}
